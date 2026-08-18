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
