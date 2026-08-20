package com.kobe.camscanner.scanner

import android.graphics.Bitmap
import com.kobe.camscanner.domain.model.Adjustments
import com.kobe.camscanner.domain.model.ScanFilter
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Turns a photograph of a page into something that looks scanned.
 *
 * Two things make the difference, and the first version got both wrong.
 *
 * **Flat-fielding.** Dividing the page by a heavily blurred copy of itself cancels the illumination
 * — the phone's own shadow, lamp falloff, the grey cast of a photocopy — in one step. The blur is
 * now estimated on a 1/8-scale copy and scaled back up, which is visually identical to blurring at
 * full size and something like fifty times faster; the old code ran a 151-tap Gaussian across a
 * 3000 px image and took seconds.
 *
 * **Levels.** This is what was actually missing. The old "whitening" was a plain gain: it multiplied
 * everything, so the paper brightened *and so did the text*, which is precisely the washed-out
 * result that made scans look poor. A real scanner sets a black point and a white point and stretches
 * between them, so paper lands on pure white and ink lands on near-black. Both points are read off
 * the image's own histogram, so a faint pencil note and a laser printout each come out right.
 */
@Singleton
class ImageEnhancer @Inject constructor() {

    /** The full automatic pass: flat-field, level, sharpen. */
    fun autoEnhance(source: Bitmap): Bitmap = apply(source, ScanFilter.AUTO, Adjustments.NEUTRAL)

    /**
     * Applies a filter and then the manual adjustments on top. Returns a new bitmap and leaves
     * [source] alone, so the filter strip can re-render from the same original repeatedly.
     */
    fun apply(source: Bitmap, filter: ScanFilter, adjustments: Adjustments): Bitmap {
        if (!OpenCvLoader.isAvailable) return fallbackApply(source, filter, adjustments)

        val src = Mat()
        var work: Mat? = null
        try {
            Utils.bitmapToMat(source, src)
            // Drop alpha: scans are opaque, and three channels halve the work of every stage below.
            val rgb = Mat()
            Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)

            work = runFilter(rgb, filter)
            rgb.release()

            if (!adjustments.isNeutral) {
                val adjusted = applyAdjustments(work, adjustments)
                work.release()
                work = adjusted
            }

            val output = Bitmap.createBitmap(work.cols(), work.rows(), Bitmap.Config.ARGB_8888)
            val rgba = Mat()
            Imgproc.cvtColor(work, rgba, Imgproc.COLOR_RGB2RGBA)
            Utils.matToBitmap(rgba, output)
            rgba.release()
            return output
        } catch (t: Throwable) {
            return fallbackApply(source, filter, adjustments)
        } finally {
            src.release()
            work?.release()
        }
    }

    /** Runs one filter over an RGB matrix. Exposed for the JVM test harness. */
    internal fun runFilter(rgb: Mat, filter: ScanFilter): Mat = when (filter) {
        ScanFilter.ORIGINAL -> rgb.clone()
        ScanFilter.AUTO -> documentPipeline(rgb, saturation = 0.85f, blackPercentile = 4.0)
        ScanFilter.COLOR -> colourPipeline(rgb, boost = false)
        ScanFilter.MAGIC_COLOR -> colourPipeline(rgb, boost = true)
        ScanFilter.GRAYSCALE -> greyscalePipeline(rgb)
        ScanFilter.BLACK_WHITE -> blackWhitePipeline(rgb)
        ScanFilter.DOCUMENT -> documentPipeline(rgb, saturation = 0.35f, blackPercentile = 6.0)
        ScanFilter.PHOTO -> photoPipeline(rgb)
    }

    // ------------------------------------------------------------------ pipelines

    /**
     * The default. Flat-field, pull the levels apart, keep a little colour so stamps and
     * highlighter survive, then sharpen.
     */
    private fun documentPipeline(rgb: Mat, saturation: Float, blackPercentile: Double): Mat {
        val balanced = greyWorldWhiteBalance(rgb)
        val flattened = flatField(balanced)
        balanced.release()

        val levelled = stretchLevels(flattened, blackPercentile, WHITE_PERCENTILE)
        flattened.release()

        val toned = if (saturation == 1f) levelled else scaleSaturation(levelled, saturation)
        if (toned !== levelled) levelled.release()

        val sharpened = unsharpMask(toned, amount = 0.7, radius = 1.2)
        toned.release()
        return sharpened
    }

    /** Colour: keeps the photograph honest, just lifts the illumination and sets the white point. */
    private fun colourPipeline(rgb: Mat, boost: Boolean): Mat {
        val balanced = greyWorldWhiteBalance(rgb)
        val flattened = flatField(balanced, strength = 0.75)
        balanced.release()

        val levelled = stretchLevels(flattened, blackPercentile = 1.0, whitePercentile = 99.0)
        flattened.release()

        if (!boost) {
            val sharpened = unsharpMask(levelled, amount = 0.5, radius = 1.2)
            levelled.release()
            return sharpened
        }
        // "Magic colour" is the punchier variant, which is what makes a printed brochure or a
        // highlighted page pop.
        val saturated = scaleSaturation(levelled, 1.30f)
        levelled.release()
        val contrasted = claheOnLuminance(saturated, clipLimit = 2.0)
        saturated.release()
        val sharpened = unsharpMask(contrasted, amount = 0.9, radius = 1.1)
        contrasted.release()
        return sharpened
    }

    private fun greyscalePipeline(rgb: Mat): Mat {
        val flattened = flatField(rgb)
        val levelled = stretchLevels(flattened, 4.0, WHITE_PERCENTILE)
        flattened.release()
        val grey = Mat()
        Imgproc.cvtColor(levelled, grey, Imgproc.COLOR_RGB2GRAY)
        levelled.release()
        val out = Mat()
        Imgproc.cvtColor(grey, out, Imgproc.COLOR_GRAY2RGB)
        grey.release()
        val sharpened = unsharpMask(out, amount = 0.7, radius = 1.2)
        out.release()
        return sharpened
    }

    /**
     * Black & white via adaptive thresholding after flat-fielding. Doing both matters: flat-fielding
     * removes the shadow, so the threshold only has to separate ink from paper, which it does
     * cleanly even on a page photographed under a desk lamp.
     */
    private fun blackWhitePipeline(rgb: Mat): Mat {
        val flattened = flatField(rgb)
        val grey = Mat()
        Imgproc.cvtColor(flattened, grey, Imgproc.COLOR_RGB2GRAY)
        flattened.release()

        val denoised = Mat()
        Imgproc.medianBlur(grey, denoised, 3)
        grey.release()

        val binary = Mat()
        // Block size scales with the image so the window always spans a few characters.
        val block = ((denoised.cols().coerceAtLeast(denoised.rows()) / 40) or 1).coerceIn(11, 61)
        Imgproc.adaptiveThreshold(
            denoised,
            binary,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            block,
            14.0,
        )
        denoised.release()

        val out = Mat()
        Imgproc.cvtColor(binary, out, Imgproc.COLOR_GRAY2RGB)
        binary.release()
        return out
    }

    /** Photo: no page assumptions. A gentle tone curve only — used for whiteboards and pictures. */
    private fun photoPipeline(rgb: Mat): Mat {
        val balanced = greyWorldWhiteBalance(rgb)
        val toned = claheOnLuminance(balanced, clipLimit = 1.6)
        balanced.release()
        return toned
    }

    // ------------------------------------------------------------------ operators

    /**
     * Cancels uneven illumination by dividing the image by a blurred estimate of the light falling
     * on it.
     *
     * The estimate is built at [ESTIMATE_EDGE] px and scaled back up. Blurring a small image and
     * enlarging the result is the same operation as blurring the large one — a Gaussian is
     * low-pass, so the detail being thrown away is detail the blur would have removed anyway — and
     * it turns a multi-second full-resolution convolution into a few milliseconds.
     *
     * @param strength 1.0 removes the illumination entirely; lower values leave some of the
     *   original shading, which suits photographs better than documents.
     */
    internal fun flatField(rgb: Mat, strength: Double = 1.0): Mat {
        val longEdge = max(rgb.cols(), rgb.rows())
        val scale = ESTIMATE_EDGE.toDouble() / longEdge

        val small = Mat()
        if (scale < 1.0) {
            Imgproc.resize(rgb, small, Size(), scale, scale, Imgproc.INTER_AREA)
        } else {
            rgb.copyTo(small)
        }

        // A blur wide enough to erase text but not the shading itself: about a fifth of the
        // proxy's long edge.
        val kernel = ((max(small.cols(), small.rows()) / 5) or 1).coerceIn(9, 121).toDouble()
        val blurredSmall = Mat()
        Imgproc.GaussianBlur(small, blurredSmall, Size(kernel, kernel), 0.0)
        small.release()

        val background = Mat()
        Imgproc.resize(blurredSmall, background, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        blurredSmall.release()

        val srcF = Mat()
        val bgF = Mat()
        rgb.convertTo(srcF, CvType.CV_32F)
        background.convertTo(bgF, CvType.CV_32F)
        background.release()

        // Guard against dividing by zero in genuinely black regions.
        Core.add(bgF, Scalar(1.0, 1.0, 1.0), bgF)

        val ratio = Mat()
        Core.divide(srcF, bgF, ratio)
        bgF.release()

        val out = Mat()
        if (strength >= 1.0) {
            ratio.convertTo(out, CvType.CV_8U, 255.0)
        } else {
            // Blend the corrected image back towards the original.
            val corrected = Mat()
            ratio.convertTo(corrected, CvType.CV_32F, 255.0)
            Core.addWeighted(corrected, strength, srcF, 1.0 - strength, 0.0, corrected)
            corrected.convertTo(out, CvType.CV_8U)
            corrected.release()
        }
        srcF.release()
        ratio.release()
        return out
    }

    /**
     * Sets a black point and a white point from the image's own histogram and stretches between
     * them, which is what actually produces white paper and black ink.
     *
     * Percentiles rather than fixed thresholds: [blackPercentile] of the pixels are ink and
     * everything at or below that level is driven to black, while everything above
     * [whitePercentile] becomes paper. A page with a lot of text and a nearly blank one both land
     * in the right place.
     */
    internal fun stretchLevels(rgb: Mat, blackPercentile: Double, whitePercentile: Double): Mat {
        val grey = Mat()
        Imgproc.cvtColor(rgb, grey, Imgproc.COLOR_RGB2GRAY)
        val black = percentile(grey, blackPercentile)
        val white = percentile(grey, whitePercentile)
        grey.release()

        // Refuse to act on a degenerate histogram — a blank or blown-out frame — rather than
        // amplifying its noise into a wall of speckle.
        val span = white - black
        if (span < MIN_LEVEL_SPAN) return rgb.clone()

        val gain = 255.0 / span
        val bias = -black * gain
        val out = Mat()
        rgb.convertTo(out, CvType.CV_8U, gain, bias)
        return out
    }

    /** The intensity below which [percent] of the pixels fall. */
    internal fun percentile(grey: Mat, percent: Double): Double {
        val hist = Mat()
        try {
            Imgproc.calcHist(
                listOf(grey),
                MatOfInt(0),
                Mat(),
                hist,
                MatOfInt(256),
                MatOfFloat(0f, 256f),
            )
            val target = grey.total() * (percent / 100.0)
            var running = 0.0
            val bin = FloatArray(1)
            for (i in 0 until 256) {
                hist.get(i, 0, bin)
                running += bin[0]
                if (running >= target) return i.toDouble()
            }
            return 255.0
        } catch (t: Throwable) {
            return if (percent < 50) 0.0 else 255.0
        } finally {
            hist.release()
        }
    }

    /**
     * Grey-world white balance: assume the average of the scene is neutral and scale each channel
     * to match. Cheap, robust, and exactly right for a page that really is mostly white.
     */
    private fun greyWorldWhiteBalance(rgb: Mat): Mat {
        val channels = ArrayList<Mat>(3)
        Core.split(rgb, channels)
        val means = channels.map { Core.mean(it).`val`[0] }
        val target = means.average()
        if (target <= 0.0) {
            channels.forEach { it.release() }
            return rgb.clone()
        }
        channels.forEachIndexed { index, channel ->
            val gain = (target / means[index].coerceAtLeast(1.0)).coerceIn(0.7, 1.4)
            Core.multiply(channel, Scalar(gain), channel)
        }
        val out = Mat()
        Core.merge(channels, out)
        channels.forEach { it.release() }
        return out
    }

    /**
     * Local contrast in LAB so only lightness is touched — running CLAHE per RGB channel would
     * shift hue, which turns skin tones and coloured stamps unpleasant.
     */
    private fun claheOnLuminance(rgb: Mat, clipLimit: Double): Mat {
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val channels = ArrayList<Mat>(3)
        Core.split(lab, channels)

        val clahe = Imgproc.createCLAHE(clipLimit, Size(8.0, 8.0))
        val equalised = Mat()
        clahe.apply(channels[0], equalised)
        channels[0].release()
        channels[0] = equalised

        Core.merge(channels, lab)
        channels.forEach { it.release() }

        val out = Mat()
        Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2RGB)
        lab.release()
        return out
    }

    private fun scaleSaturation(rgb: Mat, factor: Float): Mat {
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val channels = ArrayList<Mat>(3)
        Core.split(hsv, channels)
        Core.multiply(channels[1], Scalar(factor.toDouble()), channels[1])
        Core.merge(channels, hsv)
        channels.forEach { it.release() }
        val out = Mat()
        Imgproc.cvtColor(hsv, out, Imgproc.COLOR_HSV2RGB)
        hsv.release()
        return out
    }

    /** Unsharp mask — sharpening that adds no ringing at the scale printed text lives at. */
    private fun unsharpMask(rgb: Mat, amount: Double, radius: Double): Mat {
        val blurred = Mat()
        Imgproc.GaussianBlur(rgb, blurred, Size(0.0, 0.0), radius)
        val out = Mat()
        Core.addWeighted(rgb, 1.0 + amount, blurred, -amount, 0.0, out)
        blurred.release()
        return out
    }

    /** Manual controls, applied after the filter. */
    private fun applyAdjustments(rgb: Mat, adjustments: Adjustments): Mat {
        var current = rgb.clone()

        if (adjustments.exposure != 0f || adjustments.brightness != 0f || adjustments.contrast != 0f) {
            // alpha is gain (contrast + exposure), beta is offset (brightness).
            val alpha = (1.0 + adjustments.contrast * 0.6) *
                Math.pow(2.0, adjustments.exposure.toDouble() * 0.8)
            val beta = adjustments.brightness * 60.0
            val out = Mat()
            current.convertTo(out, CvType.CV_8U, alpha, beta)
            current.release()
            current = out
        }

        if (adjustments.saturation != 0f) {
            val out = scaleSaturation(current, 1f + adjustments.saturation)
            current.release()
            current = out
        }

        if (adjustments.sharpness != 0f) {
            val out = if (adjustments.sharpness > 0f) {
                unsharpMask(current, amount = adjustments.sharpness.toDouble() * 1.5, radius = 1.2)
            } else {
                Mat().also {
                    Imgproc.GaussianBlur(current, it, Size(0.0, 0.0), -adjustments.sharpness * 2.0)
                }
            }
            current.release()
            current = out
        }

        return current
    }

    // ------------------------------------------------------------------ fallback

    /**
     * Pure-Android path for devices where the OpenCV native library did not load. It cannot
     * flat-field, but it can still give a usable contrast-stretched result rather than leaving the
     * user with a raw photograph.
     */
    private fun fallbackApply(source: Bitmap, filter: ScanFilter, adjustments: Adjustments): Bitmap {
        val matrix = android.graphics.ColorMatrix()

        when (filter) {
            ScanFilter.GRAYSCALE, ScanFilter.BLACK_WHITE, ScanFilter.DOCUMENT ->
                matrix.setSaturation(0f)
            ScanFilter.MAGIC_COLOR -> matrix.setSaturation(1.3f)
            ScanFilter.AUTO -> matrix.setSaturation(0.85f)
            else -> Unit
        }

        val contrast = 1f + adjustments.contrast * 0.6f +
            if (filter == ScanFilter.BLACK_WHITE || filter == ScanFilter.DOCUMENT) 0.6f else 0.35f
        val brightness = adjustments.brightness * 60f +
            if (filter == ScanFilter.BLACK_WHITE) 24f else 12f
        val translate = -(128f * contrast) + 128f + brightness
        matrix.postConcat(
            android.graphics.ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    internal companion object {
        /** Long edge of the proxy the illumination estimate is built on. */
        const val ESTIMATE_EDGE = 256

        /** Everything above this percentile is treated as bare paper. */
        const val WHITE_PERCENTILE = 88.0

        /** Below this histogram span the frame is blank or blown out; leave it alone. */
        const val MIN_LEVEL_SPAN = 12.0
    }
}
