package com.ornahelper.autoplay.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.ornahelper.autoplay.data.CalibratedRegion
import com.ornahelper.autoplay.data.ScreenRect
import com.ornahelper.autoplay.data.TapPoint
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Reads calibrated screen regions from a captured frame using simple color matching.
 * No OCR / template matching — just color-distance comparisons against a reference
 * color sampled during calibration. This keeps the bot lightweight and fast enough
 * to run every tick.
 */
object BarReader {

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
     * color by more than [tolerance] — i.e. monsters standing on an otherwise empty spawn
     * area — groups adjacent matching grid cells into clusters (8-connectivity flood fill),
     * and returns a random point sampled from within the *largest* cluster (not its centroid).
     * A centroid can land in a gap for a concave/irregular monster sprite, and always tapping
     * the exact same pixel repeatedly does nothing to correct for the monster having drifted
     * slightly by the time the tap lands; picking a random point that was actually detected as
     * part of the monster, with a little sub-cell jitter, keeps every attempt on-target while
     * still varying where exactly it lands. Returns null if the largest cluster has fewer than
     * [minClusterSamples] grid cells (nothing there worth tapping).
     */
    fun findLargestClusterTapPoint(
        bitmap: Bitmap,
        region: CalibratedRegion,
        tolerance: Int,
        minClusterSamples: Int
    ): TapPoint? {
        val r = region.rect
        val left = r.left.coerceIn(0, bitmap.width - 1)
        val top = r.top.coerceIn(0, bitmap.height - 1)
        val right = r.right.coerceIn(left + 1, bitmap.width)
        val bottom = r.bottom.coerceIn(top + 1, bitmap.height)
        if (right <= left || bottom <= top) return null

        val stepX = maxOf(1, (right - left) / 80)
        val stepY = maxOf(1, (bottom - top) / 80)
        val cols = (right - left + stepX - 1) / stepX
        val rows = (bottom - top + stepY - 1) / stepY
        if (cols <= 0 || rows <= 0) return null

        val foreground = BooleanArray(cols * rows)
        for (row in 0 until rows) {
            val y = (top + row * stepY).coerceAtMost(bottom - 1)
            for (col in 0 until cols) {
                val x = (left + col * stepX).coerceAtMost(right - 1)
                val px = bitmap.getPixel(x, y)
                foreground[row * cols + col] = colorDistance(px, region.referenceColor) > tolerance
            }
        }

        val visited = BooleanArray(cols * rows)
        val queue = ArrayDeque<Int>()
        var bestMembers: List<Int> = emptyList()

        for (start in 0 until cols * rows) {
            if (!foreground[start] || visited[start]) continue
            queue.clear()
            queue.add(start)
            visited[start] = true
            val members = mutableListOf<Int>()
            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                members.add(idx)
                val row = idx / cols
                val col = idx % cols

                for (dr in -1..1) {
                    for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        val nr = row + dr
                        val nc = col + dc
                        if (nr in 0 until rows && nc in 0 until cols) {
                            val nIdx = nr * cols + nc
                            if (foreground[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                queue.add(nIdx)
                            }
                        }
                    }
                }
            }
            if (members.size > bestMembers.size) bestMembers = members
        }

        if (bestMembers.size < minClusterSamples) return null
        val chosen = bestMembers[Random.nextInt(bestMembers.size)]
        val baseX = left + (chosen % cols) * stepX
        val baseY = top + (chosen / cols) * stepY
        val x = (baseX + Random.nextInt(stepX)).coerceIn(left, right - 1)
        val y = (baseY + Random.nextInt(stepY)).coerceIn(top, bottom - 1)
        return TapPoint(x, y)
    }

    fun colorDistance(a: Int, b: Int): Int {
        val dr = Color.red(a) - Color.red(b)
        val dg = Color.green(a) - Color.green(b)
        val db = Color.blue(a) - Color.blue(b)
        return sqrt((dr * dr + dg * dg + db * db).toDouble()).toInt()
    }
}
