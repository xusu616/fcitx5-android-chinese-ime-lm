/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.theme.Theme
import kotlin.math.min

abstract class ClipboardAdapter(
    private val theme: Theme,
    private val entryRadius: Float,
    private val maskSensitive: Boolean
) : PagingDataAdapter<ClipboardEntry, ClipboardAdapter.ViewHolder>(diffCallback) {

    companion object {
        private val diffCallback = object : DiffUtil.ItemCallback<ClipboardEntry>() {
            override fun areItemsTheSame(
                oldItem: ClipboardEntry,
                newItem: ClipboardEntry
            ): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(
                oldItem: ClipboardEntry,
                newItem: ClipboardEntry
            ): Boolean {
                return oldItem.text == newItem.text &&
                    oldItem.pinned == newItem.pinned &&
                    oldItem.sensitive == newItem.sensitive &&
                    oldItem.imagePath == newItem.imagePath &&
                    oldItem.type == newItem.type
            }
        }

        private val thumbnailCache = object : LruCache<String, Bitmap>(32) {}

        /**
         * excerpt text to show on ClipboardEntryUi, to reduce render time of very long text
         * @param str text to excerpt
         * @param mask mask text content with "•"
         * @param lines max output lines
         * @param chars max chars per output line
         */
        fun excerptText(
            str: String,
            mask: Boolean = false,
            lines: Int = 4,
            chars: Int = 128
        ): String = buildString {
            val length = str.length
            var lineBreak = -1
            for (i in 1..lines) {
                val start = lineBreak + 1   // skip previous '\n'
                val excerptEnd = min(start + chars, length)
                lineBreak = str.indexOf('\n', start)
                if (lineBreak < 0) {
                    // no line breaks remaining, substring to end of text
                    if (mask) {
                        append(ClipboardEntry.BULLET.repeat(excerptEnd - start))
                    } else {
                        append(str.substring(start, excerptEnd))
                    }
                    break
                } else {
                    val end = min(excerptEnd, lineBreak)
                    // append one line exactly
                    if (mask) {
                        append(ClipboardEntry.BULLET.repeat(end - start))
                    } else {
                        appendLine(str.substring(start, end))
                    }
                }
            }
        }
    }

    private val entryMenu = ClipboardEntryMenu()

    class ViewHolder(val entryUi: ClipboardEntryUi) : RecyclerView.ViewHolder(entryUi.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ClipboardEntryUi(parent.context, theme, entryRadius))

    private fun decodeSampledBitmap(path: String, reqWidth: Int = 300, reqHeight: Int = 300): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, options)
            var inSampleSize = 1
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            options.inSampleSize = inSampleSize
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565
            BitmapFactory.decodeFile(path, options)
        } catch (_: Exception) {
            null
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position) ?: return
        with(holder.entryUi) {
            setEntry(excerptText(entry.text, entry.sensitive && maskSensitive), entry.imagePath, entry.pinned)
            if (!entry.imagePath.isNullOrEmpty()) {
                val path = entry.imagePath
                val cached = thumbnailCache.get(path)
                if (cached != null && !cached.isRecycled) {
                    imageView.tag = path
                    imageView.setImageBitmap(cached)
                } else if (imageView.tag != path || imageView.drawable == null) {
                    imageView.tag = path
                    imageView.setImageDrawable(null)
                    CoroutineScope(Dispatchers.IO).launch {
                        val bmp = decodeSampledBitmap(path)?.also {
                            thumbnailCache.put(path, it)
                        }
                        withContext(Dispatchers.Main) {
                            if (imageView.tag == path) {
                                imageView.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            } else {
                imageView.tag = null
                imageView.setImageDrawable(null)
            }
            root.setOnClickListener {
                onPaste(entry)
            }
            root.setOnLongClickListener {
                entryMenu.show(root, entry) { action, selectedEntry ->
                    when (action) {
                        ClipboardEntryMenu.Action.Pin -> onPin(selectedEntry.id)
                        ClipboardEntryMenu.Action.Unpin -> onUnpin(selectedEntry.id)
                        ClipboardEntryMenu.Action.Copy -> onCopy(selectedEntry)
                        ClipboardEntryMenu.Action.Edit -> onEdit(selectedEntry.id)
                        ClipboardEntryMenu.Action.Share -> onShare(selectedEntry)
                        ClipboardEntryMenu.Action.Delete -> onDelete(selectedEntry.id)
                    }
                }
                true
            }
        }
    }

    fun getEntryAt(position: Int) = getItem(position)

    fun onDetached() {
        entryMenu.dismiss()
    }

    abstract fun onPaste(entry: ClipboardEntry)

    open fun onCopy(entry: ClipboardEntry) {}

    abstract fun onPin(id: Int)

    abstract fun onUnpin(id: Int)

    abstract fun onEdit(id: Int)

    abstract fun onShare(entry: ClipboardEntry)

    abstract fun onDelete(id: Int)

}
