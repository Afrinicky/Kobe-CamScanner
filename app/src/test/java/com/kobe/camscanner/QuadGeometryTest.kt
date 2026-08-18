package com.kobe.camscanner

import com.google.common.truth.Truth.assertThat
import com.kobe.camscanner.domain.model.PointN
import com.kobe.camscanner.domain.model.Quad
import org.junit.Test

/**
 * Corner ordering is the one piece of geometry the whole scanner depends on: OpenCV returns four
 * points with no guaranteed winding, and the homography is wrong — silently, producing a mirrored
 * or sheared page — if they are not sorted into TL/TR/BR/BL first.
 */
class QuadGeometryTest {

    @Test
    fun `orders clockwise points into corners`() {
        val quad = Quad.fromUnordered(
            listOf(
                PointN(0.1f, 0.1f),
                PointN(0.9f, 0.15f),
                PointN(0.85f, 0.9f),
                PointN(0.15f, 0.85f),
            ),
        )

        assertThat(quad).isNotNull()
        assertThat(quad!!.topLeft).isEqualTo(PointN(0.1f, 0.1f))
        assertThat(quad.topRight).isEqualTo(PointN(0.9f, 0.15f))
        assertThat(quad.bottomRight).isEqualTo(PointN(0.85f, 0.9f))
        assertThat(quad.bottomLeft).isEqualTo(PointN(0.15f, 0.85f))
    }

    @Test
    fun `orders shuffled points into the same corners`() {
        val points = listOf(
            PointN(0.85f, 0.9f),
            PointN(0.1f, 0.1f),
            PointN(0.15f, 0.85f),
            PointN(0.9f, 0.15f),
        )

        val quad = Quad.fromUnordered(points)

        assertThat(quad).isNotNull()
        assertThat(quad!!.topLeft).isEqualTo(PointN(0.1f, 0.1f))
        assertThat(quad.bottomRight).isEqualTo(PointN(0.85f, 0.9f))
    }

    @Test
    fun `rejects a point count that is not four`() {
        assertThat(Quad.fromUnordered(listOf(PointN(0f, 0f), PointN(1f, 1f)))).isNull()
    }

    @Test
    fun `rejects a degenerate quad where one point serves two corners`() {
        // All four points on one side of the centroid: no valid TL/TR/BR/BL assignment exists.
        val quad = Quad.fromUnordered(
            listOf(
                PointN(0.1f, 0.1f),
                PointN(0.1f, 0.1f),
                PointN(0.1f, 0.1f),
                PointN(0.1f, 0.1f),
            ),
        )
        assertThat(quad).isNull()
    }

    @Test
    fun `full quad covers the whole frame`() {
        assertThat(Quad.FULL.points).containsExactly(
            PointN(0f, 0f),
            PointN(1f, 0f),
            PointN(1f, 1f),
            PointN(0f, 1f),
        ).inOrder()
    }

    @Test
    fun `scaling maps normalised corners onto pixel space`() {
        val scaled = Quad.FULL.scaled(width = 800f, height = 600f)
        assertThat(scaled).containsExactly(
            0f to 0f,
            800f to 0f,
            800f to 600f,
            0f to 600f,
        ).inOrder()
    }

    @Test
    fun `clamping keeps a dragged corner inside the frame`() {
        assertThat(PointN(-0.4f, 1.7f).clamped()).isEqualTo(PointN(0f, 1f))
    }
}
