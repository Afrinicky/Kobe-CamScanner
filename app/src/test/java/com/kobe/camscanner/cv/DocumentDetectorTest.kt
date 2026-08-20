package com.kobe.camscanner.cv

import com.google.common.truth.Truth.assertThat
import com.kobe.camscanner.scanner.DocumentDetector
import com.kobe.camscanner.scanner.OpenCvLoader
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Point

/**
 * Detection accuracy, measured against synthetic photographs whose true corners are known.
 *
 * The first release shipped a detector that could not find a page at all. It was never run against
 * an image before it reached a phone. This suite is the answer to that: every case below is one of
 * the situations that actually defeats a document detector, and each asserts a numeric bound on how
 * far the recovered corners may sit from the truth.
 */
class DocumentDetectorTest {

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

        /**
         * Mean corner error allowed, as a fraction of the image diagonal. 4% of a 720x960 frame is
         * about 48 px — close enough that the perspective correction produces a clean page, and
         * well inside what a user would nudge by hand anyway.
         */
        private const val TOLERANCE = 0.04
    }

    private val detector = DocumentDetector()

    private fun detectAndMeasure(truth: SyntheticDocument.Truth): Double {
        val detection = detector.detectInGrey(truth.image)
        assertThat(detection.found).isTrue()
        val quad = detection.quad!!
        val detected = quad.points.map { it.x to it.y }
        return SyntheticDocument.cornerError(
            detected = detected,
            truth = truth.corners,
            width = truth.image.cols(),
            height = truth.image.rows(),
        )
    }

    @Test
    fun `finds a page lying square on a dark desk`() {
        val truth = SyntheticDocument.render(deskShade = 70.0)
        try {
            assertThat(detectAndMeasure(truth)).isLessThan(TOLERANCE)
        } finally {
            truth.image.release()
        }
    }

    @Test
    fun `finds a page seen at an angle`() {
        // The case the old detector rejected outright: corners well away from 90 degrees.
        val truth = SyntheticDocument.render(
            deskShade = 80.0,
            corners = SyntheticDocument.perspectiveCorners(720, 960, strength = 0.18),
        )
        try {
            assertThat(detectAndMeasure(truth)).isLessThan(TOLERANCE)
        } finally {
            truth.image.release()
        }
    }

    @Test
    fun `finds a page rotated in the frame`() {
        val truth = SyntheticDocument.render(
            deskShade = 75.0,
            corners = SyntheticDocument.rotatedCorners(720, 960, degrees = 14.0),
        )
        try {
            assertThat(detectAndMeasure(truth)).isLessThan(TOLERANCE)
        } finally {
            truth.image.release()
        }
    }

    @Test
    fun `finds a white page on a pale desk`() {
        // Low contrast is what defeats a single-threshold detector; the gradient strategy exists
        // for exactly this frame.
        val truth = SyntheticDocument.render(deskShade = 205.0)
        try {
            assertThat(detectAndMeasure(truth)).isLessThan(TOLERANCE)
        } finally {
            truth.image.release()
        }
    }

    @Test
    fun `finds a page with a shadow across one corner`() {
        val truth = SyntheticDocument.render(deskShade = 85.0, shadowStrength = 0.45)
        try {
            assertThat(detectAndMeasure(truth)).isLessThan(TOLERANCE)
        } finally {
            truth.image.release()
        }
    }

    @Test
    fun `finds the page and not the clutter around it`() {
        val truth = SyntheticDocument.render(deskShade = 95.0, clutter = true)
        try {
            assertThat(detectAndMeasure(truth)).isLessThan(TOLERANCE)
        } finally {
            truth.image.release()
        }
    }

    @Test
    fun `survives sensor noise`() {
        val truth = SyntheticDocument.render(deskShade = 80.0, noise = 8.0)
        try {
            assertThat(detectAndMeasure(truth)).isLessThan(TOLERANCE)
        } finally {
            truth.image.release()
        }
    }

    @Test
    fun `handles the hard case - angled, shadowed and noisy at once`() {
        val truth = SyntheticDocument.render(
            deskShade = 120.0,
            corners = SyntheticDocument.perspectiveCorners(720, 960, strength = 0.14),
            shadowStrength = 0.35,
            noise = 5.0,
        )
        try {
            // A looser bound: everything is working against the detector here, and getting close
            // is enough because the user can nudge the corners.
            assertThat(detectAndMeasure(truth)).isLessThan(0.07)
        } finally {
            truth.image.release()
        }
    }

    @Test
    fun `reports nothing for a frame with no document in it`() {
        val empty = SyntheticDocument.render(
            deskShade = 90.0,
            // Push the "page" almost entirely out of frame so there is nothing plausible to find.
            corners = listOf(
                Point(-600.0, -800.0),
                Point(-540.0, -800.0),
                Point(-540.0, -720.0),
                Point(-600.0, -720.0),
            ),
        )
        try {
            val detection = detector.detectInGrey(empty.image)
            // Either nothing, or something the tracker will discard as too weak to act on.
            if (detection.found) {
                assertThat(detection.confidence).isLessThan(DocumentDetector.LOCK_CONFIDENCE)
            }
        } finally {
            empty.image.release()
        }
    }

    @Test
    fun `detection is fast enough for a live viewfinder`() {
        val truth = SyntheticDocument.render(deskShade = 80.0)
        try {
            // Warm up the JIT and OpenCV's internal buffers first.
            repeat(3) { detector.detectInGrey(truth.image) }

            val start = System.nanoTime()
            val runs = 10
            repeat(runs) { detector.detectInGrey(truth.image) }
            val perFrameMs = (System.nanoTime() - start) / runs / 1_000_000.0

            // A CI runner is not a phone, but an order of magnitude of headroom here means the
            // pipeline is not accidentally quadratic. The analyser throttles to ~70 ms anyway.
            assertThat(perFrameMs).isLessThan(120.0)
        } finally {
            truth.image.release()
        }
    }
}
