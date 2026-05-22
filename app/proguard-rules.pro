# Proguard configurations for the Frigate NVR application.

# Keep Room database and entity classes
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.limit.**

# Keep TensorFlow Lite / LiteRT elements
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Keep LibVLC models and media descriptors
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**
