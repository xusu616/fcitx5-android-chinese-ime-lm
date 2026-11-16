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
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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

    internal fun shouldDeleteMediaOnPaste(
        autoDelete: Boolean,
        strictDelete: Boolean,
        committed: Boolean
    ): Boolean = ScreenshotPolicy.shouldDeleteMediaOnPaste(autoDelete, strictDelete, committed)

    internal fun isScreenshotNameOrPath(name: String, pathOrRelative: String): Boolean =
        ScreenshotPolicy.isScreenshotNameOrPath(name, pathOrRelative)

    internal fun isMediaStoreUri(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        if (uri.authority != MediaStore.AUTHORITY) return false
        val path = uri.path ?: return false
        return path.contains("/images/")
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
        if (!isMediaStoreUri(uri)) return false
        val columns = mutableListOf(MediaStore.Images.Media.DISPLAY_NAME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            columns.add(MediaStore.Images.Media.RELATIVE_PATH)
        } else {
            columns.add(MediaStore.Images.Media.DATA)
        }
        try {
            context.contentResolver.query(uri, columns.toTypedArray(), null, null, null)?.use { cursor ->
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
            Timber.w(e, "Failed to query screenshot metadata for $uri")
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

    private suspend fun clearEntrySourceUri(entryId: Int) {
        if (entryId > 0) {
            clbDao.clearSourceUri(entryId)
        }
        withContext(Dispatchers.Main.immediate) {
            lastEntry?.let {
                if (it.id == entryId) {
                    lastEntry = it.copy(sourceUri = null)
                }
            }
        }
    }

    private suspend fun deleteSourceMediaUri(
        context: Context,
        entryId: Int,
        fallbackSourceUri: String,
        strict: Boolean
    ) {
        val effectiveUriStr = if (entryId > 0) {
            val dbEntry = clbDao.get(entryId) ?: return
            dbEntry.sourceUri ?: return
        } else {
            fallbackSourceUri
        }
        deleteMediaUri(context, Uri.parse(effectiveUriStr), entryId, strict)
    }

    private suspend fun deleteMediaUri(
        context: Context,
        uri: Uri,
        entryId: Int,
        strict: Boolean
    ) {
        val uriStr = uri.toString()
        if (!deletingUris.add(uriStr)) return
        try {
            if (!mediaUriExists(context, uri)) {
                clearEntrySourceUri(entryId)
                return
            }
            try {
                val deleted = context.contentResolver.delete(uri, null, null)
                if (deleted > 0 || !mediaUriExists(context, uri)) {
                    clearEntrySourceUri(entryId)
                }
            } catch (e: SecurityException) {
                if (!mediaUriExists(context, uri)) {
                    clearEntrySourceUri(entryId)
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    withContext(Dispatchers.Main) {
                        try {
                            val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                            val fillInIntent = Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startIntentSender(
                                pendingIntent.intentSender,
                                fillInIntent,
                                Intent.FLAG_ACTIVITY_NEW_TASK,
                                Intent.FLAG_ACTIVITY_NEW_TASK,
                                0
                            )
                            if (!strict) {
                                launch { clearEntrySourceUri(entryId) }
                            }
                        } catch (ex: Exception) {
                            Timber.w(ex, "Failed to launch MediaStore delete request for $uri")
                        }
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                    withContext(Dispatchers.Main) {
                        try {
                            val fillInIntent = Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startIntentSender(
                                e.userAction.actionIntent.intentSender,
                                fillInIntent,
                                Intent.FLAG_ACTIVITY_NEW_TASK,
                                Intent.FLAG_ACTIVITY_NEW_TASK,
                                0
                            )
                            if (!strict) {
                                launch { clearEntrySourceUri(entryId) }
                            }
                        } catch (ex: Exception) {
                            Timber.w(ex, "Failed to launch RecoverableSecurityException delete request for $uri")
                        }
                    }
                } else {
                    Timber.w(e, "No permission to delete screenshot $uri")
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to delete pasted screenshot $uri")
            }
        } finally {
            deletingUris.remove(uriStr)
        }
    }

    fun onImagePasted(context: Context, entry: ClipboardEntry, committed: Boolean = true) {
        val clipboardPrefs = AppPrefs.getInstance().clipboard
        val strict = clipboardPrefs.strictDeletePastedScreenshot.getValue()
        val autoDelete = clipboardPrefs.autoDeletePastedScreenshot.getValue()
        if (!shouldDeleteMediaOnPaste(autoDelete, strict, committed)) return
        val sourceUriStr = entry.sourceUri ?: return
        launch(Dispatchers.IO) {
            deleteSourceMediaUri(context, entry.id, sourceUriStr, strict)
        }
    }

    fun onSystemPasteAttempted(context: Context) {
        if (!AppPrefs.getInstance().clipboard.strictDeletePastedScreenshot.getValue()) return
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
                    deleteSourceMediaUri(context, entry.id, sourceUriStr, strict = true)
                } else if (isMediaStoreUri(uri)) {
                    val uriStr = uri.toString()
                    val entry = clbDao.findBySourceUri(uriStr)
                        ?: lastEntry?.takeIf { it.sourceUri == uriStr }
                    if (entry != null) {
                        delay(400L)
                        deleteSourceMediaUri(context, entry.id, uriStr, strict = true)
                    } else if (isScreenshotUri(context, uri)) {
                        delay(400L)
                        deleteMediaUri(context, uri, entryId = -1, strict = true)
                    }
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
                    val existing = resolveLocalFileProviderImagePath(appContext, firstUri)?.let {
                        clbDao.findByImagePath(it)
                    } ?: if (isMediaStoreUri(firstUri)) {
                        clbDao.findBySourceUri(firstUri.toString())
                    } else {
                        null
                    }
                    if (existing != null) {
                        val ts = clip.timestamp()
                        clbDao.updateTime(existing.id, ts)
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