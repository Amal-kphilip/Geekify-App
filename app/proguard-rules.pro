# ProGuard / R8 Rules for Geekify

-dontrepackage

# Kotlin Standard Library & Coroutines
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Kotlinx Serialization
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    <fields>;
    <init>(...);
}

# Room Database
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**
-keep class com.geekify.android.data.local.** { *; }

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Ktor Client
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Coil Image Loading
-dontwarn coil3.**
-keep class coil3.** { *; }

# Firebase
-dontwarn com.google.firebase.**

# Hilt & Dagger
-dontwarn dagger.**
-dontwarn javax.annotation.**
-keep class * extends dagger.hilt.internal.GeneratedComponent

# Baseline Profile & ProfileInstaller
-dontwarn androidx.profileinstaller.**
-keep class androidx.profileinstaller.** { *; }
