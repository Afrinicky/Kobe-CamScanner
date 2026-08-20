package com.kobe.camscanner.cv

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/**
 * Builds photographs of documents that never existed.
 *
 * The scanner could not be checked on a phone from here, so the next best thing is to synthesise
 * the situations that break a detector — a page seen at an angle, a white sheet on a pale desk, a
 * shadow across one edge, clutter in the frame — and measure whether the real pipeline recovers the
 * corners. Because the page is *placed* by a known homography, the true corner positions are known
 * exactly, so accuracy is measurable rather than a matter of opinion.
 */
object SyntheticDocument {

    /** Where the page's corners really are, in pixels, for the image that was generated. */
    data class Truth(
        val image: Mat,
        val corners: List<Point>,
    ) {
        /** Corners normalised to 0..1, matching what the detector returns. */
        val normalised: List<Pair<Float, Float>>
            get() = corners.map {
                (it.x / image.cols()).toFloat() to (it.y / image.rows()).toFloat()
            }
    }

    /**
     * @param width          canvas size
     * @param height         canvas size
     * @param deskShade      background grey (0..255). High values make the page hard to separate.
     * @param corners        where the page's four corners land, TL/TR/BR/BL
     * @param shadowStrength 0 = evenly lit, 1 = one edge in deep shadow
     * @param clutter        draw distracting shapes on the desk
     * @param noise          gaussian sensor noise standard deviation
     * @param paperShade     the document's own shade, so a dark card on a light desk can be posed
     */
    fun render(
        width: Int = 720,
        height: Int = 960,
        deskShade: Double = 90.0,
        corners: List<Point> = defaultCorners(width, height),
        shadowStrength: Double = 0.0,
        clutter: Boolean = false,
        noise: Double = 0.0,
        paperShade: Double = 244.0,
    ): Truth {
        val canvas = Mat(height, width, CvType.CV_8UC1, Scalar(deskShade))

        if (clutter) drawClutter(canvas, deskShade)

        val page = renderPage(paperShade = paperShade)
        val warped = Mat()
        val mask = Mat()

        val srcCorners = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(page.cols() - 1.0, 0.0),
            Point(page.cols() - 1.0, page.rows() - 1.0),
            Point(0.0, page.rows() - 1.0),
        )
        val dstCorners = MatOfPoint2f(*corners.toTypedArray())
        val transform = Imgproc.getPerspectiveTransform(srcCorners, dstCorners)

        Imgproc.warpPerspective(page, warped, transform, Size(width.toDouble(), height.toDouble()))

        // A mask of exactly where the page landed, so it composites without a halo.
        val white = Mat(page.rows(), page.cols(), CvType.CV_8UC1, Scalar(255.0))
        Imgproc.warpPerspective(white, mask, transform, Size(width.toDouble(), height.toDouble()))
        warped.copyTo(canvas, mask)

        if (shadowStrength > 0.0) applyShadow(canvas, shadowStrength)
        if (noise > 0.0) applyNoise(canvas, noise)

        listOf(page, warped, mask, white, srcCorners, dstCorners, transform).forEach { it.release() }
        return Truth(canvas, corners)
    }

    /** A page lying flat and square-on, filling most of the frame. */
    fun defaultCorners(width: Int, height: Int): List<Point> {
        val mx = width * 0.12
        val my = height * 0.10
        return listOf(
            Point(mx, my),
            Point(width - mx, my),
            Point(width - mx, height - my),
            Point(mx, height - my),
        )
    }

    /** A page seen from an angle: the far edge is shorter and the sides converge. */
    fun perspectiveCorners(width: Int, height: Int, strength: Double = 0.18): List<Point> {
        val mx = width * 0.10
        val my = height * 0.10
        val inset = width * strength
        return listOf(
            Point(mx + inset, my),
            Point(width - mx - inset, my),
            Point(width - mx, height - my),
            Point(mx, height - my),
        )
    }

    /** A page rotated within the frame, which is what a hand-held shot usually produces. */
    fun rotatedCorners(width: Int, height: Int, degrees: Double = 12.0): List<Point> {
        val cx = width / 2.0
        val cy = height / 2.0
        val hw = width * 0.36
        val hh = height * 0.38
        val radians = Math.toRadians(degrees)
        val cos = kotlin.math.cos(radians)
        val sin = kotlin.math.sin(radians)
        return listOf(
            Point(-hw, -hh), Point(hw, -hh), Point(hw, hh), Point(-hw, hh),
        ).map { Point(cx + it.x * cos - it.y * sin, cy + it.x * sin + it.y * cos) }
    }

    /**
     * A sheet of paper with printed text on it.
     *
     * The ink follows the paper: dark type on a light sheet, light type on a dark card. The point
     * of the text is that it is the strongest contrast in the frame — much stronger than the
     * paper-to-desk step — which is exactly what makes a detector mistake the paragraph block for
     * the document.
     */
    private fun renderPage(width: Int = 620, height: Int = 850, paperShade: Double = 244.0): Mat {
        val page = Mat(height, width, CvType.CV_8UC1, Scalar(paperShade))
        val heading = if (paperShade > 128.0) 30.0 else 225.0
        val body = if (paperShade > 128.0) 55.0 else 200.0

        // A heading and body text, drawn as filled bars — at the scale detection works on, real
        // glyphs and bars are indistinguishable, and bars keep the fixture deterministic.
        Imgproc.rectangle(
            page,
            Point(width * 0.12, height * 0.08),
            Point(width * 0.66, height * 0.12),
            Scalar(heading),
            -1,
        )
        var y = height * 0.20
        var line = 0
        while (y < height * 0.88) {
            val right = if (line % 5 == 4) width * 0.52 else width * 0.86
            Imgproc.rectangle(
                page,
                Point(width * 0.12, y),
                Point(right, y + height * 0.012),
                Scalar(body),
                -1,
            )
            y += height * 0.035
            line++
        }
        return page
    }

    /** Objects on the desk that a naive detector will happily mistake for the document. */
    private fun drawClutter(canvas: Mat, deskShade: Double) {
        val w = canvas.cols()
        val h = canvas.rows()
        Imgproc.rectangle(
            canvas,
            Point(w * 0.02, h * 0.02),
            Point(w * 0.22, h * 0.16),
            Scalar(deskShade + 45.0),
            -1,
        )
        Imgproc.circle(
            canvas,
            Point(w * 0.9, h * 0.93),
            (w * 0.07).roundToInt(),
            Scalar(deskShade - 35.0),
            -1,
        )
        Imgproc.line(
            canvas,
            Point(0.0, h * 0.5),
            Point(w * 0.08, h * 0.62),
            Scalar(deskShade + 60.0),
            6,
        )
    }

    /** A soft luminance ramp, the way a hand or a lamp shades one side of a page. */
    private fun applyShadow(canvas: Mat, strength: Double) {
        val h = canvas.rows()
        val w = canvas.cols()
        val ramp = Mat(h, w, CvType.CV_32F)
        for (y in 0 until h) {
            val row = FloatArray(w)
            for (x in 0 until w) {
                // Darkest in the top-left, fading out across the diagonal.
                val t = (x.toFloat() / w) * 0.5f + (y.toFloat() / h) * 0.5f
                row[x] = (1.0 - strength * (1.0 - t)).toFloat()
            }
            ramp.put(y, 0, row)
        }
        val floatCanvas = Mat()
        canvas.convertTo(floatCanvas, CvType.CV_32F)
        Core.multiply(floatCanvas, ramp, floatCanvas)
        floatCanvas.convertTo(canvas, CvType.CV_8U)
        ramp.release()
        floatCanvas.release()
    }

    private fun applyNoise(canvas: Mat, sigma: Double) {
        val noise = Mat(canvas.rows(), canvas.cols(), CvType.CV_16SC1)
        Core.randn(noise, 0.0, sigma)
        val signed = Mat()
        canvas.convertTo(signed, CvType.CV_16SC1)
        Core.add(signed, noise, signed)
        signed.convertTo(canvas, CvType.CV_8U)
        noise.release()
        signed.release()
    }

    /** Mean corner error between a detection and the truth, as a fraction of the image diagonal. */
    fun cornerError(
        detected: List<Pair<Float, Float>>,
        truth: List<Point>,
        width: Int,
        height: Int,
    ): Double {
        if (detected.size != 4 || truth.size != 4) return Double.MAX_VALUE
        val diagonal = kotlin.math.sqrt((width * width + height * height).toDouble())
        // Both lists are TL/TR/BR/BL, so they compare position by position.
        return detected.indices.sumOf { i ->
            val dx = detected[i].first * width - truth[i].x
            val dy = detected[i].second * height - truth[i].y
            kotlin.math.sqrt(dx * dx + dy * dy)
        } / 4.0 / diagonal
    }

    /** Convenience for turning a MatOfPoint into plain points in assertions. */
    fun pointsOf(contour: MatOfPoint): List<Point> = contour.toArray().toList()
}
