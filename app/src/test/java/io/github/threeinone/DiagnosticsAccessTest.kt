package io.github.threeinone

import io.github.threeinone.config.allowedCaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run-state receipt may only be written by the status-bar host.
 *
 * ColorOS 17 gives SystemUI its own shared UID (10248) while older builds run it as the
 * platform's 1000, so the package name has to be part of the check — a UID-only whitelist
 * silently rejects the report and the settings screen keeps showing a stale receipt.
 */
class DiagnosticsAccessTest {

    @Test fun theSystemUiHostMayPublishTheReceipt() {
        // ColorOS 17: SystemUI's own shared UID.
        assertTrue(allowedCaller(10248, listOf("com.android.systemui"), 10370))
        // Older builds: the platform UID.
        assertTrue(allowedCaller(1000, listOf("com.android.systemui", "android"), 10370))
    }

    @Test fun rootShellAndTheAppItselfMayPublishTheReceipt() {
        assertTrue(allowedCaller(0, emptyList(), 10370))
        assertTrue(allowedCaller(2000, emptyList(), 10370))
        assertTrue(allowedCaller(10370, listOf("io.github.threeinone"), 10370))
    }

    @Test fun ordinaryAppsAreRejected() {
        assertFalse(allowedCaller(10222, listOf("com.example.other"), 10370))
        assertFalse(allowedCaller(10123, listOf("com.example.other"), 10370))
    }
}
