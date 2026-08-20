package com.kobe.camscanner.cv

import com.google.common.truth.Truth.assertThat
import com.kobe.camscanner.domain.model.ScanFilter
import com.kobe.camscanner.scanner.ImageEnhancer
import com.kobe.camscanner.scanner.OpenCvLoader
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc

/**
 * Scan quality, measured rather than asserted.
 *
 * The first release washed pages out: "whitening" was a plain gain, so brightening the paper
 * brightened the text with it, and nothing ever reached true black. These tests describe what a
 * scan is actually supposed to look like — paper near white, ink near black, illumination flat —
 * and check the numbers.
 */
class ImageEnhancerTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadNatives() {
            nu.pattern.OpenCV.loadLocally()
            OpenCvLoader.markAvailableForTesting(true)
        }

        @JvmStatic
        @AfterClass
        fun unload() {
            OpenCvLoader.markAvailableForTesting(false)
        }
    }

    private val enhancer = ImageEnhancer()

    /** A flat-lit page: light paper, dark text, as three-channel RGB. */
    private fun page(shadow: Double = 0.0, paper: Double = 210.0): Mat {
        val truth = SyntheticDocument.render(
            width = 600,
            height = 800,
            deskShade = 60.0,
            corners = listOf(
                org.opencv.core.Point(0.0, 0.0),
                org.opencv.core.Point(599.0, 0.0),
                org.opencv.core.Point(599.0, 799.0),
                org.opencv.core.Point(0.0, 799.0),
            ),
            shadowStrength = shadow,
        )
        // The synthetic page is drawn at 244; scale it to the requested paper level so a dull,
        // under-exposed capture can be simulated.
        val scaled = Mat()
        truth.image.convertTo(scaled, -1, paper / 244.0, 0.0)
        truth.image.release()

        val rgb = Mat()
        Imgproc.cvtColor(scaled, rgb, Imgproc.COLOR_GRAY2RGB)
        scaled.release()
        return rgb
    }

    private fun grey(rgb: Mat): Mat {
        val g = Mat()
        Imgproc.cvtColor(rgb, g, Imgproc.COLOR_RGB2GRAY)
        return g
    }

    /** Mean of the brightest decile — how white the paper reads. */
    private fun paperLevel(rgb: Mat): Double {
        val g = grey(rgb)
        val value = enhancer.percentile(g, 95.0)
        g.release()
        return value
    }

    /** Mean of the darkest few percent — how black the ink reads. */
    private fun inkLevel(rgb: Mat): Double {
        val g = grey(rgb)
        val value = enhancer.percentile(g, 2.0)
        g.release()
        return value
    }

    private fun stdDev(mat: Mat): Double {
        val mean = MatOfDouble()
        val sigma = MatOfDouble()
        Core.meanStdDev(mat, mean, sigma)
        val value = sigma.toArray()[0]
        mean.release()
        sigma.release()
        return value
    }

    @Test
    fun `the auto filter drives paper to white and ink towards black`() {
        val source = page(paper = 200.0)
        val result = enhancer.runFilter(source, ScanFilter.AUTO)
        try {
            // Paper should read as paper, not as light grey.
            assertThat(paperLevel(result)).isAtLeast(235.0)
            // Ink must be genuinely dark. The old pipeline left it around mid-grey.
            assertThat(inkLevel(result)).isAtMost(70.0)
            // And the separation must be wider than it started.
            val before = paperLevel(source) - inkLevel(source)
            val after = paperLevel(result) - inkLevel(result)
            assertThat(after).isGreaterThan(before)
        } finally {
            source.release()
            result.release()
        }
    }

    @Test
    fun `an under-exposed capture is still brought up to white`() {
        // A dim shot: paper only reaching 150. This is where a fixed white point fails and a
        // percentile-based one does not.
        val source = page(paper = 150.0)
        val result = enhancer.runFilter(source, ScanFilter.AUTO)
        try {
            assertThat(paperLevel(source)).isLessThan(180.0)
            assertThat(paperLevel(result)).isAtLeast(230.0)
        } finally {
            source.release()
            result.release()
        }
    }

    @Test
    fun `flat-fielding removes a shadow gradient`() {
        val source = page(shadow = 0.5)
        val flattened = enhancer.flatField(source)
        try {
            // Compare the mean brightness of the left and right thirds. A shadow makes them
            // differ; flat-fielding should bring them back together.
            fun meanOfColumnBand(mat: Mat, fromFraction: Double, toFraction: Double): Double {
                val g = grey(mat)
                val band = g.submat(
                    0,
                    g.rows(),
                    (g.cols() * fromFraction).toInt(),
                    (g.cols() * toFraction).toInt(),
                )
                val value = Core.mean(band).`val`[0]
                band.release()
                g.release()
                return value
            }

            val beforeGap = kotlin.math.abs(
                meanOfColumnBand(source, 0.0, 0.25) - meanOfColumnBand(source, 0.75, 1.0),
            )
            val afterGap = kotlin.math.abs(
                meanOfColumnBand(flattened, 0.0, 0.25) - meanOfColumnBand(flattened, 0.75, 1.0),
            )

            assertThat(beforeGap).isGreaterThan(12.0)
            assertThat(afterGap).isLessThan(beforeGap / 2.0)
        } finally {
            source.release()
            flattened.release()
        }
    }

    @Test
    fun `levels stretching widens the histogram`() {
        val source = page(paper = 170.0)
        val stretched = enhancer.stretchLevels(source, 4.0, 88.0)
        try {
            assertThat(stdDev(grey(stretched))).isGreaterThan(stdDev(grey(source)))
        } finally {
            source.release()
            stretched.release()
        }
    }

    @Test
    fun `a blank frame is left alone rather than amplified into noise`() {
        // Uniform grey with nothing in it. Stretching this would turn sensor noise into a wall of
        // speckle, so the guard should decline to act.
        val flat = Mat(200, 200, org.opencv.core.CvType.CV_8UC3, org.opencv.core.Scalar(128.0, 128.0, 128.0))
        val result = enhancer.stretchLevels(flat, 4.0, 88.0)
        try {
            assertThat(stdDev(grey(result))).isLessThan(3.0)
        } finally {
            flat.release()
            result.release()
        }
    }

    @Test
    fun `black and white produces a genuinely bi-level image`() {
        val source = page(shadow = 0.35)
        val result = enhancer.runFilter(source, ScanFilter.BLACK_WHITE)
        try {
            val g = grey(result)
            // Nearly every pixel should sit at one end or the other.
            val total = g.total().toDouble()
            var extremes = 0.0
            val hist = Mat()
            Imgproc.calcHist(
                listOf(g),
                org.opencv.core.MatOfInt(0),
                Mat(),
                hist,
                org.opencv.core.MatOfInt(256),
                org.opencv.core.MatOfFloat(0f, 256f),
            )
            val bin = FloatArray(1)
            for (i in 0 until 256) {
                if (i < 20 || i > 235) {
                    hist.get(i, 0, bin)
                    extremes += bin[0]
                }
            }
            hist.release()
            g.release()
            assertThat(extremes / total).isAtLeast(0.95)
        } finally {
            source.release()
            result.release()
        }
    }

    @Test
    fun `the original filter changes nothing`() {
        val source = page()
        val result = enhancer.runFilter(source, ScanFilter.ORIGINAL)
        try {
            val diff = Mat()
            Core.absdiff(source, result, diff)
            assertThat(Core.sumElems(diff).`val`[0]).isEqualTo(0.0)
            diff.release()
        } finally {
            source.release()
            result.release()
        }
    }

    @Test
    fun `enhancement is fast enough not to stall a capture`() {
        // A realistic page size rather than the small fixtures above.
        val big = Mat()
        val source = page()
        Imgproc.resize(source, big, org.opencv.core.Size(2000.0, 2667.0))
        try {
            repeat(2) { enhancer.runFilter(big, ScanFilter.AUTO).release() }
            val start = System.nanoTime()
            enhancer.runFilter(big, ScanFilter.AUTO).release()
            val ms = (System.nanoTime() - start) / 1_000_000.0

            // The old full-resolution 151-tap blur took seconds. Estimating illumination on a
            // downscaled copy is what makes this bounded.
            assertThat(ms).isLessThan(2500.0)
        } finally {
            source.release()
            big.release()
        }
    }
}
