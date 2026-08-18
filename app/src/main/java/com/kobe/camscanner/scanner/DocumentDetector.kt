package com.kobe.camscanner.scanner

import android.graphics.Bitmap
import com.kobe.camscanner.domain.model.PointN
import com.kobe.camscanner.domain.model.Quad
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Document boundary detection, implementing the pipeline in SDS 9:
 *
 *   frame -> resize -> greyscale -> denoise -> edges -> contours -> polygon approximation ->
 *   quadrilateral -> corner validation -> boundary
 *
 * Two things matter for the feel of the scanner. First, everything runs on a small proxy image
 * (long edge [WORK_EDGE] px) — a 4000 px frame carries no extra edge information at this scale and
 * costs 20x the time (SDS 45). Second, detection returns *normalised* corners, so the same quad is
 * valid for the preview overlay and for the full-resolution capture without any rescaling logic at
 * the call sites.
 */
@Singleton
class DocumentDetector @Inject constructor() {

    /**
     * Detects a document in an 8-bit single-channel luminance buffer.
     *
     * This is the hot path: CameraX hands over YUV_420_888, whose Y plane *is* a greyscale image,
     * so the analyser can skip colour conversion entirely.
     */
    fun detectFromLuminance(
        luminance: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
    ): Detection {
        if (!OpenCvLoader.isAvailable || width <= 0 || height <= 0) return Detection.NONE

        val grey = Mat(height, width, CvType.CV_8UC1)
        try {
            if (rowStride == width) {
                grey.put(0, 0, luminance)
            } else {
                // Strided planes are common on real hardware; copy row by row rather than
                // allocating a second full buffer.
                val row = ByteArray(width)
                for (y in 0 until height) {
                    val offset = y * rowStride
                    if (offset + width > luminance.size) break
                    System.arraycopy(luminance, offset, row, 0, width)
                    grey.put(y, 0, row)
                }
            }
            return detectInGrey(grey)
        } finally {
            grey.release()
        }
    }

    /** Detects a document in a decoded bitmap (gallery import, or a re-detect on a saved page). */
    fun detectFromBitmap(bitmap: Bitmap): Detection {
        if (!OpenCvLoader.isAvailable) return Detection.NONE
        val rgba = Mat()
        val grey = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, grey, Imgproc.COLOR_RGBA2GRAY)
            return detectInGrey(grey)
        } catch (t: Throwable) {
            return Detection.NONE
        } finally {
            rgba.release()
            grey.release()
        }
    }

    private fun detectInGrey(grey: Mat): Detection {
        val srcWidth = grey.cols().toFloat()
        val srcHeight = grey.rows().toFloat()
        if (srcWidth < 32f || srcHeight < 32f) return Detection.NONE

        val scale = WORK_EDGE / max(srcWidth, srcHeight)
        val small = Mat()
        val blurred = Mat()
        val edges = Mat()
        val closed = Mat()

        try {
            if (scale < 1f) {
                Imgproc.resize(
                    grey,
                    small,
                    Size((srcWidth * scale).toDouble(), (srcHeight * scale).toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA,
                )
            } else {
                grey.copyTo(small)
            }

            // Denoise. A bilateral filter keeps the page border crisp while flattening paper
            // texture and print, which is exactly the trade-off edge detection wants here.
            Imgproc.bilateralFilter(small, blurred, 7, 45.0, 45.0)

            // Auto-thresholded Canny: the median of the image sets the hysteresis band, so the
            // detector holds up under office fluorescents and dim room light alike.
            val median = medianOf(blurred)
            val lower = max(0.0, 0.66 * median)
            val upper = min(255.0, 1.33 * median)
            Imgproc.Canny(blurred, edges, lower, upper)

            // Dilate then erode to bridge the small gaps a printed border leaves in the outline.
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                closed,
                contours,
                hierarchy,
                Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )
            hierarchy.release()
            if (contours.isEmpty()) return Detection.NONE

            val frameArea = (small.cols() * small.rows()).toDouble()
            var best: List<Point>? = null
            var bestScore = 0.0

            contours
                .sortedByDescending { Imgproc.contourArea(it) }
                .take(MAX_CANDIDATES)
                .forEach { contour ->
                    val area = Imgproc.contourArea(contour)
                    if (area < frameArea * MIN_AREA_RATIO) return@forEach

                    val curve = MatOfPoint2f(*contour.toArray())
                    val perimeter = Imgproc.arcLength(curve, true)
                    val approx = MatOfPoint2f()
                    Imgproc.approxPolyDP(curve, approx, 0.02 * perimeter, true)
                    val points = approx.toArray().toList()
                    curve.release()
                    approx.release()

                    if (points.size != 4) return@forEach
                    val polygon = MatOfPoint(*points.toTypedArray())
                    val convex = Imgproc.isContourConvex(polygon)
                    polygon.release()
                    if (!convex) return@forEach

                    val score = scoreQuad(points, area, frameArea)
                    if (score > bestScore) {
                        bestScore = score
                        best = points
                    }
                }

            contours.forEach { it.release() }

            val corners = best ?: return Detection.NONE
            val width = small.cols().toFloat()
            val height = small.rows().toFloat()
            val normalised = corners.map {
                PointN(
                    (it.x / width).toFloat().coerceIn(0f, 1f),
                    (it.y / height).toFloat().coerceIn(0f, 1f),
                )
            }
            val quad = Quad.fromUnordered(normalised) ?: return Detection.NONE
            return Detection(quad, bestScore.toFloat().coerceIn(0f, 1f))
        } catch (t: Throwable) {
            return Detection.NONE
        } finally {
            small.release()
            blurred.release()
            edges.release()
            closed.release()
        }
    }

    /**
     * Corner validation (SDS 9). A quad is only accepted if it is plausibly a piece of paper seen
     * at an angle, which means: large enough, roughly convex, corners not too far from square, and
     * opposite sides of comparable length. The score blends those so the *best* candidate wins
     * rather than merely the largest.
     */
    private fun scoreQuad(points: List<Point>, area: Double, frameArea: Double): Double {
        val angles = (0 until 4).map { i ->
            val prev = points[(i + 3) % 4]
            val curr = points[i]
            val next = points[(i + 1) % 4]
            angleAt(prev, curr, next)
        }
        // Reject anything with a corner outside 60..120 degrees; paper never looks like that
        // unless the contour has latched onto something else in the frame.
        if (angles.any { it < MIN_ANGLE || it > MAX_ANGLE }) return 0.0

        val squareness = 1.0 - angles.sumOf { abs(it - 90.0) } / (4.0 * 30.0)

        val sides = (0 until 4).map { dist(points[it], points[(it + 1) % 4]) }
        val topBottom = ratio(sides[0], sides[2])
        val leftRight = ratio(sides[1], sides[3])
        val symmetry = (topBottom + leftRight) / 2.0

        val coverage = (area / frameArea).coerceIn(0.0, 1.0)
        // Coverage helps up to about half the frame and then stops mattering; a page filling the
        // whole viewfinder is not more likely to be a page than one filling 60% of it.
        val coverageScore = min(coverage / 0.5, 1.0)

        return (squareness * 0.4 + symmetry * 0.35 + coverageScore * 0.25).coerceIn(0.0, 1.0)
    }

    private fun ratio(a: Double, b: Double): Double {
        if (a <= 0.0 || b <= 0.0) return 0.0
        return min(a, b) / max(a, b)
    }

    private fun dist(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun angleAt(prev: Point, curr: Point, next: Point): Double {
        val v1x = prev.x - curr.x
        val v1y = prev.y - curr.y
        val v2x = next.x - curr.x
        val v2y = next.y - curr.y
        val dot = v1x * v2x + v1y * v2y
        val mag = sqrt(v1x * v1x + v1y * v1y) * sqrt(v2x * v2x + v2y * v2y)
        if (mag == 0.0) return 0.0
        return Math.toDegrees(kotlin.math.acos((dot / mag).coerceIn(-1.0, 1.0)))
    }

    /** Median intensity, used to auto-tune the Canny thresholds. */
    private fun medianOf(mat: Mat): Double {
        val histSize = 256
        val hist = Mat()
        try {
            Imgproc.calcHist(
                listOf(mat),
                org.opencv.core.MatOfInt(0),
                Mat(),
                hist,
                org.opencv.core.MatOfInt(histSize),
                org.opencv.core.MatOfFloat(0f, 256f),
            )
            val total = mat.total()
            var running = 0.0
            val target = total / 2.0
            val bin = FloatArray(1)
            for (i in 0 until histSize) {
                hist.get(i, 0, bin)
                running += bin[0]
                if (running >= target) return i.toDouble()
            }
            return 128.0
        } catch (t: Throwable) {
            return 128.0
        } finally {
            hist.release()
        }
    }

    /** A detected boundary and how much the detector trusts it. */
    data class Detection(val quad: Quad?, val confidence: Float) {
        val found: Boolean get() = quad != null

        companion object {
            val NONE = Detection(null, 0f)
        }
    }

    companion object {
        /** Long edge, in pixels, of the proxy image every stage of detection runs on. */
        const val WORK_EDGE = 480f

        /** Contours smaller than this share of the frame are never a document. */
        private const val MIN_AREA_RATIO = 0.10

        /** Only the largest few contours are worth approximating. */
        private const val MAX_CANDIDATES = 8

        private const val MIN_ANGLE = 60.0
        private const val MAX_ANGLE = 120.0

        /** Detection at or above this confidence is shown as a locked-on boundary. */
        const val LOCK_CONFIDENCE = 0.55f
    }
}
