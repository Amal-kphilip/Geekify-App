plugins {
    id("com.android.application") version "9.4.0" apply false
    id("com.android.library") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    // KSP 2.3.10 is the current KSP line supporting Kotlin 2.4.x.
    id("com.google.devtools.ksp") version "2.3.10" apply false
    // Google services (Firebase) — must match firebase-bom 33.x
    id("com.google.gms.google-services") version "4.4.2" apply false
}
