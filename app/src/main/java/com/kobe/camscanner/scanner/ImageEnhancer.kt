package com.kobe.camscanner.scanner

import android.graphics.Bitmap
import com.kobe.camscanner.domain.model.Adjustments
import com.kobe.camscanner.domain.model.ScanFilter
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Image enhancement (SDS 12 and 13).
 *
 * The heart of "scanner quality from an ordinary photograph" is [normaliseBackground]: dividing the
 * page by a heavily blurred copy of itself. That blurred copy *is* the illumination field, so the
 * division cancels the shadow of the phone, the lamp falloff, and the grey cast of a photocopy in
 * one step — and it does so without the blotching that a global threshold produces. Everything
 * else here is finishing.
 */
@Singleton
class ImageEnhancer @Inject constructor() {

    /**
     * The full automatic pass from SDS 13: white balance, contrast, background normalisation,
     * shadow reduction, denoise, sharpen, whitening.
     */
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

            work = when (filter) {
                ScanFilter.ORIGINAL -> rgb.clone()
                ScanFilter.AUTO -> autoPipeline(rgb)
                ScanFilter.COLOR -> colourPipeline(rgb, boost = false)
                ScanFilter.MAGIC_COLOR -> colourPipeline(rgb, boost = true)
                ScanFilter.GRAYSCALE -> greyscalePipeline(rgb)
                ScanFilter.BLACK_WHITE -> blackWhitePipeline(rgb)
                ScanFilter.DOCUMENT -> documentPipeline(rgb)
                ScanFilter.PHOTO -> photoPipeline(rgb)
            }
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

    // ------------------------------------------------------------------ pipelines

    /** Auto: the default every scan lands on. Balanced for printed text on white paper. */
    private fun autoPipeline(rgb: Mat): Mat {
        val balanced = greyWorldWhiteBalance(rgb)
        val flattened = normaliseBackground(balanced, blurRadiusRatio = 0.06)
        balanced.release()
        val toned = claheOnLuminance(flattened, clipLimit = 2.0)
        flattened.release()
        val whitened = whitenPaper(toned, whitePoint = 0.90f)
        toned.release()
        val sharpened = unsharpMask(whitened, amount = 0.85, radius = 1.4)
        whitened.release()
        return sharpened
    }

    /** Colour: keeps the photograph honest, just lifts the illumination. */
    private fun colourPipeline(rgb: Mat, boost: Boolean): Mat {
        val balanced = greyWorldWhiteBalance(rgb)
        val flattened = normaliseBackground(balanced, blurRadiusRatio = 0.08)
        balanced.release()
        val toned = claheOnLuminance(flattened, clipLimit = if (boost) 3.0 else 1.8)
        flattened.release()
        if (!boost) return toned
        // "Magic colour" is the punchier variant: more saturation and a firmer white point,
        // which is what makes a printed brochure or a highlighted page pop.
        val saturated = scaleSaturation(toned, 1.28f)
        toned.release()
        val whitened = whitenPaper(saturated, whitePoint = 0.93f)
        saturated.release()
        val sharpened = unsharpMask(whitened, amount = 1.0, radius = 1.2)
        whitened.release()
        return sharpened
    }

    private fun greyscalePipeline(rgb: Mat): Mat {
        val flattened = normaliseBackground(rgb, blurRadiusRatio = 0.06)
        val grey = Mat()
        Imgproc.cvtColor(flattened, grey, Imgproc.COLOR_RGB2GRAY)
        flattened.release()
        val clahe: CLAHE = Imgproc.createCLAHE(2.2, Size(8.0, 8.0))
        val equalised = Mat()
        clahe.apply(grey, equalised)
        grey.release()
        val out = Mat()
        Imgproc.cvtColor(equalised, out, Imgproc.COLOR_GRAY2RGB)
        equalised.release()
        return out
    }

    /**
     * Black & white via adaptive thresholding after background normalisation. Doing both matters:
     * normalisation removes the shadow, the adaptive threshold then only has to separate ink from
     * paper, which it does cleanly even on a page photographed under a desk lamp.
     */
    private fun blackWhitePipeline(rgb: Mat): Mat {
        val flattened = normaliseBackground(rgb, blurRadiusRatio = 0.05)
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
            12.0,
        )
        denoised.release()

        val out = Mat()
        Imgproc.cvtColor(binary, out, Imgproc.COLOR_GRAY2RGB)
        binary.release()
        return out
    }

    /** Document: near-monochrome but keeps stamps, signatures and highlighter visible. */
    private fun documentPipeline(rgb: Mat): Mat {
        val balanced = greyWorldWhiteBalance(rgb)
        val flattened = normaliseBackground(balanced, blurRadiusRatio = 0.05)
        balanced.release()
        val desaturated = scaleSaturation(flattened, 0.45f)
        flattened.release()
        val toned = claheOnLuminance(desaturated, clipLimit = 2.6)
        desaturated.release()
        val whitened = whitenPaper(toned, whitePoint = 0.86f)
        toned.release()
        val sharpened = unsharpMask(whitened, amount = 1.1, radius = 1.1)
        whitened.release()
        return sharpened
    }

    /** Photo: no page assumptions. Just a gentle tone curve — used for whiteboards and pictures. */
    private fun photoPipeline(rgb: Mat): Mat {
        val balanced = greyWorldWhiteBalance(rgb)
        val toned = claheOnLuminance(balanced, clipLimit = 1.6)
        balanced.release()
        return toned
    }

    // ------------------------------------------------------------------ operators

    /**
     * Divides the image by a blurred copy of itself to cancel uneven illumination.
     * This is the shadow-removal and background-cleanup step of SDS 13 in a single operation.
     */
    private fun normaliseBackground(rgb: Mat, blurRadiusRatio: Double): Mat {
        val radius = (rgb.cols().coerceAtLeast(rgb.rows()) * blurRadiusRatio).roundToInt()
        val kernel = ((radius or 1).coerceIn(9, 151)).toDouble()

        val background = Mat()
        Imgproc.GaussianBlur(rgb, background, Size(kernel, kernel), 0.0)

        val srcF = Mat()
        val bgF = Mat()
        rgb.convertTo(srcF, CvType.CV_32F)
        background.convertTo(bgF, CvType.CV_32F)
        background.release()

        // Guard against divide-by-zero in genuinely black regions.
        Core.add(bgF, Scalar(1.0, 1.0, 1.0), bgF)

        val ratio = Mat()
        Core.divide(srcF, bgF, ratio)
        srcF.release()
        bgF.release()

        val out = Mat()
        ratio.convertTo(out, CvType.CV_8U, 255.0)
        ratio.release()
        return out
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

    /**
     * Paper whitening (SDS 13.7): everything above [whitePoint] of full scale is pulled to pure
     * white and the remaining range is stretched back out. That is what removes the last grey haze
     * and makes the result read as "scanned" rather than "photographed".
     */
    private fun whitenPaper(rgb: Mat, whitePoint: Float): Mat {
        val cut = (whitePoint * 255f).toDouble()
        val scale = 255.0 / cut
        val out = Mat()
        rgb.convertTo(out, CvType.CV_8U, scale, 0.0)
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

    /** Manual controls from SDS 12, applied after the filter. */
    private fun applyAdjustments(rgb: Mat, adjustments: Adjustments): Mat {
        var current = rgb.clone()

        if (adjustments.exposure != 0f || adjustments.brightness != 0f || adjustments.contrast != 0f) {
            // alpha is gain (contrast + exposure), beta is offset (brightness).
            val alpha = (1.0 + adjustments.contrast * 0.6) * Math.pow(2.0, adjustments.exposure.toDouble() * 0.8)
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
                Mat().also { Imgproc.GaussianBlur(current, it, Size(0.0, 0.0), -adjustments.sharpness * 2.0) }
            }
            current.release()
            current = out
        }

        return current
    }

    // ------------------------------------------------------------------ fallback

    /**
     * Pure-Android path for devices where the OpenCV native library did not load. It cannot do
     * background normalisation, but it can still give a usable greyscale/contrast result rather
     * than leaving the user with a raw photograph.
     */
    private fun fallbackApply(source: Bitmap, filter: ScanFilter, adjustments: Adjustments): Bitmap {
        val matrix = android.graphics.ColorMatrix()

        when (filter) {
            ScanFilter.GRAYSCALE, ScanFilter.BLACK_WHITE, ScanFilter.DOCUMENT ->
                matrix.setSaturation(0f)
            ScanFilter.MAGIC_COLOR -> matrix.setSaturation(1.3f)
            else -> Unit
        }

        val contrast = 1f + adjustments.contrast * 0.6f +
            if (filter == ScanFilter.BLACK_WHITE || filter == ScanFilter.DOCUMENT) 0.5f else 0.15f
        val brightness = adjustments.brightness * 60f +
            if (filter == ScanFilter.BLACK_WHITE) 20f else 8f
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
}
