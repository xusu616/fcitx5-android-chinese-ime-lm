/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.Keep
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDao
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.ui.main.MediaDeleteRequestActivity
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.fcitx.fcitx5.android.utils.timestamp
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ClipboardManager : ClipboardManager.OnPrimaryClipChangedListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    private lateinit var clbDb: ClipboardDatabase
    private lateinit var clbDao: ClipboardDao

    fun interface OnClipboardUpdateListener {
        fun onUpdate(entry: ClipboardEntry)
    }

    private val clipboardManager = appContext.clipboardManager

    private val mutex = Mutex()

    var itemCount: Int = 0
        private set

    private suspend fun updateItemCount() {
        itemCount = clbDao.itemCount()
    }

    private val onUpdateListeners = WeakHashSet<OnClipboardUpdateListener>()

    var transformer: ((String) -> String)? = null

    fun addOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.add(listener)
    }

    fun removeOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.remove(listener)
    }

    private val enabledPref = AppPrefs.getInstance().clipboard.clipboardListening

    @Keep
    private val enabledListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
        if (value) {
            clipboardManager.addPrimaryClipChangedListener(this)
            registerScreenshotObserver()
        } else {
            clipboardManager.removePrimaryClipChangedListener(this)
            unregisterScreenshotObserver()
        }
    }

    private var screenshotObserver: ScreenshotObserver? = null

    fun hasMediaReadPermission(context: Context = appContext): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasMediaManagePermission(context: Context = appContext): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> MediaStore.canManageMedia(context)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
            else -> hasMediaReadPermission(context)
        }
    }

    private fun registerScreenshotObserver() {
        if (screenshotObserver == null) {
            val observer = ScreenshotObserver(appContext, Handler(Looper.getMainLooper())) { uri, mimeType, onSaveFailed ->
                handleScreenshot(uri, mimeType, onSaveFailed)
            }
            screenshotObserver = observer
            try {
                appContext.contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    observer
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to register screenshot observer")
            }
        }
    }

    private fun unregisterScreenshotObserver() {
        screenshotObserver?.let {
            try {
                appContext.contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                Timber.w(e, "Failed to unregister screenshot observer")
            }
            screenshotObserver = null
        }
    }

    private fun handleScreenshot(uri: Uri, mimeType: String, onSaveFailed: () -> Unit) {
        launch {
            mutex.withLock {
                try {
                    if (clbDao.findBySourceUri(uri.toString()) != null) {
                        return@withLock
                    }
                    val file = saveImageToLocal(appContext, uri, mimeType)
                    if (file == null) {
                        onSaveFailed()
                        return@withLock
                    }
                    val entry = ClipboardEntry(
                        text = "",
                        imagePath = file.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        type = mimeType,
                        sourceUri = uri.toString()
                    )
                    val insertedEntry = clbDb.withTransaction {
                        val rowId = clbDao.insert(entry)
                        removeOutdated()
                        clbDao.get(rowId) ?: entry
                    }
                    updateLastEntry(insertedEntry)
                    updateItemCount()
                } catch (e: Exception) {
                    Timber.w(e, "Failed to insert screenshot into clipboard")
                }
            }
        }
    }

    private val deletingUris = ConcurrentHashMap.newKeySet<String>()
    private val pendingDeleteRequests = ConcurrentHashMap.newKeySet<String>()

    internal val mediaDeleteFocus = MediaDeleteFocusState()

    internal fun shouldDeleteMediaOnPaste(
        autoDelete: Boolean,
        strictDelete: Boolean,
        committed: Boolean
    ): Boolean = ScreenshotPolicy.shouldDeleteMediaOnPaste(autoDelete, strictDelete, committed)

    internal fun isScreenshotNameOrPath(name: String, pathOrRelative: String): Boolean =
        ScreenshotPolicy.isScreenshotNameOrPath(name, pathOrRelative)

    internal fun isMediaStoreUri(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        if (!ScreenshotPolicy.isMediaStoreAuthority(uri.authority)) return false
        val path = uri.path ?: return false
        return path.contains("/images/") || path.contains("/file/")
    }

    internal fun resolveMediaStoreImageUri(context: Context, uri: Uri): Uri? {
        if (isMediaStoreUri(uri)) {
            val path = uri.path ?: return null
            if (path.contains("/file/")) {
                val mime = try {
                    context.contentResolver.getType(uri)
                } catch (_: Exception) {
                    null
                }
                if (mime?.startsWith("image/") != true) return null
            }
            val id = try {
                ContentUris.parseId(uri)
            } catch (_: Exception) {
                -1L
            }
            if (id >= 0L && uri.authority == MediaStore.AUTHORITY) {
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
            return uri
        }
        if (uri.scheme == ContentResolver.SCHEME_CONTENT &&
            uri.authority == "com.android.providers.media.documents" &&
            DocumentsContract.isDocumentUri(context, uri)
        ) {
            val docId = try {
                DocumentsContract.getDocumentId(uri)
            } catch (_: Exception) {
                null
            }
            val id = ScreenshotPolicy.parseMediaDocumentImageId(docId) ?: return null
            return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        }
        return null
    }

    private fun queryOpenableNameAndSize(context: Context, uri: Uri): Pair<String?, Long?> {
        var name: String? = null
        var size: Long? = null
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameCol != -1) {
                        name = cursor.getString(nameCol)?.takeIf { it.isNotEmpty() }
                    }
                    val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeCol != -1 && !cursor.isNull(sizeCol)) {
                        size = cursor.getLong(sizeCol).takeIf { it > 0L }
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (name.isNullOrEmpty()) {
            name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        }
        return name to size
    }

    internal fun findRecentScreenshotMediaUri(context: Context, clipUri: Uri): Uri? {
        if (!hasMediaReadPermission(context)) return null
        val (displayName, clipSize) = queryOpenableNameAndSize(context, clipUri)
        if (displayName.isNullOrEmpty() || !isScreenshotNameOrPath(displayName, "")) return null
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.SIZE
        )
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(displayName)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                while (cursor.moveToNext()) {
                    val rowSize = if (sizeCol != -1 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L
                    if (clipSize != null && rowSize > 0L && rowSize != clipSize) continue
                    val id = cursor.getLong(idCol)
                    return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to resolve screenshot MediaStore URI for $clipUri")
        }
        return null
    }

    @JvmStatic
    fun resolveScreenshotSourceUri(context: Context, uri: Uri): String? {
        val resolved = resolveMediaStoreImageUri(context, uri)
            ?: findRecentScreenshotMediaUri(context, uri)
            ?: return null
        return if (isScreenshotUri(context, resolved)) resolved.toString() else null
    }

    internal fun resolveLocalFileProviderImagePath(context: Context, uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        if (uri.authority != "${context.packageName}.clipboard.fileprovider") return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != "clipboard_images") return null
        val fileName = segments[1]
        if (fileName.isEmpty() || fileName.contains('/') || fileName.contains('\\') || fileName == "." || fileName == "..") {
            return null
        }
        return context.filesDir.resolve("clipboard_images").resolve(fileName).absolutePath
    }

    @JvmStatic
    fun isScreenshotUri(context: Context, uri: Uri): Boolean {
        val targetUri = resolveMediaStoreImageUri(context, uri) ?: return false
        val columns = mutableListOf(MediaStore.Images.Media.DISPLAY_NAME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            columns.add(MediaStore.Images.Media.RELATIVE_PATH)
        } else {
            columns.add(MediaStore.Images.Media.DATA)
        }
        try {
            context.contentResolver.query(targetUri, columns.toTypedArray(), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val name = if (nameCol != -1) cursor.getString(nameCol) ?: "" else ""
                    val pathOrRelative = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val relCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                        if (relCol != -1) cursor.getString(relCol) ?: "" else ""
                    } else {
                        val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                        if (dataCol != -1) cursor.getString(dataCol) ?: "" else ""
                    }
                    return isScreenshotNameOrPath(name, pathOrRelative)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to query screenshot metadata for $targetUri")
        }
        return false
    }

    private fun mediaUriExists(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } == true
        } catch (_: SecurityException) {
            // Query may be denied without READ_MEDIA_IMAGES; let delete / delete request handle it
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun clearEntrySourceUri(entryId: Int, uriStr: String? = null) {
        if (entryId > 0) {
            clbDao.clearSourceUri(entryId)
        }
        if (uriStr != null) {
            clbDao.clearSourceUriByUri(uriStr)
        }
        withContext(Dispatchers.Main.immediate) {
            lastEntry?.let {
                if ((entryId > 0 && it.id == entryId) || (uriStr != null && it.sourceUri == uriStr)) {
                    lastEntry = it.copy(sourceUri = null)
                }
            }
        }
    }

    internal fun acknowledgeDeleteRequest(requestId: String?) {
        if (requestId != null) {
            pendingDeleteRequests.remove(requestId)
        }
    }

    internal fun onDeleteRequestResult(
        context: Context,
        uri: Uri,
        entryId: Int,
        strict: Boolean,
        approved: Boolean,
        requestId: String? = null,
    ) {
        mediaDeleteFocus.finish(requestId)
        val uriStr = uri.toString()
        deletingUris.remove(uriStr)
        launch(Dispatchers.IO) {
            if (approved || !mediaUriExists(context, uri) || !strict) {
                clearEntrySourceUri(entryId, uriStr)
            }
        }
    }

    private suspend fun deleteSourceMediaUri(
        context: Context,
        entryId: Int,
        fallbackSourceUri: String,
        strict: Boolean,
        origin: MediaDeleteFocusState.Editor?,
    ) {
        val effectiveUriStr = if (entryId > 0) {
            val dbEntry = clbDao.getAny(entryId)
            if (dbEntry != null) {
                dbEntry.sourceUri ?: return
            } else if (strict) {
                fallbackSourceUri
            } else {
                return
            }
        } else {
            fallbackSourceUri
        }
        deleteMediaUri(context, Uri.parse(effectiveUriStr), entryId, strict, origin)
    }

    private suspend fun deleteMediaUri(
        context: Context,
        uri: Uri,
        entryId: Int,
        strict: Boolean,
        origin: MediaDeleteFocusState.Editor?,
    ) {
        val targetUri = resolveMediaStoreImageUri(context, uri) ?: uri
        val uriStr = targetUri.toString()
        if (!deletingUris.add(uriStr)) return
        var handedOffToActivity = false
        try {
            if (!mediaUriExists(context, targetUri)) {
                clearEntrySourceUri(entryId, uriStr)
                return
            }
            var needDeleteRequest = false
            var intentSender: IntentSender? = null
            try {
                val deleted = context.contentResolver.delete(targetUri, null, null)
                if (deleted > 0 || !mediaUriExists(context, targetUri)) {
                    clearEntrySourceUri(entryId, uriStr)
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    needDeleteRequest = true
                }
            } catch (e: SecurityException) {
                if (!mediaUriExists(context, targetUri)) {
                    clearEntrySourceUri(entryId, uriStr)
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    needDeleteRequest = true
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                    intentSender = e.userAction.actionIntent.intentSender
                } else {
                    Timber.w(e, "No permission to delete screenshot $targetUri")
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to delete pasted screenshot $targetUri")
            }

            if (needDeleteRequest && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    intentSender = MediaStore.createDeleteRequest(
                        context.contentResolver,
                        listOf(targetUri)
                    ).intentSender
                } catch (ex: Exception) {
                    Timber.w(ex, "Failed to create MediaStore delete request for $targetUri")
                }
            }

            val sender = intentSender ?: return
            val requestId = UUID.randomUUID().toString()
            pendingDeleteRequests.add(requestId)
            val launched = withContext(Dispatchers.Main) {
                try {
                    if (origin != null && (context as? FcitxInputMethodService)?.captureMediaDeleteOrigin() == origin) {
                        mediaDeleteFocus.begin(requestId, origin)
                    }
                    MediaDeleteRequestActivity.launch(
                        context,
                        sender,
                        targetUri,
                        entryId,
                        strict,
                        requestId
                    )
                    true
                } catch (ex: Exception) {
                    Timber.w(ex, "Failed to launch MediaDeleteRequestActivity for $targetUri")
                    false
                }
            }
            if (launched) {
                handedOffToActivity = true
                // Guard against silent BAL drop where onCreate never runs
                launch {
                    delay(3_000L)
                    if (pendingDeleteRequests.remove(requestId)) {
                        mediaDeleteFocus.cancel(requestId)
                        deletingUris.remove(uriStr)
                    }
                }
            } else {
                mediaDeleteFocus.cancel(requestId)
                pendingDeleteRequests.remove(requestId)
            }
        } finally {
            if (!handedOffToActivity) {
                deletingUris.remove(uriStr)
            }
        }
    }

    fun onImagePasted(context: Context, entry: ClipboardEntry, committed: Boolean = true) {
        val clipboardPrefs = AppPrefs.getInstance().clipboard
        val strict = clipboardPrefs.strictDeletePastedScreenshot.getValue()
        val autoDelete = clipboardPrefs.autoDeletePastedScreenshot.getValue()
        if (!shouldDeleteMediaOnPaste(autoDelete, strict, committed)) return
        val sourceUriStr = entry.sourceUri ?: return
        val origin = (context as? FcitxInputMethodService)?.captureMediaDeleteOrigin()
        launch(Dispatchers.IO) {
            deleteSourceMediaUri(context, entry.id, sourceUriStr, strict, origin)
        }
    }

    fun onSystemPasteAttempted(context: Context) {
        if (!AppPrefs.getInstance().clipboard.strictDeletePastedScreenshot.getValue()) return
        val origin = (context as? FcitxInputMethodService)?.captureMediaDeleteOrigin()
        val clip = try {
            clipboardManager.primaryClip
        } catch (e: Exception) {
            Timber.w(e, "Failed to read primary clip on paste attempt")
            return
        } ?: return
        if (clip.itemCount <= 0) return
        val item = clip.getItemAt(0) ?: return
        val uri = item.uri ?: return
        launch(Dispatchers.IO) {
            try {
                val localPath = resolveLocalFileProviderImagePath(context, uri)
                if (localPath != null) {
                    val entry = clbDao.findByImagePath(localPath)
                        ?: lastEntry?.takeIf { it.imagePath == localPath }
                    val sourceUriStr = entry?.sourceUri ?: return@launch
                    deleteSourceMediaUri(context, entry.id, sourceUriStr, strict = true, origin = origin)
                    return@launch
                }
                val mediaUri = resolveMediaStoreImageUri(context, uri)
                    ?: findRecentScreenshotMediaUri(context, uri)
                    ?: return@launch
                val mediaUriStr = mediaUri.toString()
                val rawUriStr = uri.toString()
                val entry = clbDao.findBySourceUri(mediaUriStr)
                    ?: (if (rawUriStr != mediaUriStr) clbDao.findBySourceUri(rawUriStr) else null)
                    ?: lastEntry?.takeIf { it.sourceUri == mediaUriStr || it.sourceUri == rawUriStr }
                if (entry != null) {
                    delay(400L)
                    deleteSourceMediaUri(context, entry.id, entry.sourceUri ?: mediaUriStr, strict = true, origin = origin)
                } else if (isScreenshotUri(context, mediaUri)) {
                    delay(400L)
                    deleteMediaUri(context, mediaUri, entryId = -1, strict = true, origin = origin)
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to delete media image on system paste attempt for $uri")
            }
        }
    }

    @JvmStatic
    fun saveImageToLocal(context: Context, uri: Uri, mimeType: String): File? {
        return try {
            val dir = context.filesDir.resolve("clipboard_images")
            if (!dir.exists()) dir.mkdirs()
            val ext = when (mimeType) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                else -> "png"
            }
            val file = File(dir, "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (file.exists() && file.length() > 0) file else null
        } catch (e: Exception) {
            Timber.w(e, "Failed to save image to local")
            null
        }
    }

    private class ScreenshotObserver(
        private val context: Context,
        handler: Handler,
        private val onScreenshotDetected: (Uri, String, () -> Unit) -> Unit
    ) : ContentObserver(handler) {
        private val handledIds = LinkedHashSet<Long>()

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            checkScreenshot(uri)
        }

        private fun checkScreenshot(changedUri: Uri?) {
            if (!hasMediaReadPermission(context)) return
            try {
                val projectionList = mutableListOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.SIZE,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    projectionList.add(MediaStore.Images.Media.IS_PENDING)
                }
                val projection = projectionList.toTypedArray()

                val specificId = changedUri?.let {
                    try {
                        ContentUris.parseId(it)
                    } catch (_: Exception) {
                        -1L
                    }
                } ?: -1L
                val queryUri = if (specificId >= 0L) changedUri!! else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val sortOrder = if (specificId >= 0L) null else "${MediaStore.Images.Media.DATE_ADDED} DESC"

                context.contentResolver.query(
                    queryUri,
                    projection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                        val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                        val mimeTypeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                        val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val pendingCol = cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                            if (pendingCol != -1 && cursor.getInt(pendingCol) != 0) {
                                return
                            }
                        }

                        val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 1L
                        if (size <= 0L) return

                        val id = cursor.getLong(idCol)
                        synchronized(handledIds) {
                            if (id in handledIds) return
                        }
                        val name = cursor.getString(nameCol) ?: ""
                        val dateAdded = cursor.getLong(dateAddedCol)
                        val mimeType = cursor.getString(mimeTypeCol) ?: "image/png"

                        val pathOrRelative = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val relCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                            if (relCol != -1) cursor.getString(relCol) ?: "" else ""
                        } else {
                            val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                            if (dataCol != -1) cursor.getString(dataCol) ?: "" else ""
                        }

                        val now = System.currentTimeMillis() / 1000
                        if (kotlin.math.abs(now - dateAdded) <= 15) {
                            if (isScreenshotNameOrPath(name, pathOrRelative)) {
                                synchronized(handledIds) {
                                    if (!handledIds.add(id)) return
                                    if (handledIds.size > 32) {
                                        val iter = handledIds.iterator()
                                        if (iter.hasNext()) {
                                            iter.next()
                                            iter.remove()
                                        }
                                    }
                                }
                                val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                                onScreenshotDetected(contentUri, mimeType) {
                                    synchronized(handledIds) { handledIds.remove(id) }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to query MediaStore for screenshot")
            }
        }
    }

    private val limitPref = AppPrefs.getInstance().clipboard.clipboardHistoryLimit

    @Keep
    private val limitListener = ManagedPreference.OnChangeListener<Int> { _, _ ->
        launch { removeOutdated() }
    }

    @Volatile
    var lastEntry: ClipboardEntry? = null

    private fun updateLastEntry(entry: ClipboardEntry) {
        lastEntry = entry
        onUpdateListeners.forEach { it.onUpdate(entry) }
    }

    fun init(context: Context) {
        clbDb = Room
            .databaseBuilder(context, ClipboardDatabase::class.java, "clbdb")
            // allow wipe the database instead of crashing when downgrade
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
        clbDao = clbDb.clipboardDao()
        enabledListener.onChange(enabledPref.key, enabledPref.getValue())
        enabledPref.registerOnChangeListener(enabledListener)
        limitListener.onChange(limitPref.key, limitPref.getValue())
        limitPref.registerOnChangeListener(limitListener)
        launch { updateItemCount() }
    }

    suspend fun get(id: Int) = clbDao.get(id)

    suspend fun haveUnpinned() = clbDao.haveUnpinned()

    fun allEntries() = clbDao.allEntries()

    suspend fun pin(id: Int) = clbDao.updatePinStatus(id, true)

    suspend fun unpin(id: Int) = clbDao.updatePinStatus(id, false)

    suspend fun updateText(id: Int, text: String) {
        lastEntry?.let {
            if (id == it.id) updateLastEntry(it.copy(text = text))
        }
        clbDao.updateText(id, text)
    }

    suspend fun delete(id: Int) {
        clbDao.markAsDeleted(id)
        updateItemCount()
    }

    suspend fun deleteAll(skipPinned: Boolean = true): IntArray {
        val ids = if (skipPinned) {
            clbDao.findUnpinnedIds()
        } else {
            clbDao.findAllIds()
        }
        clbDao.markAsDeleted(*ids)
        updateItemCount()
        return ids
    }

    suspend fun undoDelete(vararg ids: Int) {
        clbDao.undoDelete(*ids)
        updateItemCount()
    }

    suspend fun realDelete() {
        try {
            val deleted = clbDao.getDeleted()
            deleted.forEach { entry ->
                entry.imagePath?.let { path ->
                    try { File(path).delete() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to clean up deleted image files")
        }
        clbDao.realDelete()
    }

    suspend fun nukeTable() {
        withContext(coroutineContext) {
            try {
                appContext.filesDir.resolve("clipboard_images").deleteRecursively()
            } catch (_: Exception) {}
            clbDb.clearAllTables()
            updateItemCount()
        }
    }

    private var lastClipTimestamp = -1L
    private var lastClipHash = 0

    override fun onPrimaryClipChanged() {
        val clip = clipboardManager.primaryClip ?: return
        /**
         * skip duplicate ClipData
         * https://developer.android.com/reference/android/content/ClipboardManager.OnPrimaryClipChangedListener#onPrimaryClipChanged()
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timestamp = clip.description.timestamp
            if (timestamp == lastClipTimestamp) return
            lastClipTimestamp = timestamp
        } else {
            val timestamp = System.currentTimeMillis()
            val hash = clip.hashCode()
            if (timestamp - lastClipTimestamp < 100L && hash == lastClipHash) return
            lastClipTimestamp = timestamp
            lastClipHash = hash
        }
        launch {
            mutex.withLock {
                val firstUri = if (clip.itemCount > 0) clip.getItemAt(0)?.uri else null
                if (firstUri != null) {
                    val existing = resolveLocalFileProviderImagePath(appContext, firstUri)?.let { localPath ->
                        clbDao.findByImagePath(localPath)
                            ?: lastEntry?.takeIf { it.imagePath == localPath }
                    } ?: (resolveMediaStoreImageUri(appContext, firstUri)
                        ?: findRecentScreenshotMediaUri(appContext, firstUri))?.let { mediaUri ->
                        val mediaUriStr = mediaUri.toString()
                        val rawUriStr = firstUri.toString()
                        clbDao.findBySourceUri(mediaUriStr)
                            ?: (if (rawUriStr != mediaUriStr) clbDao.findBySourceUri(rawUriStr) else null)
                            ?: lastEntry?.takeIf { it.sourceUri == mediaUriStr || it.sourceUri == rawUriStr }
                    }
                    if (existing != null) {
                        val ts = clip.timestamp()
                        if (existing.id > 0) {
                            clbDao.updateTime(existing.id, ts)
                        }
                        updateLastEntry(existing.copy(timestamp = ts))
                        return@withLock
                    }
                }
                val entry = ClipboardEntry.fromClipData(clip, transformer) ?: return@withLock
                if (entry.imagePath.isNullOrEmpty() && entry.text.isBlank()) return@withLock
                try {
                    clbDao.find(entry.text, entry.imagePath, entry.sensitive)?.let {
                        updateLastEntry(it.copy(timestamp = entry.timestamp))
                        clbDao.updateTime(it.id, entry.timestamp)
                        return@withLock
                    }
                    val insertedEntry = clbDb.withTransaction {
                        val rowId = clbDao.insert(entry)
                        removeOutdated()
                        // new entry can be deleted immediately if clipboard limit == 0
                        clbDao.get(rowId) ?: entry
                    }
                    updateLastEntry(insertedEntry)
                    updateItemCount()
                } catch (exception: Exception) {
                    Timber.w("Failed to update clipboard database: $exception")
                    updateLastEntry(entry)
                }
            }
        }
    }

    private suspend fun removeOutdated() {
        val limit = limitPref.getValue()
        val unpinned = clbDao.getAllUnpinned()
        if (unpinned.size > limit) {
            // the last one we will keep
            val last = unpinned
                .sortedBy { it.id }
                .getOrNull(unpinned.size - limit)
            // delete all unpinned before that, or delete all when limit <= 0
            clbDao.markUnpinnedAsDeletedEarlierThan(last?.timestamp ?: System.currentTimeMillis())
        }
    }

}
