package com.kobe.camscanner.scanner

import com.kobe.camscanner.domain.model.PointN
import com.kobe.camscanner.domain.model.Quad
import kotlin.math.abs

/**
 * Decides when the phone has been held still enough over a detected document to fire the shutter
 * on its own (SDS 8: "Document detected" -> "Hold steady" -> "Capture").
 *
 * Two safeguards keep auto-capture from being annoying. The quad is smoothed before it is drawn, so
 * the on-screen boundary never jitters even while the raw detection does; and the capture only
 * fires after the corners have stayed within [MOVEMENT_TOLERANCE] for [HOLD_MILLIS], which is long
 * enough that a user sweeping the phone across a desk does not trigger a frame mid-sweep.
 */
class StabilityTracker(
    private val holdMillis: Long = HOLD_MILLIS,
    private val movementTolerance: Float = MOVEMENT_TOLERANCE,
) {
    private var smoothed: Quad? = null
    private var steadySince: Long = 0L
    private var lastSeenAt: Long = 0L
    private var alreadyFired = false

    /** Feeds a raw detection and returns what the viewfinder should show and do. */
    fun update(
        detection: DocumentDetector.Detection,
        now: Long = System.currentTimeMillis(),
    ): State {
        val raw = detection.quad
        if (raw == null || detection.confidence < MIN_CONFIDENCE) {
            // Hold the last boundary briefly so a single dropped frame does not make the overlay
            // flicker; only then declare the document lost.
            if (smoothed != null && now - lastSeenAt < GRACE_MILLIS) {
                return State(smoothed, Phase.SEARCHING, progress = 0f)
            }
            reset()
            return State(null, Phase.SEARCHING, progress = 0f)
        }

        lastSeenAt = now
        val previous = smoothed
        val next = if (previous == null) raw else lerp(previous, raw, SMOOTHING)
        smoothed = next

        val movement = if (previous == null) Float.MAX_VALUE else maxCornerShift(previous, raw)
        if (movement > movementTolerance) {
            steadySince = now
            alreadyFired = false
            return State(next, Phase.DETECTED, progress = 0f)
        }

        if (steadySince == 0L) steadySince = now
        val held = now - steadySince
        val progress = (held.toFloat() / holdMillis).coerceIn(0f, 1f)

        return when {
            alreadyFired -> State(next, Phase.DETECTED, progress = 1f)
            progress >= 1f -> {
                alreadyFired = true
                State(next, Phase.CAPTURE, progress = 1f)
            }
            else -> State(next, Phase.HOLD_STEADY, progress = progress)
        }
    }

    /** Called after a capture so the next page starts from a clean slate. */
    fun reset() {
        smoothed = null
        steadySince = 0L
        alreadyFired = false
    }

    private fun lerp(from: Quad, to: Quad, t: Float): Quad = Quad(
        lerp(from.topLeft, to.topLeft, t),
        lerp(from.topRight, to.topRight, t),
        lerp(from.bottomRight, to.bottomRight, t),
        lerp(from.bottomLeft, to.bottomLeft, t),
    )

    private fun lerp(from: PointN, to: PointN, t: Float) =
        PointN(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)

    private fun maxCornerShift(a: Quad, b: Quad): Float =
        a.points.zip(b.points).maxOf { (p, q) -> maxOf(abs(p.x - q.x), abs(p.y - q.y)) }

    /** What the viewfinder is currently doing. Drives the on-screen coaching text. */
    enum class Phase { SEARCHING, DETECTED, HOLD_STEADY, CAPTURE }

    data class State(
        val quad: Quad?,
        val phase: Phase,
        val progress: Float,
    ) {
        val shouldCapture: Boolean get() = phase == Phase.CAPTURE
    }

    companion object {
        /** How long the corners must stay put before auto-capture fires. */
        const val HOLD_MILLIS = 700L

        /**
         * Maximum per-corner movement, in fractions of the frame, still counted as "steady".
         * Loose enough for a handheld phone: at 0.022 an ordinary steady hand never qualified.
         */
        const val MOVEMENT_TOLERANCE = 0.035f

        /** Weight of each new detection in the smoothed boundary. */
        private const val SMOOTHING = 0.35f

        /**
         * Detections below this confidence do not count as a document at all. Kept just under the
         * detector's own reporting floor so anything it bothers to return is shown to the user.
         */
        private const val MIN_CONFIDENCE = 0.30f

        /** How long a boundary stays on screen after detection drops out. */
        private const val GRACE_MILLIS = 400L
    }
}
