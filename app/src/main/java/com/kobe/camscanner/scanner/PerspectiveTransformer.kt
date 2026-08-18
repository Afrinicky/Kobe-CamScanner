package com.kobe.camscanner.scanner

import android.graphics.Bitmap
import android.graphics.Matrix
import com.kobe.camscanner.domain.model.Quad
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Perspective correction (SDS 11): four corners -> homography -> flat page.
 *
 * The output size is derived from the quad itself rather than fixed, so a receipt stays tall and
 * narrow and an A4 sheet stays A4-ish. Using the *longer* of each opposing pair means the corrected
 * page is never squashed — foreshortened edges get restored rather than averaged away.
 */
@Singleton
class PerspectiveTransformer @Inject constructor() {

    /**
     * Warps [source] so that [quad] (normalised 0..1 corners) becomes the full output rectangle.
     * Returns a new bitmap; [source] is left untouched so the original stays reusable.
     */
    fun correct(source: Bitmap, quad: Quad): Bitmap {
        if (!OpenCvLoader.isAvailable) return fallbackCrop(source, quad)

        val srcMat = Mat()
        val dstMat = Mat()
        var transform: Mat? = null
        var srcPoints: MatOfPoint2f? = null
        var dstPoints: MatOfPoint2f? = null

        try {
            Utils.bitmapToMat(source, srcMat)
            val w = source.width.toFloat()
            val h = source.height.toFloat()

            val tl = Point((quad.topLeft.x * w).toDouble(), (quad.topLeft.y * h).toDouble())
            val tr = Point((quad.topRight.x * w).toDouble(), (quad.topRight.y * h).toDouble())
            val br = Point((quad.bottomRight.x * w).toDouble(), (quad.bottomRight.y * h).toDouble())
            val bl = Point((quad.bottomLeft.x * w).toDouble(), (quad.bottomLeft.y * h).toDouble())

            val outWidth = max(distance(tl, tr), distance(bl, br)).roundToInt().coerceAtLeast(16)
            val outHeight = max(distance(tl, bl), distance(tr, br)).roundToInt().coerceAtLeast(16)

            srcPoints = MatOfPoint2f(tl, tr, br, bl)
            dstPoints = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(outWidth - 1.0, 0.0),
                Point(outWidth - 1.0, outHeight - 1.0),
                Point(0.0, outHeight - 1.0),
            )

            transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
            Imgproc.warpPerspective(
                srcMat,
                dstMat,
                transform,
                Size(outWidth.toDouble(), outHeight.toDouble()),
                Imgproc.INTER_CUBIC,
            )

            val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dstMat, output)
            return output
        } catch (t: Throwable) {
            return fallbackCrop(source, quad)
        } finally {
            srcMat.release()
            dstMat.release()
            transform?.release()
            srcPoints?.release()
            dstPoints?.release()
        }
    }

    /**
     * Straightens a page that is already rectangular but tilted, using the dominant text/edge angle.
     * Small rotations (< [MAX_DESKEW_DEGREES]) only — anything larger is a perspective problem, not
     * a skew problem, and belongs to [correct].
     */
    fun deskew(source: Bitmap): Bitmap {
        if (!OpenCvLoader.isAvailable) return source
        val angle = dominantSkewAngle(source) ?: return source
        if (kotlin.math.abs(angle) < MIN_DESKEW_DEGREES ||
            kotlin.math.abs(angle) > MAX_DESKEW_DEGREES
        ) {
            return source
        }
        val matrix = Matrix().apply { postRotate(-angle) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun dominantSkewAngle(source: Bitmap): Float? {
        val rgba = Mat()
        val grey = Mat()
        val edges = Mat()
        val lines = Mat()
        try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, grey, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Canny(grey, edges, 60.0, 180.0)
            Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180.0, 100, source.width / 4.0, 20.0)
            if (lines.rows() == 0) return null

            val angles = ArrayList<Double>(lines.rows())
            val row = DoubleArray(4)
            for (i in 0 until lines.rows()) {
                lines.get(i, 0, row)
                val dx = row[2] - row[0]
                val dy = row[3] - row[1]
                if (dx == 0.0) continue
                val degrees = Math.toDegrees(kotlin.math.atan2(dy, dx))
                // Keep near-horizontal lines only; those are text baselines and page edges.
                if (kotlin.math.abs(degrees) < MAX_DESKEW_DEGREES) angles += degrees
            }
            if (angles.isEmpty()) return null
            angles.sort()
            return angles[angles.size / 2].toFloat()
        } catch (t: Throwable) {
            return null
        } finally {
            rgba.release()
            grey.release()
            edges.release()
            lines.release()
        }
    }

    /**
     * Used when OpenCV is unavailable: an axis-aligned crop to the quad's bounding box. Not a
     * homography, but it still removes the surrounding desk and keeps the app usable.
     */
    private fun fallbackCrop(source: Bitmap, quad: Quad): Bitmap {
        val xs = quad.points.map { it.x }
        val ys = quad.points.map { it.y }
        val left = (xs.min() * source.width).roundToInt().coerceIn(0, source.width - 1)
        val top = (ys.min() * source.height).roundToInt().coerceIn(0, source.height - 1)
        val right = (xs.max() * source.width).roundToInt().coerceIn(left + 1, source.width)
        val bottom = (ys.max() * source.height).roundToInt().coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun distance(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        const val MIN_DESKEW_DEGREES = 0.4f
        const val MAX_DESKEW_DEGREES = 12f
    }
}
