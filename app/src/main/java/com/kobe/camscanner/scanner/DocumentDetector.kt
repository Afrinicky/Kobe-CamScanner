package com.kobe.camscanner.scanner

import android.graphics.Bitmap
import com.kobe.camscanner.domain.model.PointN
import com.kobe.camscanner.domain.model.Quad
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
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
 * Finds the page in a frame.
 *
 * The first version of this ran one Canny pass and demanded the result approximate to exactly four
 * convex points with every corner between 60° and 120°. Real documents rarely oblige: a page on a
 * desk with a shadow along one edge, or a white sheet on a pale table, routinely approximates to
 * five or six points or produces a broken outline, and the detector simply returned nothing.
 *
 * This version runs several independent strategies and keeps the best-scoring quad any of them
 * finds. They fail in different circumstances, which is the point:
 *
 *  - **Morphological gradient** copes with low contrast — a white page on a light desk — because it
 *    responds to local intensity change rather than an absolute threshold.
 *  - **Auto-tuned Canny** is the sharpest on a well-lit page against a contrasting surface.
 *  - **Adaptive threshold** finds the page as a *region* rather than an outline, which is what
 *    survives when the border is partly lost in shadow.
 *
 * Each candidate contour is tried both as a polygon approximation and, if that does not yield four
 * usable points, as its minimum-area rectangle — so a page whose outline is slightly broken still
 * produces a sensible boundary instead of nothing at all.
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

    /** Runs every strategy on a shared downscaled proxy and returns the best quad found. */
    internal fun detectInGrey(grey: Mat): Detection {
        val srcWidth = grey.cols().toFloat()
        val srcHeight = grey.rows().toFloat()
        if (srcWidth < 32f || srcHeight < 32f) return Detection.NONE

        val small = Mat()
        val blurred = Mat()
        try {
            val scale = WORK_EDGE / max(srcWidth, srcHeight)
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

            // A plain Gaussian rather than a bilateral filter: an order of magnitude cheaper, and
            // at this scale the edge preservation a bilateral buys is not visible in the result.
            Imgproc.GaussianBlur(small, blurred, Size(5.0, 5.0), 0.0)

            val frameArea = (small.cols() * small.rows()).toDouble()
            var best: Candidate? = null

            for (mask in buildEdgeMasks(blurred)) {
                try {
                    val candidate = bestQuadIn(mask, frameArea)
                    if (candidate != null && (best == null || candidate.score > best!!.score)) {
                        best = candidate
                    }
                } finally {
                    mask.release()
                }
                // A confident hit early on makes the remaining strategies pointless.
                if ((best?.score ?: 0.0) > EARLY_ACCEPT) break
            }

            val candidate = best ?: return Detection.NONE
            val width = small.cols().toFloat()
            val height = small.rows().toFloat()
            val normalised = candidate.points.map {
                PointN(
                    (it.x / width).toFloat().coerceIn(0f, 1f),
                    (it.y / height).toFloat().coerceIn(0f, 1f),
                )
            }
            val quad = Quad.fromUnordered(normalised) ?: return Detection.NONE
            return Detection(quad, candidate.score.toFloat().coerceIn(0f, 1f))
        } catch (t: Throwable) {
            return Detection.NONE
        } finally {
            small.release()
            blurred.release()
        }
    }

    /**
     * The three binary edge/region images the search runs over, cheapest and sharpest first.
     * The caller releases each one.
     */
    private fun buildEdgeMasks(blurred: Mat): List<Mat> {
        val masks = ArrayList<Mat>(3)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        try {
            // 1. Auto-tuned Canny, closed up so a printed border's small gaps do not split the
            //    outline into pieces.
            runCatching {
                val median = medianOf(blurred)
                val edges = Mat()
                Imgproc.Canny(
                    blurred,
                    edges,
                    max(10.0, 0.60 * median),
                    min(255.0, 1.40 * median),
                )
                val closed = Mat()
                Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)
                edges.release()
                masks += closed
            }

            // 2. Morphological gradient: responds to local change, so it still outlines a white
            //    page lying on a pale desk where Canny's thresholds find almost nothing.
            runCatching {
                val gradient = Mat()
                Imgproc.morphologyEx(blurred, gradient, Imgproc.MORPH_GRADIENT, kernel)
                val binary = Mat()
                Imgproc.threshold(
                    gradient,
                    binary,
                    0.0,
                    255.0,
                    Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU,
                )
                gradient.release()
                val closed = Mat()
                Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel)
                binary.release()
                masks += closed
            }

            // 3. Adaptive threshold, which finds the page as a filled region. This is the one that
            //    survives a border partly lost in shadow, because it never needs a continuous edge.
            runCatching {
                val binary = Mat()
                val block = ((blurred.cols().coerceAtLeast(blurred.rows()) / 8) or 1).coerceIn(15, 75)
                Imgproc.adaptiveThreshold(
                    blurred,
                    binary,
                    255.0,
                    Imgproc.ADAPTIVE_THRESH_MEAN_C,
                    Imgproc.THRESH_BINARY,
                    block,
                    10.0,
                )
                val closed = Mat()
                Imgproc.morphologyEx(
                    binary,
                    closed,
                    Imgproc.MORPH_CLOSE,
                    Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0)),
                )
                binary.release()
                masks += closed
            }
        } finally {
            kernel.release()
        }
        return masks
    }

    /** Best-scoring quadrilateral in one binary image. */
    private fun bestQuadIn(mask: Mat, frameArea: Double): Candidate? {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(
                mask,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )
            if (contours.isEmpty()) return null

            var best: Candidate? = null
            contours
                .sortedByDescending { Imgproc.contourArea(it) }
                .take(MAX_CANDIDATES)
                .forEach { contour ->
                    val area = Imgproc.contourArea(contour)
                    if (area < frameArea * MIN_AREA_RATIO) return@forEach

                    quadFrom(contour)?.let { points ->
                        val score = scoreQuad(points, frameArea)
                        if (score > MIN_SCORE && (best == null || score > best!!.score)) {
                            best = Candidate(points, score)
                        }
                    }
                }
            return best
        } catch (t: Throwable) {
            return null
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
        }
    }

    /**
     * Turns a contour into four corners.
     *
     * Three attempts, in decreasing order of fidelity: approximate the contour at a few epsilons
     * and take it if it lands on four points; otherwise approximate its convex hull, which absorbs
     * a nick or a shadow notch in one edge; otherwise fall back to the minimum-area rectangle,
     * which is always four points and is close to right for anything roughly rectangular.
     */
    private fun quadFrom(contour: MatOfPoint): List<Point>? {
        val curve = MatOfPoint2f(*contour.toArray())
        try {
            val perimeter = Imgproc.arcLength(curve, true)
            if (perimeter <= 0.0) return null

            for (epsilon in APPROX_EPSILONS) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(curve, approx, epsilon * perimeter, true)
                val points = approx.toArray().toList()
                approx.release()
                if (points.size == 4 && isConvex(points)) return points
            }

            // Convex hull, then approximate again — this recovers a page whose outline has a bite
            // taken out of it by a shadow or an overlapping object.
            runCatching {
                val hullIndices = MatOfInt()
                Imgproc.convexHull(contour, hullIndices)
                val hullPoints = hullIndices.toArray().map { contour.toArray()[it] }
                hullIndices.release()
                if (hullPoints.size >= 4) {
                    val hullCurve = MatOfPoint2f(*hullPoints.toTypedArray())
                    val hullPerimeter = Imgproc.arcLength(hullCurve, true)
                    for (epsilon in APPROX_EPSILONS) {
                        val approx = MatOfPoint2f()
                        Imgproc.approxPolyDP(hullCurve, approx, epsilon * hullPerimeter, true)
                        val points = approx.toArray().toList()
                        approx.release()
                        if (points.size == 4 && isConvex(points)) {
                            hullCurve.release()
                            return points
                        }
                    }
                    hullCurve.release()
                }
            }

            // Last resort: the tightest rectangle around the contour, at whatever angle fits best.
            val box = Imgproc.minAreaRect(curve)
            val corners = arrayOfNulls<Point>(4)
            box.points(corners)
            return corners.filterNotNull().takeIf { it.size == 4 }
        } catch (t: Throwable) {
            return null
        } finally {
            curve.release()
        }
    }

    private fun isConvex(points: List<Point>): Boolean {
        val polygon = MatOfPoint(*points.toTypedArray())
        return try {
            Imgproc.isContourConvex(polygon)
        } catch (t: Throwable) {
            false
        } finally {
            polygon.release()
        }
    }

    /**
     * How much this quad looks like a sheet of paper photographed at an angle.
     *
     * The gates are deliberately looser than a textbook rectangle test. A page shot from 45° has
     * corners well outside 60–120° and strongly unequal opposite sides, and rejecting it outright —
     * which the first version did — is why nothing was ever detected. Instead the geometry feeds a
     * score, and the best candidate across every strategy wins.
     */
    private fun scoreQuad(points: List<Point>, frameArea: Double): Double {
        val ordered = orderForScoring(points) ?: return 0.0

        val angles = (0 until 4).map { i ->
            angleAt(ordered[(i + 3) % 4], ordered[i], ordered[(i + 1) % 4])
        }
        // Only reject the genuinely impossible: a corner this sharp or this flat means the contour
        // has latched onto something that is not a page.
        if (angles.any { it < MIN_ANGLE || it > MAX_ANGLE }) return 0.0

        val squareness = 1.0 - angles.sumOf { abs(it - 90.0) } / (4.0 * 60.0)

        val sides = (0 until 4).map { dist(ordered[it], ordered[(it + 1) % 4]) }
        if (sides.any { it < 1.0 }) return 0.0
        val symmetry = (ratio(sides[0], sides[2]) + ratio(sides[1], sides[3])) / 2.0

        val area = polygonArea(ordered)
        val coverage = (area / frameArea).coerceIn(0.0, 1.0)
        // Coverage helps up to about a third of the frame and then stops mattering; a page filling
        // the viewfinder is not more likely to be a page than one filling 40% of it.
        val coverageScore = min(coverage / 0.35, 1.0)

        // A page is a convex blob, so its area should nearly fill its own bounding quad. This is
        // what separates a real sheet from a random four-sided artefact in the background.
        return (squareness * 0.34 + symmetry * 0.33 + coverageScore * 0.33).coerceIn(0.0, 1.0)
    }

    /** Sorts four points into TL, TR, BR, BL for scoring. */
    private fun orderForScoring(points: List<Point>): List<Point>? {
        if (points.size != 4) return null
        val cx = points.sumOf { it.x } / 4.0
        val cy = points.sumOf { it.y } / 4.0
        // Sorting by angle around the centroid gives a consistent winding for any convex quad.
        val sorted = points.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
        return sorted.takeIf { it.size == 4 }
    }

    private fun polygonArea(points: List<Point>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2.0
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
                MatOfInt(0),
                Mat(),
                hist,
                MatOfInt(histSize),
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

    private data class Candidate(val points: List<Point>, val score: Double)

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
        private const val MIN_AREA_RATIO = 0.06

        /** Only the largest few contours per strategy are worth approximating. */
        private const val MAX_CANDIDATES = 6

        /** Epsilons tried when approximating a contour, tightest first. */
        private val APPROX_EPSILONS = doubleArrayOf(0.02, 0.035, 0.05, 0.08)

        /**
         * Corner limits. Wide on purpose: a page photographed from a low angle genuinely has
         * corners near 50° and near 130°, and the previous 60–120° window rejected those outright.
         */
        private const val MIN_ANGLE = 45.0
        private const val MAX_ANGLE = 135.0

        /** Below this a candidate is not worth reporting at all. */
        private const val MIN_SCORE = 0.30

        /** Score at which the remaining strategies are skipped. */
        private const val EARLY_ACCEPT = 0.82

        /** Detection at or above this confidence is shown as a locked-on boundary. */
        const val LOCK_CONFIDENCE = 0.45f
    }
}
