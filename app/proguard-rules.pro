// ProGuard rules for WhatsApp Summarizer
-keep class com.example.whatsapp_summarizer.data.model.** { *; }
-keep class com.example.whatsapp_summarizer.data.dao.** { *; }
-keep class com.example.whatsapp_summarizer.data.database.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Retrofit & OkHttp
-keepattributes Signature
-keepattributes Exceptions
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# JSON (org.json)
-keep class org.json.** { *; }
-dontwarn org.json.**

# Keep Kotlin metadata
-keepattributes *Annotation*
-keepattributes KotlinMetadata
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
