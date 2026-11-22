/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

internal class MediaDeleteFocusState {
    data class Editor(
        val clientToken: Any,
        val packageName: String,
        val fieldId: Int,
        val fieldName: String?,
        val inputType: Int,
        val imeOptions: Int,
    ) {
        val hasFieldIdentity: Boolean get() = fieldId != 0 || !fieldName.isNullOrEmpty()
    }

    private data class Request(val editor: Editor, var finished: Boolean = false)
    private val requests = mutableMapOf<String, Request>()

    @Synchronized
    fun begin(requestId: String, editor: Editor) {
        requests[requestId] = Request(editor)
    }

    @Synchronized
    fun finish(requestId: String?) {
        requests[requestId]?.finished = true
    }

    @Synchronized
    fun cancel(requestId: String?) {
        requests.remove(requestId)
    }

    @Synchronized
    fun onEditorStarted(editor: Editor?, restarting: Boolean) {
        requests.entries.removeAll { (_, request) ->
            editor == null || request.editor != editor || (!editor.hasFieldIdentity && !restarting)
        }
    }

    @Synchronized
    fun consumeReturn(editor: Editor?, restarting: Boolean): Boolean {
        onEditorStarted(editor, restarting)
        if (requests.isEmpty()) return false
        requests.entries.removeAll { it.value.finished }
        return true
    }

    @Synchronized
    fun clear() {
        requests.clear()
    }
}
