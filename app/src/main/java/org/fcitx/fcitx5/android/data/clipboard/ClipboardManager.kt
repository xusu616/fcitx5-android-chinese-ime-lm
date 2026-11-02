/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.Keep
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import timber.log.Timber
import java.io.File
import java.util.UUID

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

    private fun registerScreenshotObserver() {
        if (screenshotObserver == null) {
            val observer = ScreenshotObserver(appContext, Handler(Looper.getMainLooper())) { uri, mimeType ->
                handleScreenshot(uri, mimeType)
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

    private fun handleScreenshot(uri: Uri, mimeType: String) {
        launch {
            val file = saveImageToLocal(appContext, uri, mimeType) ?: return@launch
            val entry = ClipboardEntry(
                text = "",
                imagePath = file.absolutePath,
                timestamp = System.currentTimeMillis(),
                type = mimeType
            )
            mutex.withLock {
                try {
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
        private val onScreenshotDetected: (Uri, String) -> Unit
    ) : ContentObserver(handler) {
        private var lastHandledId: Long = -1L

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            checkScreenshot()
        }

        private fun checkScreenshot() {
            try {
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.MIME_TYPE,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
                )
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
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

                        val id = cursor.getLong(idCol)
                        if (id == lastHandledId) return
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
                            val isScreenshot = name.contains("screenshot", ignoreCase = true) ||
                                    name.contains("截屏") ||
                                    pathOrRelative.contains("screenshot", ignoreCase = true) ||
                                    pathOrRelative.contains("截屏")
                            if (isScreenshot) {
                                lastHandledId = id
                                val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                                onScreenshotDetected(contentUri, mimeType)
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