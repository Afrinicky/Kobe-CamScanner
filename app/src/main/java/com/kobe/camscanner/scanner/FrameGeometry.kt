package com.kobe.camscanner.scanner

import com.kobe.camscanner.domain.model.PointN
import com.kobe.camscanner.domain.model.Quad

/**
 * The coordinate plumbing between the camera and the screen.
 *
 * Three spaces are in play at once and confusing any two of them puts the detected boundary in the
 * wrong place — which is exactly what happened before this existed:
 *
 *  1. **Analysis space** — the raw `ImageAnalysis` buffer. It arrives in the *sensor's* orientation,
 *     so a phone held upright typically delivers a 640x480 landscape frame with
 *     `rotationDegrees = 90`. Detection happens here.
 *  2. **Upright space** — the same image rotated by `rotationDegrees` so it matches what the user
 *     is looking at. The full-resolution capture is already upright (ImageCapture writes the
 *     orientation into EXIF and the decoder applies it), so this is the space a quad must be in
 *     before it is handed to the perspective warp.
 *  3. **View space** — the `PreviewView` after its FILL_CENTER scale-and-crop. This is the only
 *     space the on-screen overlay may be drawn in.
 *
 * Everything here is normalised (0..1) and free of Android types, so it is all directly testable.
 */
object FrameGeometry {

    /**
     * Rotates a normalised point clockwise by [degrees] (0, 90, 180 or 270).
     *
     * Rotating an image 90° clockwise sends the pixel at (x, y) of a W x H image to
     * (H - 1 - y, x) of the resulting H x W image, which in normalised terms is (1 - v, u).
     */
    fun rotatePoint(point: PointN, degrees: Int): PointN = when (normalise(degrees)) {
        90 -> PointN(1f - point.y, point.x)
        180 -> PointN(1f - point.x, 1f - point.y)
        270 -> PointN(point.y, 1f - point.x)
        else -> point
    }

    /**
     * Converts a quad detected in analysis space into upright space.
     *
     * The corners are re-sorted afterwards: rotating by 90° turns the top-left corner into the
     * top-right one, so the tuple's *names* would otherwise stop matching its geometry, and every
     * downstream homography depends on that ordering being true.
     */
    fun analysisToUpright(quad: Quad, rotationDegrees: Int): Quad {
        val rotated = quad.points.map { rotatePoint(it, rotationDegrees) }
        return Quad.fromUnordered(rotated) ?: Quad(rotated[0], rotated[1], rotated[2], rotated[3])
    }

    /** Upright pixel dimensions of a frame delivered as [width] x [height] at [rotationDegrees]. */
    fun uprightSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (normalise(rotationDegrees) % 180 == 90) height to width else width to height

    /**
     * Maps a point from upright space into view space for a FILL_CENTER preview.
     *
     * FILL_CENTER scales the frame by whichever factor makes it *cover* the view, then centres it,
     * so one axis is cropped. Points on the cropped part legitimately fall outside 0..1; the caller
     * decides whether to clamp (the overlay does not, so a boundary running off-screen is drawn
     * running off-screen rather than being bent back into frame).
     *
     * @param sourceAspect  width / height of the upright frame
     * @param viewAspect    width / height of the PreviewView
     */
    fun uprightToView(point: PointN, sourceAspect: Float, viewAspect: Float): PointN {
        if (sourceAspect <= 0f || viewAspect <= 0f) return point
        return if (sourceAspect > viewAspect) {
            // Source is relatively wider: matched on height, cropped left and right.
            val scale = sourceAspect / viewAspect
            PointN(point.x * scale - (scale - 1f) / 2f, point.y)
        } else {
            // Source is relatively taller: matched on width, cropped top and bottom.
            val scale = viewAspect / sourceAspect
            PointN(point.x, point.y * scale - (scale - 1f) / 2f)
        }
    }

    fun uprightToView(quad: Quad, sourceAspect: Float, viewAspect: Float): Quad {
        val mapped = quad.points.map { uprightToView(it, sourceAspect, viewAspect) }
        return Quad(mapped[0], mapped[1], mapped[2], mapped[3])
    }

    /** Inverse of [uprightToView]; used when a touch on the preview has to become a frame position. */
    fun viewToUpright(point: PointN, sourceAspect: Float, viewAspect: Float): PointN {
        if (sourceAspect <= 0f || viewAspect <= 0f) return point
        return if (sourceAspect > viewAspect) {
            val scale = sourceAspect / viewAspect
            PointN((point.x + (scale - 1f) / 2f) / scale, point.y)
        } else {
            val scale = viewAspect / sourceAspect
            PointN(point.x, (point.y + (scale - 1f) / 2f) / scale)
        }
    }

    private fun normalise(degrees: Int): Int = ((degrees % 360) + 360) % 360
}
