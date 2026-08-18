package com.kobe.camscanner

import com.google.common.truth.Truth.assertThat
import com.kobe.camscanner.domain.model.PointN
import com.kobe.camscanner.domain.model.Quad
import com.kobe.camscanner.scanner.DocumentDetector
import com.kobe.camscanner.scanner.StabilityTracker
import org.junit.Test

/**
 * Auto-capture behaviour.
 *
 * These are the rules a user actually feels: it must not fire while the phone is moving, it must
 * fire once the phone has been still long enough, and it must not fire twice for one page.
 */
class StabilityTrackerTest {

    private fun quad(offset: Float = 0f) = Quad(
        PointN(0.1f + offset, 0.1f + offset),
        PointN(0.9f + offset, 0.1f + offset),
        PointN(0.9f + offset, 0.9f + offset),
        PointN(0.1f + offset, 0.9f + offset),
    )

    private fun detection(offset: Float = 0f, confidence: Float = 0.8f) =
        DocumentDetector.Detection(quad(offset), confidence)

    @Test
    fun `reports searching when nothing is detected`() {
        val tracker = StabilityTracker()
        val state = tracker.update(DocumentDetector.Detection.NONE, now = 0L)

        assertThat(state.phase).isEqualTo(StabilityTracker.Phase.SEARCHING)
        assertThat(state.quad).isNull()
        assertThat(state.shouldCapture).isFalse()
    }

    @Test
    fun `low confidence detections do not count as a document`() {
        val tracker = StabilityTracker()
        val state = tracker.update(detection(confidence = 0.2f), now = 0L)

        assertThat(state.phase).isEqualTo(StabilityTracker.Phase.SEARCHING)
    }

    @Test
    fun `first sighting reports detected but never captures`() {
        val tracker = StabilityTracker()
        val state = tracker.update(detection(), now = 0L)

        assertThat(state.phase).isEqualTo(StabilityTracker.Phase.DETECTED)
        assertThat(state.shouldCapture).isFalse()
        assertThat(state.quad).isNotNull()
    }

    @Test
    fun `holding steady progresses and then captures`() {
        val tracker = StabilityTracker(holdMillis = 500L)
        tracker.update(detection(), now = 0L)
        tracker.update(detection(), now = 10L)

        val midway = tracker.update(detection(), now = 260L)
        assertThat(midway.phase).isEqualTo(StabilityTracker.Phase.HOLD_STEADY)
        assertThat(midway.progress).isGreaterThan(0f)
        assertThat(midway.progress).isLessThan(1f)

        val fired = tracker.update(detection(), now = 600L)
        assertThat(fired.shouldCapture).isTrue()
    }

    @Test
    fun `movement resets the hold timer`() {
        val tracker = StabilityTracker(holdMillis = 500L)
        tracker.update(detection(), now = 0L)
        tracker.update(detection(), now = 10L)
        tracker.update(detection(), now = 300L)

        // A large jump: the phone moved.
        val moved = tracker.update(detection(offset = 0.09f), now = 320L)
        assertThat(moved.phase).isEqualTo(StabilityTracker.Phase.DETECTED)
        assertThat(moved.progress).isEqualTo(0f)

        // The clock restarts from the moment of the move, so the old deadline no longer applies.
        val afterMove = tracker.update(detection(offset = 0.09f), now = 600L)
        assertThat(afterMove.shouldCapture).isFalse()
    }

    @Test
    fun `does not fire twice for one steady page`() {
        val tracker = StabilityTracker(holdMillis = 300L)
        tracker.update(detection(), now = 0L)
        tracker.update(detection(), now = 10L)

        assertThat(tracker.update(detection(), now = 400L).shouldCapture).isTrue()
        assertThat(tracker.update(detection(), now = 500L).shouldCapture).isFalse()
        assertThat(tracker.update(detection(), now = 900L).shouldCapture).isFalse()
    }

    @Test
    fun `resetting allows the next page to capture`() {
        val tracker = StabilityTracker(holdMillis = 300L)
        tracker.update(detection(), now = 0L)
        tracker.update(detection(), now = 10L)
        assertThat(tracker.update(detection(), now = 400L).shouldCapture).isTrue()

        tracker.reset()
        tracker.update(detection(), now = 1000L)
        tracker.update(detection(), now = 1010L)
        assertThat(tracker.update(detection(), now = 1400L).shouldCapture).isTrue()
    }

    @Test
    fun `a single dropped frame keeps the boundary on screen`() {
        val tracker = StabilityTracker()
        tracker.update(detection(), now = 0L)

        val dropped = tracker.update(DocumentDetector.Detection.NONE, now = 100L)
        assertThat(dropped.quad).isNotNull()
        assertThat(dropped.phase).isEqualTo(StabilityTracker.Phase.SEARCHING)

        // Once the document has genuinely been gone for a while, the boundary is dropped.
        val gone = tracker.update(DocumentDetector.Detection.NONE, now = 1000L)
        assertThat(gone.quad).isNull()
    }
}
