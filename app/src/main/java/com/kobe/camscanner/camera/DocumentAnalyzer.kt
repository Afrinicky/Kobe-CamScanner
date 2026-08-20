package com.kobe.camscanner.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.kobe.camscanner.scanner.DocumentDetector
import com.kobe.camscanner.scanner.FrameGeometry
import com.kobe.camscanner.scanner.StabilityTracker
import java.util.concurrent.atomic.AtomicBoolean

/** What the analyser hands back to the viewfinder, already in upright space. */
data class AnalysisResult(
    val state: StabilityTracker.State,
    /** width / height of the frame *after* rotation, for mapping onto the preview. */
    val uprightAspect: Float,
)

/**
 * The live detection loop.
 *
 * Frames arrive in the sensor's orientation, which on a phone held upright is landscape with
 * `rotationDegrees = 90`. The boundary is detected in that space and then immediately converted to
 * upright space, so everything downstream — the smoothing in [StabilityTracker], the on-screen
 * overlay, and the quad handed to the capture — speaks one language. Skipping that conversion is
 * what made detection look broken in the first build.
 *
 * CameraX is configured with [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST], so frames are dropped
 * rather than queued whenever detection is still busy, and the loop is additionally throttled to
 * [MIN_INTERVAL_MS] because detecting faster changes nothing a user can see.
 */
class DocumentAnalyzer(
    private val detector: DocumentDetector,
    private val tracker: StabilityTracker,
    private val onResult: (AnalysisResult) -> Unit,
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

            val rotation = image.imageInfo.rotationDegrees
            val detection = detector.detectFromLuminance(
                luminance = bytes,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
            )

            val upright = detection.quad?.let { FrameGeometry.analysisToUpright(it, rotation) }
            val state = tracker.update(
                DocumentDetector.Detection(upright, detection.confidence),
                now,
            )

            val (uw, uh) = FrameGeometry.uprightSize(image.width, image.height, rotation)
            onResult(AnalysisResult(state, if (uh > 0) uw.toFloat() / uh else 1f))
        } catch (t: Throwable) {
            // A single bad frame must never take the viewfinder down.
            onResult(
                AnalysisResult(
                    StabilityTracker.State(null, StabilityTracker.Phase.SEARCHING, 0f),
                    1f,
                ),
            )
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
