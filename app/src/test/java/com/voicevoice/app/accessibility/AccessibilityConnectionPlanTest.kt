package com.voicevoice.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityConnectionPlanTest {
    @Test
    fun firstConnectionRegistersReceiverAndInitializesOverlay() {
        assertEquals(
            AccessibilityConnectionPlan(registerReceiver = true, initializeOverlay = true),
            accessibilityConnectionPlan(receiverRegistered = false, overlayAttached = false),
        )
    }

    @Test
    fun repeatedConnectionPreservesActiveOverlaySession() {
        assertEquals(
            AccessibilityConnectionPlan(registerReceiver = false, initializeOverlay = false),
            accessibilityConnectionPlan(receiverRegistered = true, overlayAttached = true),
        )
    }

    @Test
    fun missingPiecesAreRecoveredIndependently() {
        assertEquals(
            AccessibilityConnectionPlan(registerReceiver = true, initializeOverlay = false),
            accessibilityConnectionPlan(receiverRegistered = false, overlayAttached = true),
        )
        assertEquals(
            AccessibilityConnectionPlan(registerReceiver = false, initializeOverlay = true),
            accessibilityConnectionPlan(receiverRegistered = true, overlayAttached = false),
        )
    }
}
