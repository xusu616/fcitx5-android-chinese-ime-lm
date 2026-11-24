/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.os.Build
import android.view.View
import android.widget.PopupMenu
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.item
import splitties.resources.styledColor

internal class ClipboardEntryMenu {

    enum class Action { Pin, Unpin, Copy, Edit, Share, Delete }

    private var popupMenu: PopupMenu? = null
    private var popupAnchor: View? = null

    private val anchorAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {}

        override fun onViewDetachedFromWindow(view: View) {
            if (view === popupAnchor) dismiss()
        }
    }

    fun show(anchor: View, entry: ClipboardEntry, onAction: (Action, ClipboardEntry) -> Unit) {
        dismiss()
        val context = anchor.context
        val popup = PopupMenu(context, anchor)
        val menu = popup.menu
        val iconTint = context.styledColor(android.R.attr.colorControlNormal)
        if (entry.pinned) {
            menu.item(R.string.unpin, R.drawable.ic_outline_push_pin_24, iconTint) {
                onAction(Action.Unpin, entry)
            }
        } else {
            menu.item(R.string.pin, R.drawable.ic_baseline_push_pin_24, iconTint) {
                onAction(Action.Pin, entry)
            }
        }
        menu.item(R.string.copy, R.drawable.ic_baseline_content_copy_24, iconTint) {
            onAction(Action.Copy, entry)
        }
        if (entry.imagePath.isNullOrEmpty()) {
            menu.item(R.string.edit, R.drawable.ic_baseline_edit_24, iconTint) {
                onAction(Action.Edit, entry)
            }
        }
        menu.item(R.string.share, R.drawable.ic_baseline_share_24, iconTint) {
            onAction(Action.Share, entry)
        }
        menu.item(R.string.delete, R.drawable.ic_baseline_delete_24, iconTint) {
            onAction(Action.Delete, entry)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !DeviceUtil.isSamsungOneUI && !DeviceUtil.isFlyme) {
            popup.setForceShowIcon(true)
        }
        popup.setOnDismissListener {
            if (it === popupMenu) clearAnchor()
        }
        popupMenu = popup
        popupAnchor = anchor
        anchor.addOnAttachStateChangeListener(anchorAttachListener)
        popup.show()
    }

    fun dismiss() {
        val popup = popupMenu
        clearAnchor()
        popup?.dismiss()
    }

    private fun clearAnchor() {
        popupAnchor?.removeOnAttachStateChangeListener(anchorAttachListener)
        popupAnchor = null
        popupMenu = null
    }
}
