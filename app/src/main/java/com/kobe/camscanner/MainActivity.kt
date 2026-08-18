package com.kobe.camscanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kobe.camscanner.core.ui.theme.KobeTheme
import com.kobe.camscanner.data.settings.KobeSettings
import com.kobe.camscanner.data.settings.Settings
import com.kobe.camscanner.data.settings.ThemePreference
import com.kobe.camscanner.navigation.KobeNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The only Activity.
 *
 * Kobe is a single-activity Compose app: the scanner, the editors and the library are all
 * destinations in one graph, which is what lets a scan session survive navigation without being
 * serialised across process boundaries.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: KobeSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedUris = incomingImageUris(intent)

        setContent {
            val prefs by settings.settings.collectAsState(initial = Settings())
            val systemDark = isSystemInDarkTheme()
            val dark = when (prefs.theme) {
                ThemePreference.SYSTEM -> systemDark
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }

            KobeTheme(darkTheme = dark) {
                // Images shared into Kobe from another app are consumed once: the home screen runs
                // them through the same scanner pipeline as a camera capture and opens review.
                var pendingImport by remember { mutableStateOf(sharedUris) }

                KobeNavHost(
                    pendingImport = pendingImport,
                    onImportConsumed = { pendingImport = emptyList() },
                )
            }
        }
    }

    /** Extracts images shared into Kobe from another app (manifest SEND / SEND_MULTIPLE). */
    private fun incomingImageUris(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri,
        )

        Intent.ACTION_SEND_MULTIPLE ->
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()

        else -> emptyList()
    }
}
