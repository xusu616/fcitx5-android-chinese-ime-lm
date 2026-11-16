/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment

class ClipboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().clipboard) {

    private var mediaPermissionPref: Preference? = null

    private val requestReadMediaLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            updateMediaPermissionSummary()
        }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = screen.context
        mediaPermissionPref = Preference(ctx).apply {
            setTitle(R.string.clipboard_media_permission)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                if (!ClipboardManager.hasMediaReadPermission(ctx)) {
                    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Manifest.permission.READ_MEDIA_IMAGES
                    } else {
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    }
                    requestReadMediaLauncher.launch(perm)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ClipboardManager.hasMediaManagePermission(ctx)) {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).apply {
                                data = Uri.parse("package:${ctx.packageName}")
                            }
                        )
                    } catch (_: Exception) {
                    }
                } else {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${ctx.packageName}")
                            }
                        )
                    } catch (_: Exception) {
                    }
                }
                true
            }
        }.also {
            screen.addPreference(it)
        }
        updateMediaPermissionSummary()
    }

    override fun onResume() {
        super.onResume()
        updateMediaPermissionSummary()
    }

    private fun updateMediaPermissionSummary() {
        val pref = mediaPermissionPref ?: return
        val ctx = context ?: return
        val summaryRes = when {
            !ClipboardManager.hasMediaReadPermission(ctx) -> R.string.clipboard_media_permission_denied
            ClipboardManager.hasMediaManagePermission(ctx) -> R.string.clipboard_media_permission_granted
            else -> R.string.clipboard_media_permission_read_only
        }
        pref.setSummary(summaryRes)
    }
}
