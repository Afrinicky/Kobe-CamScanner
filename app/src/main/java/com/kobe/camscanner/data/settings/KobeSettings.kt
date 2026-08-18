package com.kobe.camscanner.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kobe.camscanner.domain.model.PdfCompression
import com.kobe.camscanner.domain.model.PdfPageSize
import com.kobe.camscanner.domain.model.ScanFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "kobe_settings",
)

/** Everything the user can change, and the defaults a first-time user gets. */
data class Settings(
    val autoCapture: Boolean = true,
    val autoOcr: Boolean = true,
    val shutterSound: Boolean = false,
    val showGrid: Boolean = false,
    val defaultFilter: ScanFilter = ScanFilter.AUTO,
    val defaultPageSize: PdfPageSize = PdfPageSize.A4,
    val defaultCompression: PdfCompression = PdfCompression.BALANCED,
    val searchablePdf: Boolean = true,
    val smartNaming: Boolean = true,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val appLock: Boolean = false,
    val pdfAuthor: String = "",
)

enum class ThemePreference(val label: String) {
    SYSTEM("Match system"),
    LIGHT("Light"),
    DARK("Dark"),
}

/**
 * Preference storage. DataStore rather than SharedPreferences because every read here feeds a
 * Compose screen, and a Flow means a setting change reaches the viewfinder without a restart.
 */
@Singleton
class KobeSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val AUTO_CAPTURE = booleanPreferencesKey("auto_capture")
        val AUTO_OCR = booleanPreferencesKey("auto_ocr")
        val SHUTTER_SOUND = booleanPreferencesKey("shutter_sound")
        val SHOW_GRID = booleanPreferencesKey("show_grid")
        val DEFAULT_FILTER = stringPreferencesKey("default_filter")
        val PAGE_SIZE = stringPreferencesKey("page_size")
        val COMPRESSION = stringPreferencesKey("compression")
        val SEARCHABLE_PDF = booleanPreferencesKey("searchable_pdf")
        val SMART_NAMING = booleanPreferencesKey("smart_naming")
        val THEME = stringPreferencesKey("theme")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val PDF_AUTHOR = stringPreferencesKey("pdf_author")
    }

    val settings: Flow<Settings> = context.settingsStore.data.map { prefs ->
        Settings(
            autoCapture = prefs[Keys.AUTO_CAPTURE] ?: true,
            autoOcr = prefs[Keys.AUTO_OCR] ?: true,
            shutterSound = prefs[Keys.SHUTTER_SOUND] ?: false,
            showGrid = prefs[Keys.SHOW_GRID] ?: false,
            defaultFilter = prefs[Keys.DEFAULT_FILTER].toEnum(ScanFilter.AUTO),
            defaultPageSize = prefs[Keys.PAGE_SIZE].toEnum(PdfPageSize.A4),
            defaultCompression = prefs[Keys.COMPRESSION].toEnum(PdfCompression.BALANCED),
            searchablePdf = prefs[Keys.SEARCHABLE_PDF] ?: true,
            smartNaming = prefs[Keys.SMART_NAMING] ?: true,
            theme = prefs[Keys.THEME].toEnum(ThemePreference.SYSTEM),
            appLock = prefs[Keys.APP_LOCK] ?: false,
            pdfAuthor = prefs[Keys.PDF_AUTHOR] ?: "",
        )
    }

    suspend fun setAutoCapture(value: Boolean) = put(Keys.AUTO_CAPTURE, value)
    suspend fun setAutoOcr(value: Boolean) = put(Keys.AUTO_OCR, value)
    suspend fun setShutterSound(value: Boolean) = put(Keys.SHUTTER_SOUND, value)
    suspend fun setShowGrid(value: Boolean) = put(Keys.SHOW_GRID, value)
    suspend fun setSearchablePdf(value: Boolean) = put(Keys.SEARCHABLE_PDF, value)
    suspend fun setSmartNaming(value: Boolean) = put(Keys.SMART_NAMING, value)
    suspend fun setAppLock(value: Boolean) = put(Keys.APP_LOCK, value)

    suspend fun setDefaultFilter(value: ScanFilter) = put(Keys.DEFAULT_FILTER, value.name)
    suspend fun setPageSize(value: PdfPageSize) = put(Keys.PAGE_SIZE, value.name)
    suspend fun setCompression(value: PdfCompression) = put(Keys.COMPRESSION, value.name)
    suspend fun setTheme(value: ThemePreference) = put(Keys.THEME, value.name)
    suspend fun setPdfAuthor(value: String) = put(Keys.PDF_AUTHOR, value)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.settingsStore.edit { it[key] = value }
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(fallback: E): E =
        this?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: fallback
}
