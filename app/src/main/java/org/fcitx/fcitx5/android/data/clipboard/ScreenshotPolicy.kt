/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

internal object ScreenshotPolicy {
    fun shouldDeleteMediaOnPaste(
        autoDelete: Boolean,
        strictDelete: Boolean,
        committed: Boolean
    ): Boolean = strictDelete || (committed && autoDelete)

    fun isScreenshotNameOrPath(name: String, pathOrRelative: String): Boolean {
        return name.contains("screenshot", ignoreCase = true) ||
            name.contains("screen_shot", ignoreCase = true) ||
            name.contains("screencapture", ignoreCase = true) ||
            name.contains("截屏") ||
            name.contains("截图") ||
            pathOrRelative.contains("screenshot", ignoreCase = true) ||
            pathOrRelative.contains("screen_shot", ignoreCase = true) ||
            pathOrRelative.contains("screencapture", ignoreCase = true) ||
            pathOrRelative.contains("截屏") ||
            pathOrRelative.contains("截图")
    }

    fun isMediaStoreAuthority(authority: String?): Boolean {
        if (authority.isNullOrEmpty()) return false
        if (authority == "media") return true
        val at = authority.indexOf('@')
        if (at <= 0) return false
        return authority.substring(at + 1) == "media" &&
            authority.substring(0, at).all { it in '0'..'9' }
    }

    fun parseMediaDocumentImageId(documentId: String?): Long? {
        if (documentId.isNullOrEmpty() || !documentId.startsWith("image:")) return null
        return documentId.substringAfter("image:").toLongOrNull()?.takeIf { it >= 0L }
    }
}
