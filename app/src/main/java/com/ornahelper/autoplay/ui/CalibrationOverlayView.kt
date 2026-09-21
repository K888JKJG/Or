package com.ornahelper.autoplay.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.ornahelper.autoplay.R
import com.ornahelper.autoplay.data.CalibratedButton
import com.ornahelper.autoplay.data.CalibratedRegion
import com.ornahelper.autoplay.data.CalibrationStep
import com.ornahelper.autoplay.data.ScreenRect
import com.ornahelper.autoplay.data.TapPoint
import com.ornahelper.autoplay.engine.BarReader
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen touch surface used during the calibration wizard. For a "rect" step the
 * user drags out a box (drawn live); on release it's finalized and, using the current
 * captured frame, its average color is sampled as the reference color for that region.
 * For a "point" step, the release position itself is the captured tap point.
 *
 * Coordinates are taken from [MotionEvent.getRawX]/[getRawY] since this view always
 * covers the whole display, so they line up 1:1 with both the MediaProjection capture
 * and the coordinates [OrnaAccessibilityService] taps.
 */
class CalibrationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onRegionCaptured(region: CalibratedRegion)
        fun onButtonCaptured(button: CalibratedButton)
    }

    var listener: Listener? = null
    var frameProvider: (() -> Bitmap?)? = null

    var currentStep: CalibrationStep = CalibrationStep.MONSTER_SPAWN_AREA
        set(value) {
            field = value
            dragRect = null
            invalidate()
        }

    private var startX = 0f
    private var startY = 0f
    private var isDragging = false
    private var dragRect: RectF? = null

    private val scrimPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.calibration_scrim)
    }
    private val rectStrokePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.calibration_rect)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val rectFillPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.calibration_rect)
        alpha = 60
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        dragRect?.let { r ->
            canvas.drawRect(r, rectFillPaint)
            canvas.drawRect(r, rectStrokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                isDragging = true
                dragRect = RectF(startX, startY, startX, startY)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging && currentStep.isRect) {
                    dragRect = RectF(
                        min(startX, event.rawX), min(startY, event.rawY),
                        max(startX, event.rawX), max(startY, event.rawY)
                    )
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                val endX = event.rawX
                val endY = event.rawY
                val distance = hypot((endX - startX).toDouble(), (endY - startY).toDouble())
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    finalizeGesture(startX, startY, endX, endY, distance)
                }
                dragRect = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun finalizeGesture(x0: Float, y0: Float, x1: Float, y1: Float, distance: Double) {
        val step = currentStep
        if (step.isRect) {
            if (distance < MIN_DRAG_DISTANCE_PX) return // too small, let the user retry
            val rect = ScreenRect(
                left = min(x0, x1).toInt(),
                top = min(y0, y1).toInt(),
                right = max(x0, x1).toInt(),
                bottom = max(y0, y1).toInt()
            ).normalized()
            val frame = frameProvider?.invoke()
            val color = frame?.let { BarReader.averageColor(it, rect) } ?: Color.WHITE
            listener?.onRegionCaptured(CalibratedRegion(rect, color))
        } else {
            val point = TapPoint(x1.toInt(), y1.toInt())
            val anchorRect = ScreenRect.around(point, ANCHOR_HALF_SIZE_PX)
            val frame = frameProvider?.invoke()
            val anchorColor = frame?.let { BarReader.averageColor(it, anchorRect) } ?: Color.WHITE
            listener?.onButtonCaptured(CalibratedButton(point, CalibratedRegion(anchorRect, anchorColor)))
        }
    }

    companion object {
        private const val MIN_DRAG_DISTANCE_PX = 20
        private const val ANCHOR_HALF_SIZE_PX = 30
    }
}
