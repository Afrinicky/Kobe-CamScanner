# --- OpenCV -------------------------------------------------------------
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# --- PDFBox-Android -----------------------------------------------------
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.apache.commons.logging.**
-dontwarn javax.naming.**

# --- ML Kit -------------------------------------------------------------
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# --- Room / Hilt keep generated code ------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep @dagger.hilt.android.HiltAndroidApp class *

# Kotlin metadata used by reflection-free serialization of enums in DataStore.
-keepclassmembers enum * { *; }

# --- Kotlin / Compose ---------------------------------------------------
# Compose ships its own rules; these cover the reflective corners the app touches.
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}
-dontwarn kotlinx.coroutines.**

# --- CameraX ------------------------------------------------------------
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# --- Kept data models ---------------------------------------------------
# Room and the settings store instantiate these reflectively.
-keep class com.kobe.camscanner.data.local.** { *; }
-keep class com.kobe.camscanner.domain.model.** { *; }

# PDFBox reaches for AWT classes that do not exist on Android; the paths are unused.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.apache.pdfbox.**
