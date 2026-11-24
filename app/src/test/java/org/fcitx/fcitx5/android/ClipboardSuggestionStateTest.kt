/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.bar.ClipboardSuggestionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSuggestionStateTest {
    @Test
    fun noSuggestionUsesConfiguredEmojiButton() {
        val state = ClipboardSuggestionState()
        assertTrue(state.useEmojiButton(enabled = true))
        assertFalse(state.useEmojiButton(enabled = false))
        assertFalse(state.toggleToolbar())
        assertFalse(state.showToolbar)
    }

    @Test
    fun activeSuggestionUsesArrowAndTogglesBothWays() {
        val state = ClipboardSuggestionState()
        state.update(hasContent = true)
        assertTrue(state.isAvailable)
        assertFalse(state.showToolbar)
        assertFalse(state.useEmojiButton(enabled = true))

        assertTrue(state.toggleToolbar())
        assertTrue(state.showToolbar)
        assertTrue(state.isAvailable)
        assertFalse(state.useEmojiButton(enabled = true))

        assertTrue(state.toggleToolbar())
        assertFalse(state.showToolbar)
        assertTrue(state.isAvailable)
        assertFalse(state.useEmojiButton(enabled = true))
    }

    @Test
    fun newContentReopensSuggestionAfterViewingToolbar() {
        val state = ClipboardSuggestionState()
        state.update(hasContent = true)
        state.toggleToolbar()
        state.update(hasContent = true)
        assertTrue(state.isAvailable)
        assertFalse(state.showToolbar)
        assertFalse(state.useEmojiButton(enabled = true))
    }

    @Test
    fun consumedExpiredOrDisabledSuggestionRestoresEmojiButton() {
        val state = ClipboardSuggestionState()
        state.update(hasContent = true)
        state.toggleToolbar()
        state.update(hasContent = false)
        assertFalse(state.isAvailable)
        assertFalse(state.showToolbar)
        assertTrue(state.useEmojiButton(enabled = true))
        assertFalse(state.toggleToolbar())
    }

    @Test
    fun disabledEmojiPreferenceKeepsArrowAfterSuggestionEnds() {
        val state = ClipboardSuggestionState()
        state.update(hasContent = true)
        assertFalse(state.useEmojiButton(enabled = false))
        state.update(hasContent = false)
        assertFalse(state.useEmojiButton(enabled = false))
    }
}
