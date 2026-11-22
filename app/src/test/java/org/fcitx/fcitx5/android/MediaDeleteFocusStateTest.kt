/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.clipboard.MediaDeleteFocusState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDeleteFocusStateTest {
    private val editor = MediaDeleteFocusState.Editor(Any(), "app.original", 42, "message", 1, 6)

    @Test
    fun completedRequestPreservesOnlyOneReturnToOriginalEditor() {
        val state = MediaDeleteFocusState()
        state.begin("delete", editor)
        state.finish("delete")
        assertTrue(state.consumeReturn(editor, restarting = false))
        assertFalse(state.consumeReturn(editor, restarting = false))
    }

    @Test
    fun activeRequestPreservesEditorUntilExplicitCompletion() {
        val state = MediaDeleteFocusState()
        state.begin("delete", editor)
        assertTrue(state.consumeReturn(editor, restarting = false))
        assertTrue(state.consumeReturn(editor, restarting = true))
        state.finish("delete")
        assertTrue(state.consumeReturn(editor, restarting = false))
        assertFalse(state.consumeReturn(editor, restarting = false))
    }

    @Test
    fun anotherFieldIsNotSuppressedAndInvalidatesOldReturn() {
        val state = MediaDeleteFocusState()
        state.begin("delete", editor)
        state.finish("delete")
        assertFalse(state.consumeReturn(editor.copy(fieldId = 43), restarting = false))
        assertFalse(state.consumeReturn(editor, restarting = false))
    }

    @Test
    fun anotherClientWithSameEditorMetadataIsNotSuppressed() {
        val state = MediaDeleteFocusState()
        state.begin("delete", editor)
        assertFalse(state.consumeReturn(editor.copy(clientToken = Any()), restarting = true))
    }

    @Test
    fun changedInputTypeIncludingPasswordFieldIsNotSuppressed() {
        val state = MediaDeleteFocusState()
        state.begin("delete", editor)
        assertFalse(state.consumeReturn(editor.copy(inputType = 129), restarting = false))
    }

    @Test
    fun editorWithoutFieldIdentityRequiresExplicitRestart() {
        val anonymous = editor.copy(fieldId = 0, fieldName = null)
        val state = MediaDeleteFocusState()
        state.begin("delete", anonymous)
        state.finish("delete")
        assertFalse(state.consumeReturn(anonymous, restarting = false))
        state.begin("second", anonymous)
        state.finish("second")
        assertTrue(state.consumeReturn(anonymous, restarting = true))
        assertFalse(state.consumeReturn(anonymous, restarting = true))
    }

    @Test
    fun cancelledOrDroppedRequestCannotAffectLaterInput() {
        val state = MediaDeleteFocusState()
        state.begin("delete", editor)
        state.cancel("delete")
        state.finish("delete")
        assertFalse(state.consumeReturn(editor, restarting = false))
    }

    @Test
    fun editorSwitchWhileDialogIsOpenDiscardsItsLaterResult() {
        val state = MediaDeleteFocusState()
        state.begin("delete", editor)
        state.onEditorStarted(editor.copy(packageName = "another.app"), restarting = false)
        state.finish("delete")
        assertFalse(state.consumeReturn(editor, restarting = false))
    }

    @Test
    fun serviceDestructionClearsPendingReturn() {
        val state = MediaDeleteFocusState()
        state.begin("delete", editor)
        state.finish("delete")
        state.clear()
        assertFalse(state.consumeReturn(editor, restarting = false))
    }
}
