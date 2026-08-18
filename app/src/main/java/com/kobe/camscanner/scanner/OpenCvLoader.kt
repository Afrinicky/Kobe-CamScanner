package com.kobe.camscanner.scanner

import android.util.Log
import org.opencv.android.OpenCVLoader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot native OpenCV initialisation.
 *
 * The whole engine degrades gracefully if this fails: [isAvailable] is checked by every entry point
 * and the app falls back to a full-frame quad plus pure-Kotlin enhancement. A device with an
 * unsupported ABI therefore gets a working scanner, not a crash (SDS 49).
 */
object OpenCvLoader {

    private const val TAG = "KobeOpenCV"
    private val initialised = AtomicBoolean(false)

    @Volatile
    var isAvailable: Boolean = false
        private set

    fun init() {
        if (!initialised.compareAndSet(false, true)) return
        isAvailable = try {
            OpenCVLoader.initLocal()
        } catch (t: Throwable) {
            Log.w(TAG, "OpenCV native load failed; using the fallback pipeline", t)
            false
        }
        Log.i(TAG, "OpenCV available: $isAvailable")
    }
}
