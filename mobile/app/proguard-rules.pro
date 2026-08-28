# Keep Ktor
-keep class io.ktor.** { *; }
-keep class kotlinx.serialization.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# App models
-keep class com.taskguide.app.data.model.** { *; }
