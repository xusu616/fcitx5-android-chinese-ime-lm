/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.core.FcitxAPI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class FcitxDaemonApiTest {

    @Test
    fun daemonProxyImplementsEveryApiMethod() {
        // Inspect the actual compiled proxy without initializing the daemon or JNI.
        val daemonClass = Class.forName(
            "org.fcitx.fcitx5.android.daemon.FcitxDaemon",
            false,
            javaClass.classLoader
        )
        val proxyClass = daemonClass.getDeclaredMethod("getFcitxImpl").returnType
        assertTrue(FcitxAPI::class.java.isAssignableFrom(proxyClass))

        val apiMethods = FcitxAPI::class.java.methods.filter {
            Modifier.isAbstract(it.modifiers)
        }
        for (apiMethod in apiMethods) {
            val implementation = proxyClass.getMethod(apiMethod.name, *apiMethod.parameterTypes)
            val message = "Daemon proxy must implement $apiMethod"
            assertFalse(message, Modifier.isAbstract(implementation.modifiers))
            assertFalse(message, implementation.declaringClass.isInterface)
        }
    }
}
