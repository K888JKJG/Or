package com.ornahelper.autoplay.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Provides the only capability this app needs from the Accessibility API: synthesizing a
 * tap at arbitrary screen coordinates via [dispatchGesture]. It does not read window
 * content (canRetrieveWindowContent is false in the service config) — screen state is
 * read separately via MediaProjection pixel sampling in [ScreenCaptureController]/[OrnaBotService].
 */
class OrnaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used: this service only dispatches gestures, it doesn't inspect events.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    fun performTap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAP_DURATION_MS = 40L

        @Volatile
        private var instance: OrnaAccessibilityService? = null

        val isEnabled: Boolean get() = instance != null

        /** Returns true if a tap gesture was successfully dispatched (service must be enabled). */
        fun tap(x: Int, y: Int): Boolean = instance?.performTap(x, y) ?: false
    }
}
