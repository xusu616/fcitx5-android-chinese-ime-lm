/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.utils.parcelable
import timber.log.Timber

/**
 * Transparent trampoline [Activity] used to invoke [MediaStore.createDeleteRequest] and
 * [android.app.RecoverableSecurityException] [IntentSender]s via [startIntentSenderForResult],
 * because Android's MediaProvider PermissionActivity requires a non-null [getCallingPackage].
 */
class MediaDeleteRequestActivity : Activity() {

    private var targetUri: Uri? = null
    private var entryId: Int = -1
    private var strict: Boolean = false
    private var requestId: String? = null
    private var launched: Boolean = false
    private var resultDelivered: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableTransition(isOpen = true)

        targetUri = intent.parcelable(EXTRA_URI)
        entryId = intent.getIntExtra(EXTRA_ENTRY_ID, -1)
        strict = intent.getBooleanExtra(EXTRA_STRICT, false)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val intentSender = intent.parcelable<IntentSender>(EXTRA_INTENT_SENDER)

        val uri = targetUri
        if (uri == null || intentSender == null) {
            ClipboardManager.acknowledgeDeleteRequest(requestId)
            resultDelivered = true
            finish()
            disableTransition(isOpen = false)
            return
        }

        ClipboardManager.acknowledgeDeleteRequest(requestId)
        launched = savedInstanceState?.getBoolean(STATE_LAUNCHED) == true
        resultDelivered = savedInstanceState?.getBoolean(STATE_RESULT_DELIVERED) == true

        if (!launched) {
            launched = true
            try {
                val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                        )
                        .toBundle()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    @Suppress("DEPRECATION")
                    ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                        .toBundle()
                } else {
                    null
                }
                startIntentSenderForResult(intentSender, REQUEST_DELETE, null, 0, 0, 0, options)
            } catch (e: Exception) {
                Timber.w(e, "Failed to launch media delete request for $uri")
                deliverResult(uri, approved = false)
                finish()
                disableTransition(isOpen = false)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DELETE) {
            targetUri?.let { deliverResult(it, approved = resultCode == RESULT_OK) }
            finish()
            disableTransition(isOpen = false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LAUNCHED, launched)
        outState.putBoolean(STATE_RESULT_DELIVERED, resultDelivered)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !resultDelivered) {
            targetUri?.let { deliverResult(it, approved = false) }
        }
    }

    private fun deliverResult(uri: Uri, approved: Boolean) {
        if (resultDelivered) return
        resultDelivered = true
        ClipboardManager.onDeleteRequestResult(applicationContext, uri, entryId, strict, approved)
    }

    private fun disableTransition(isOpen: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (isOpen) OVERRIDE_TRANSITION_OPEN else OVERRIDE_TRANSITION_CLOSE
            overrideActivityTransition(type, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val REQUEST_DELETE = 1
        private const val EXTRA_INTENT_SENDER = "intent_sender"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_ENTRY_ID = "entry_id"
        private const val EXTRA_STRICT = "strict"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val STATE_LAUNCHED = "launched"
        private const val STATE_RESULT_DELIVERED = "result_delivered"

        fun launch(
            context: Context,
            intentSender: IntentSender,
            uri: Uri,
            entryId: Int,
            strict: Boolean,
            requestId: String
        ) {
            val intent = Intent(context, MediaDeleteRequestActivity::class.java).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_INTENT_SENDER, intentSender)
                putExtra(EXTRA_URI, uri)
                putExtra(EXTRA_ENTRY_ID, entryId)
                putExtra(EXTRA_STRICT, strict)
                putExtra(EXTRA_REQUEST_ID, requestId)
            }
            val options = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
            context.startActivity(intent, options)
        }
    }
}
