/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

internal class ClipboardSuggestionState {
    var isAvailable = false
        private set

    var showToolbar = false
        private set

    fun update(hasContent: Boolean) {
        isAvailable = hasContent
        showToolbar = false
    }

    fun toggleToolbar(): Boolean {
        if (!isAvailable) return false
        showToolbar = !showToolbar
        return true
    }

    fun useEmojiButton(enabled: Boolean): Boolean = enabled && !isAvailable
}
