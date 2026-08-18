package com.kobe.camscanner

import android.app.Application
import com.kobe.camscanner.scanner.OpenCvLoader
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point.
 *
 * The two asset/native-backed libraries Kobe depends on are initialised here, once, so no screen
 * ever has to guard against "was OpenCV loaded yet?". Both are fully offline: OpenCV is a bundled
 * .so, PDFBox reads its font metrics from the APK's own assets.
 */
@HiltAndroidApp
class KobeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        OpenCvLoader.init()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
