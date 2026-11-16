/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.clipboard.ScreenshotPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotPolicyTest {

    @Test
    fun strictDeleteTriggersRegardlessOfCommitResultOrAutoDelete() {
        assertTrue(ScreenshotPolicy.shouldDeleteMediaOnPaste(autoDelete = false, strictDelete = true, committed = false))
        assertTrue(ScreenshotPolicy.shouldDeleteMediaOnPaste(autoDelete = false, strictDelete = true, committed = true))
        assertTrue(ScreenshotPolicy.shouldDeleteMediaOnPaste(autoDelete = true, strictDelete = true, committed = false))
        assertTrue(ScreenshotPolicy.shouldDeleteMediaOnPaste(autoDelete = true, strictDelete = true, committed = true))
    }

    @Test
    fun nonStrictFollowsAutoDeleteAndCommitResult() {
        assertFalse(ScreenshotPolicy.shouldDeleteMediaOnPaste(autoDelete = false, strictDelete = false, committed = false))
        assertFalse(ScreenshotPolicy.shouldDeleteMediaOnPaste(autoDelete = false, strictDelete = false, committed = true))
        assertFalse(ScreenshotPolicy.shouldDeleteMediaOnPaste(autoDelete = true, strictDelete = false, committed = false))
        assertTrue(ScreenshotPolicy.shouldDeleteMediaOnPaste(autoDelete = true, strictDelete = false, committed = true))
    }

    @Test
    fun screenshotNameAndPathMatching() {
        assertTrue(ScreenshotPolicy.isScreenshotNameOrPath("Screenshot_20261116.png", "Pictures/"))
        assertTrue(ScreenshotPolicy.isScreenshotNameOrPath("IMG_001.png", "Pictures/Screenshots/"))
        assertTrue(ScreenshotPolicy.isScreenshotNameOrPath("截屏_2026.png", "DCIM/"))
        assertTrue(ScreenshotPolicy.isScreenshotNameOrPath("IMG_002.jpg", "DCIM/截屏/"))
        assertTrue(ScreenshotPolicy.isScreenshotNameOrPath("screencapture_01.png", "Pictures/"))
        assertFalse(ScreenshotPolicy.isScreenshotNameOrPath("IMG_20261116.jpg", "DCIM/Camera/"))
    }
}
