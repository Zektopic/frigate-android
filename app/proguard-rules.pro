# Proguard configurations for the Frigate NVR application.

# Keep Room database and entity classes
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.limit.**
