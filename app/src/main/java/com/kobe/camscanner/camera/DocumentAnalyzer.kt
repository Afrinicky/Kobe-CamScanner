package com.kobe.camscanner.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.kobe.camscanner.scanner.DocumentDetector
import com.kobe.camscanner.scanner.StabilityTracker
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The live detection loop.
 *
 * CameraX is configured with [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST], so frames are dropped
 * rather than queued whenever detection is still busy — the preview stays fluid on a mid-range
 * device even when a frame takes longer than a frame interval to analyse (SDS 45).
 *
 * Frames are additionally throttled to [MIN_INTERVAL_MS]. Detecting faster than that changes
 * nothing a user can see and only costs battery.
 */
class DocumentAnalyzer(
    private val detector: DocumentDetector,
    private val tracker: StabilityTracker,
    private val onResult: (StabilityTracker.State, frameWidth: Int, frameHeight: Int, rotation: Int) -> Unit,
) : ImageAnalysis.Analyzer {

    private val busy = AtomicBoolean(false)
    private var lastRunAt = 0L

    @Volatile
    var enabled: Boolean = true

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (!enabled || now - lastRunAt < MIN_INTERVAL_MS || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastRunAt = now

        try {
            val plane = image.planes.firstOrNull()
            if (plane == null) {
                image.close()
                return
            }
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val detection = detector.detectFromLuminance(
                luminance = bytes,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
            )
            val state = tracker.update(detection, now)
            onResult(state, image.width, image.height, image.imageInfo.rotationDegrees)
        } catch (t: Throwable) {
            // A single bad frame must never take the viewfinder down.
            onResult(StabilityTracker.State(null, StabilityTracker.Phase.SEARCHING, 0f), 0, 0, 0)
        } finally {
            busy.set(false)
            image.close()
        }
    }

    fun reset() = tracker.reset()

    private companion object {
        /** ~14 detections per second, which is past the point of visible improvement. */
        const val MIN_INTERVAL_MS = 70L
    }
}
