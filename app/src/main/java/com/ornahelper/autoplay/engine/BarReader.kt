package com.ornahelper.autoplay.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.ornahelper.autoplay.data.CalibratedRegion
import com.ornahelper.autoplay.data.ScreenRect
import kotlin.math.sqrt

/**
 * Reads calibrated screen regions from a captured frame using simple color matching.
 * No OCR / template matching — just color-distance comparisons against a reference
 * color sampled during calibration. This keeps the bot lightweight and fast enough
 * to run every tick, at the cost of needing a distinct color for filled vs. empty bars.
 */
object BarReader {

    /**
     * Percentage (0-100) of the bar's midline whose pixels match [region]'s reference
     * (filled) color, scanning the whole width. Returns -1 if the region is invalid
     * or out of bounds.
     */
    fun readBarPercent(bitmap: Bitmap, region: CalibratedRegion, tolerance: Int): Int {
        val r = region.rect
        if (r.width <= 1 || r.height <= 0) return -1
        val y = ((r.top + r.bottom) / 2).coerceIn(0, bitmap.height - 1)
        val left = r.left.coerceIn(0, bitmap.width - 1)
        val right = r.right.coerceIn(left + 1, bitmap.width)

        var matched = 0
        var sampled = 0
        for (x in left until right) {
            sampled++
            val px = bitmap.getPixel(x, y)
            if (colorDistance(px, region.referenceColor) <= tolerance) matched++
        }
        if (sampled == 0) return -1
        return (matched * 100 / sampled).coerceIn(0, 100)
    }

    /** True if the region's average color is still close to its calibrated reference color. */
    fun regionMatchesReference(bitmap: Bitmap, region: CalibratedRegion, tolerance: Int): Boolean {
        val avg = averageColor(bitmap, region.rect) ?: return false
        return colorDistance(avg, region.referenceColor) <= tolerance
    }

    /** Samples the average color across a grid inside [rect]; used during calibration. */
    fun averageColor(bitmap: Bitmap, rect: ScreenRect): Int? {
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val right = rect.right.coerceIn(left + 1, bitmap.width)
        val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)
        if (right <= left || bottom <= top) return null

        val stepX = maxOf(1, (right - left) / 24)
        val stepY = maxOf(1, (bottom - top) / 24)

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0L
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val px = bitmap.getPixel(x, y)
                rSum += Color.red(px)
                gSum += Color.green(px)
                bSum += Color.blue(px)
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0L) return null
        return Color.rgb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
    }

    private fun colorDistance(a: Int, b: Int): Int {
        val dr = Color.red(a) - Color.red(b)
        val dg = Color.green(a) - Color.green(b)
        val db = Color.blue(a) - Color.blue(b)
        return sqrt((dr * dr + dg * dg + db * db).toDouble()).toInt()
    }
}
