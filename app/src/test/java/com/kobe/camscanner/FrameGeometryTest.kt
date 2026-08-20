package com.kobe.camscanner

import com.google.common.truth.Truth.assertThat
import com.kobe.camscanner.domain.model.PointN
import com.kobe.camscanner.domain.model.Quad
import com.kobe.camscanner.scanner.FrameGeometry
import org.junit.Test
import kotlin.math.abs

/**
 * Coordinate mapping between the camera buffer, the upright image and the preview.
 *
 * This is the code that was wrong in the first build: the analyser's rotation was discarded, so a
 * boundary detected in a landscape sensor frame was drawn onto a portrait preview and handed to the
 * warp in the wrong space. Detection appeared not to work at all. These tests exist so it cannot
 * silently break again.
 */
class FrameGeometryTest {

    private fun assertClose(actual: PointN, x: Float, y: Float, tolerance: Float = 1e-4f) {
        assertThat(abs(actual.x - x)).isLessThan(tolerance)
        assertThat(abs(actual.y - y)).isLessThan(tolerance)
    }

    // ------------------------------------------------------------------ rotation

    @Test
    fun `no rotation leaves a point alone`() {
        assertClose(FrameGeometry.rotatePoint(PointN(0.25f, 0.75f), 0), 0.25f, 0.75f)
    }

    @Test
    fun `90 degrees sends the top-left corner to the top-right`() {
        // The origin of a landscape frame ends up on the right edge once turned upright.
        assertClose(FrameGeometry.rotatePoint(PointN(0f, 0f), 90), 1f, 0f)
        assertClose(FrameGeometry.rotatePoint(PointN(1f, 0f), 90), 1f, 1f)
        assertClose(FrameGeometry.rotatePoint(PointN(1f, 1f), 90), 0f, 1f)
        assertClose(FrameGeometry.rotatePoint(PointN(0f, 1f), 90), 0f, 0f)
    }

    @Test
    fun `180 degrees mirrors both axes`() {
        assertClose(FrameGeometry.rotatePoint(PointN(0.2f, 0.3f), 180), 0.8f, 0.7f)
    }

    @Test
    fun `270 degrees is the inverse of 90`() {
        val start = PointN(0.31f, 0.62f)
        val there = FrameGeometry.rotatePoint(start, 90)
        val back = FrameGeometry.rotatePoint(there, 270)
        assertClose(back, start.x, start.y)
    }

    @Test
    fun `the centre is a fixed point of every rotation`() {
        listOf(0, 90, 180, 270).forEach { degrees ->
            assertClose(FrameGeometry.rotatePoint(PointN(0.5f, 0.5f), degrees), 0.5f, 0.5f)
        }
    }

    @Test
    fun `negative and over-wrapped angles normalise`() {
        assertClose(FrameGeometry.rotatePoint(PointN(0.2f, 0.3f), -90), 0.3f, 0.8f)
        assertClose(FrameGeometry.rotatePoint(PointN(0.2f, 0.3f), 450), 0.7f, 0.2f)
    }

    // ------------------------------------------------------------------ upright conversion

    @Test
    fun `rotating a quad re-sorts its corners so the names stay true`() {
        // A page occupying the left half of a landscape sensor frame.
        val analysis = Quad(
            PointN(0.1f, 0.2f),
            PointN(0.5f, 0.2f),
            PointN(0.5f, 0.8f),
            PointN(0.1f, 0.8f),
        )
        val upright = FrameGeometry.analysisToUpright(analysis, 90)

        // Whatever the rotation did, top-left must still be up and to the left of bottom-right.
        assertThat(upright.topLeft.x).isLessThan(upright.bottomRight.x)
        assertThat(upright.topLeft.y).isLessThan(upright.bottomRight.y)
        assertThat(upright.topRight.x).isGreaterThan(upright.topLeft.x)
        assertThat(upright.bottomLeft.y).isGreaterThan(upright.topLeft.y)
    }

    @Test
    fun `a full-frame quad stays full-frame through any rotation`() {
        listOf(0, 90, 180, 270).forEach { degrees ->
            val upright = FrameGeometry.analysisToUpright(Quad.FULL, degrees)
            upright.points.forEach { point ->
                assertThat(point.x).isIn(listOf(0f, 1f))
                assertThat(point.y).isIn(listOf(0f, 1f))
            }
        }
    }

    @Test
    fun `upright size swaps the axes on a quarter turn`() {
        assertThat(FrameGeometry.uprightSize(640, 480, 0)).isEqualTo(640 to 480)
        assertThat(FrameGeometry.uprightSize(640, 480, 90)).isEqualTo(480 to 640)
        assertThat(FrameGeometry.uprightSize(640, 480, 180)).isEqualTo(640 to 480)
        assertThat(FrameGeometry.uprightSize(640, 480, 270)).isEqualTo(480 to 640)
    }

    // ------------------------------------------------------------------ preview mapping

    @Test
    fun `matching aspects need no adjustment`() {
        assertClose(FrameGeometry.uprightToView(PointN(0.3f, 0.7f), 0.75f, 0.75f), 0.3f, 0.7f)
    }

    @Test
    fun `a 4-3 camera frame on a tall phone crops left and right`() {
        // The everyday case: an upright 3:4 analysis frame (0.75) shown on a 9:19.5 screen (~0.46).
        // Covering the screen means matching on height, so the sides are what get cropped.
        val source = 3f / 4f
        val view = 9f / 19.5f

        assertClose(FrameGeometry.uprightToView(PointN(0.5f, 0.5f), source, view), 0.5f, 0.5f)

        val left = FrameGeometry.uprightToView(PointN(0f, 0.5f), source, view)
        val right = FrameGeometry.uprightToView(PointN(1f, 0.5f), source, view)
        assertThat(left.x).isLessThan(0f)
        assertThat(right.x).isGreaterThan(1f)
        // The vertical axis fills exactly, so it is left alone.
        assertThat(left.y).isEqualTo(0.5f)
        assertClose(FrameGeometry.uprightToView(PointN(0.5f, 0f), source, view), 0.5f, 0f)
    }

    @Test
    fun `a tall frame in a wide view crops top and bottom`() {
        // The mirror case, which exercises the other branch.
        val source = 9f / 19.5f
        val view = 3f / 4f

        val top = FrameGeometry.uprightToView(PointN(0.5f, 0f), source, view)
        val bottom = FrameGeometry.uprightToView(PointN(0.5f, 1f), source, view)
        assertThat(top.y).isLessThan(0f)
        assertThat(bottom.y).isGreaterThan(1f)
        assertThat(top.x).isEqualTo(0.5f)
    }

    @Test
    fun `a wide frame on a square view crops left and right`() {
        val source = 16f / 9f
        val view = 1f

        val left = FrameGeometry.uprightToView(PointN(0f, 0.5f), source, view)
        val right = FrameGeometry.uprightToView(PointN(1f, 0.5f), source, view)
        assertThat(left.x).isLessThan(0f)
        assertThat(right.x).isGreaterThan(1f)
        assertThat(left.y).isEqualTo(0.5f)
    }

    @Test
    fun `view mapping round-trips`() {
        val source = 3f / 4f
        val view = 9f / 19.5f
        listOf(
            PointN(0.1f, 0.2f),
            PointN(0.5f, 0.5f),
            PointN(0.93f, 0.87f),
        ).forEach { start ->
            val there = FrameGeometry.uprightToView(start, source, view)
            val back = FrameGeometry.viewToUpright(there, source, view)
            assertClose(back, start.x, start.y, tolerance = 1e-3f)
        }
    }

    @Test
    fun `degenerate aspects are passed through rather than dividing by zero`() {
        assertClose(FrameGeometry.uprightToView(PointN(0.4f, 0.6f), 0f, 1f), 0.4f, 0.6f)
        assertClose(FrameGeometry.uprightToView(PointN(0.4f, 0.6f), 1f, 0f), 0.4f, 0.6f)
    }

    // ------------------------------------------------------------------ the regression itself

    @Test
    fun `a portrait phone maps a page in the lower sensor half to the left of the screen`() {
        // The real failure case. Phone upright, sensor delivers 640x480 landscape with
        // rotationDegrees = 90. The page sits in the *lower* half of the sensor frame, which after
        // the quarter turn is the *left* half of what the user sees.
        val analysis = Quad(
            PointN(0.15f, 0.55f),
            PointN(0.85f, 0.55f),
            PointN(0.85f, 0.95f),
            PointN(0.15f, 0.95f),
        )

        val upright = FrameGeometry.analysisToUpright(analysis, 90)
        val centreX = upright.points.map { it.x }.average().toFloat()

        assertThat(centreX).isLessThan(0.5f)

        // Without the rotation the centre would have been on the other side, which is precisely
        // the bug: the overlay was drawn on the opposite side of the screen from the page.
        val unrotatedCentreX = analysis.points.map { it.x }.average().toFloat()
        assertThat(unrotatedCentreX).isGreaterThan(0.4f)
        assertThat(abs(unrotatedCentreX - centreX)).isGreaterThan(0.2f)
    }
}
