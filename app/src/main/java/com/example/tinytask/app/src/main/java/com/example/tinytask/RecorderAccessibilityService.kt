package com.example.tinytask

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class RecorderAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RecorderAccessibilityService? = null
        private const val TAG = "RecorderService"
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used - we don't need semantic events, only gesture dispatch.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    /** Replay a single stroke immediately (used to forward a touch captured by the overlay). */
    fun dispatchImmediate(points: List<TouchPoint>) {
        if (points.isEmpty()) return
        val gesture = buildGestureForStroke(RecordedStroke(0, points)) ?: return
        dispatchGesture(gesture, null, null)
    }

    /** Play back a full recorded macro, respecting relative timing between strokes. */
    fun playMacro(strokes: List<RecordedStroke>, onDone: () -> Unit) {
        if (strokes.isEmpty()) {
            onDone()
            return
        }
        var remaining = strokes.size
        for (stroke in strokes) {
            handler.postDelayed({
                val gesture = buildGestureForStroke(stroke)
                if (gesture != null) {
                    dispatchGesture(gesture, object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            remaining--
                            if (remaining <= 0) onDone()
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            remaining--
                            if (remaining <= 0) onDone()
                        }
                    }, handler)
                } else {
                    remaining--
                    if (remaining <= 0) onDone()
                }
            }, stroke.startOffset)
        }
    }

    private fun buildGestureForStroke(stroke: RecordedStroke): GestureDescription? {
        val points = stroke.points
        if (points.isEmpty()) return null

        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
        }

        val firstT = points.first().t
        val lastT = points.last().t
        var duration = lastT - firstT
        if (duration <= 0) duration = 1 // dispatchGesture requires duration > 0

        val strokeDescription = GestureDescription.StrokeDescription(path, 0, duration)
        val builder = GestureDescription.Builder()
        builder.addStroke(strokeDescription)
        return builder.build()
    }
}
