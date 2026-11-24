/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.FileProvider
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.BaseTransientBottomBar.BaseCallback
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.SnackbarContentLayout
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp
import splitties.views.dsl.core.withTheme
import timber.log.Timber
import java.io.File

internal class ClipboardEntryActions(
    private val context: Context,
    private val service: FcitxInputMethodService,
    private val theme: Theme,
    private val root: View,
    private val onDeleted: (Int) -> Unit = {}
) {
    private val snackbarContext by lazy {
        context.withTheme(R.style.InputViewSnackbarTheme)
    }
    private var snackbarInstance: Snackbar? = null
    private val pendingDeleteIds = arrayListOf<Int>()

    fun perform(action: ClipboardEntryMenu.Action, entry: ClipboardEntry) {
        when (action) {
            ClipboardEntryMenu.Action.Pin -> pin(entry.id)
            ClipboardEntryMenu.Action.Unpin -> unpin(entry.id)
            ClipboardEntryMenu.Action.Copy -> copy(entry)
            ClipboardEntryMenu.Action.Edit -> edit(entry.id)
            ClipboardEntryMenu.Action.Share -> share(entry)
            ClipboardEntryMenu.Action.Delete -> delete(entry.id)
        }
    }

    fun pin(id: Int) {
        service.lifecycleScope.launch { ClipboardManager.pin(id) }
    }

    fun unpin(id: Int) {
        service.lifecycleScope.launch { ClipboardManager.unpin(id) }
    }

    fun edit(id: Int) {
        AppUtil.launchClipboardEdit(context, id)
    }

    fun copy(entry: ClipboardEntry) {
        if (!entry.imagePath.isNullOrEmpty()) {
            try {
                val file = File(entry.imagePath)
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.clipboard.fileprovider", file
                )
                val clipData = ClipData.newUri(context.contentResolver, "Image", uri)
                context.clipboardManager.setPrimaryClip(clipData)
                context.toast(R.string.copied_to_clipboard)
            } catch (e: Exception) {
                Timber.w(e, "Failed to copy image to clipboard")
            }
        } else {
            val clipData = ClipData.newPlainText(null, entry.text)
            context.clipboardManager.setPrimaryClip(clipData)
            context.toast(R.string.copied_to_clipboard)
        }
    }

    fun share(entry: ClipboardEntry) {
        val target = Intent(Intent.ACTION_SEND).apply {
            if (!entry.imagePath.isNullOrEmpty()) {
                val file = File(entry.imagePath)
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.clipboard.fileprovider", file
                )
                type = entry.type.ifEmpty { "image/png" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, entry.text)
            }
        }
        val chooser = Intent.createChooser(target, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        service.startActivity(chooser)
    }

    fun delete(id: Int) {
        service.lifecycleScope.launch {
            ClipboardManager.delete(id)
            onDeleted(id)
            showUndoSnackbar(id)
        }
    }

    @SuppressLint("RestrictedApi")
    fun showUndoSnackbar(vararg ids: Int) {
        ids.forEach { pendingDeleteIds.add(it) }
        val text = context.resources.getString(R.string.num_items_deleted, pendingDeleteIds.size)
        snackbarInstance = Snackbar.make(snackbarContext, root, text, Snackbar.LENGTH_LONG)
            .setBackgroundTint(theme.popupBackgroundColor)
            .setTextColor(theme.popupTextColor)
            .setActionTextColor(theme.genericActiveBackgroundColor)
            .setAction(R.string.undo) {
                service.lifecycleScope.launch {
                    ClipboardManager.undoDelete(*pendingDeleteIds.toIntArray())
                    pendingDeleteIds.clear()
                }
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                    if (snackbarInstance === transientBottomBar) {
                        snackbarInstance = null
                    }
                    when (event) {
                        BaseCallback.DISMISS_EVENT_SWIPE,
                        BaseCallback.DISMISS_EVENT_MANUAL,
                        BaseCallback.DISMISS_EVENT_TIMEOUT -> {
                            service.lifecycleScope.launch {
                                ClipboardManager.realDelete()
                                pendingDeleteIds.clear()
                            }
                        }
                        BaseCallback.DISMISS_EVENT_ACTION,
                        BaseCallback.DISMISS_EVENT_CONSECUTIVE -> {}
                    }
                }
            }).apply {
                val horizontalMargin = snackbarContext.dp(24)
                val verticalMargin = snackbarContext.dp(16)
                view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    leftMargin = horizontalMargin
                    rightMargin = horizontalMargin
                    bottomMargin = verticalMargin
                }
                ((view as FrameLayout).getChildAt(0) as SnackbarContentLayout).apply {
                    messageView.letterSpacing = 0f
                    actionView.letterSpacing = 0f
                }
                show()
            }
    }

    fun dismiss() {
        snackbarInstance?.dismiss()
    }
}
