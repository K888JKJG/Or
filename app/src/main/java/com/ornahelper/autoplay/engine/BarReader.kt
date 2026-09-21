package com.ornahelper.autoplay.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.ornahelper.autoplay.data.CalibratedRegion
import com.ornahelper.autoplay.data.ScreenRect
import com.ornahelper.autoplay.data.TapPoint
import kotlin.math.sqrt

/**
 * Reads calibrated screen regions from a captured frame using simple color matching.
 * No OCR / template matching — just color-distance comparisons against a reference
 * color sampled during calibration. This keeps the bot lightweight and fast enough
 * to run every tick.
 */
object BarReader {

    /** True if the region's average color is still close to its calibrated reference color. */
    fun regionMatchesReference(bitmap: Bitmap, region: CalibratedRegion, tolerance: Int): Boolean {
        val avg = averageColor(bitmap, region.rect) ?: return false
        return colorDistance(avg, region.referenceColor) <= tolerance
    }

    /** Distance between [region]'s live average color and its calibrated reference, or null if out of bounds. */
    fun regionColorDistance(bitmap: Bitmap, region: CalibratedRegion): Int? {
        val avg = averageColor(bitmap, region.rect) ?: return null
        return colorDistance(avg, region.referenceColor)
    }

    /** Samples the average color across a grid inside [rect]; used during calibration and state checks. */
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

    /**
     * Scans [region] on a coarse grid for pixels that differ from its reference (background)
     * color by more than [tolerance] — i.e. a monster standing on an otherwise empty spawn
     * area — and returns the centroid of every such pixel. Returns null if fewer than
     * [minMatchedSamples] pixels matched (nothing there worth tapping).
     */
    fun findForegroundCentroid(
        bitmap: Bitmap,
        region: CalibratedRegion,
        tolerance: Int,
        minMatchedSamples: Int
    ): TapPoint? {
        val r = region.rect
        val left = r.left.coerceIn(0, bitmap.width - 1)
        val top = r.top.coerceIn(0, bitmap.height - 1)
        val right = r.right.coerceIn(left + 1, bitmap.width)
        val bottom = r.bottom.coerceIn(top + 1, bitmap.height)
        if (right <= left || bottom <= top) return null

        val stepX = maxOf(1, (right - left) / 80)
        val stepY = maxOf(1, (bottom - top) / 80)

        var sumX = 0L
        var sumY = 0L
        var matched = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val px = bitmap.getPixel(x, y)
                if (colorDistance(px, region.referenceColor) > tolerance) {
                    sumX += x
                    sumY += y
                    matched++
                }
                x += stepX
            }
            y += stepY
        }
        if (matched < minMatchedSamples) return null
        return TapPoint((sumX / matched).toInt(), (sumY / matched).toInt())
    }

    fun colorDistance(a: Int, b: Int): Int {
        val dr = Color.red(a) - Color.red(b)
        val dg = Color.green(a) - Color.green(b)
        val db = Color.blue(a) - Color.blue(b)
        return sqrt((dr * dr + dg * dg + db * db).toDouble()).toInt()
    }
}
