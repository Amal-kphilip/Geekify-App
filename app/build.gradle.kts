import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

fun semanticVersionCode(versionName: String): Int? {
    val parts = versionName.trim().removePrefix("v").substringBefore('-').split('.')
    val nums = parts.map { it.toIntOrNull() ?: return null }
    if (nums.isEmpty() || nums.size > 3) return null
    val major = nums[0]
    val minor = nums.getOrElse(1) { 0 }
    val patch = nums.getOrElse(2) { 0 }
    if (minor !in 0..99 || patch !in 0..99) return null
    return major * 10_000 + minor * 100 + patch
}

val resolvedVersionName: String = providers.gradleProperty("appVersionName").orNull
    ?.trim()
    ?.removePrefix("v")
    ?.takeIf { it.isNotEmpty() }
    ?: "0.1.0"

val resolvedVersionCode: Int = semanticVersionCode(resolvedVersionName)
    ?: providers.gradleProperty("appVersionCode").orNull?.toIntOrNull()
    ?: 100

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.geekify.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.geekify.android"
        minSdk = 26
        targetSdk = 37
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }
    signingConfigs {
        create("release") {
            val envStoreFile = System.getenv("ANDROID_KEYSTORE_PATH")
                ?: keystoreProperties.getProperty("storeFile")
            val envStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                ?: keystoreProperties.getProperty("storePassword")
            val envKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
                ?: keystoreProperties.getProperty("keyAlias")
            val envKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                ?: keystoreProperties.getProperty("keyPassword")

            if (!envStoreFile.isNullOrBlank() &&
                !envStorePassword.isNullOrBlank() &&
                !envKeyAlias.isNullOrBlank() &&
                !envKeyPassword.isNullOrBlank()
            ) {
                storeFile = rootProject.file(envStoreFile)
                storePassword = envStorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "META-INF/DEPENDENCIES"
        jniLibs {
            keepDebugSymbols += setOf(
                "**/libandroidx.graphics.path.so",
                "**/libdatastore_shared_counter.so",
                "**/libquickjs.so"
            )
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning?.storeFile != null) {
                signingConfig = releaseSigning
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    group = "verification"
    description = "Verifies that release signing credentials are configured before building a release artifact."
    doLast {
        val releaseSigning = android.signingConfigs.findByName("release")
        val injectedStoreFile = providers.gradleProperty("android.injected.signing.store.file").orNull
        val injectedStorePassword = providers.gradleProperty("android.injected.signing.store.password").orNull
        val injectedKeyAlias = providers.gradleProperty("android.injected.signing.key.alias").orNull
        val injectedKeyPassword = providers.gradleProperty("android.injected.signing.key.password").orNull

        val hasInjectedSigning = !injectedStoreFile.isNullOrBlank() &&
            !injectedStorePassword.isNullOrBlank() &&
            !injectedKeyAlias.isNullOrBlank() &&
            !injectedKeyPassword.isNullOrBlank() &&
            file(injectedStoreFile).exists()

        val configuredStoreFile = releaseSigning?.storeFile
        val hasReleaseSigningConfig = configuredStoreFile != null &&
            configuredStoreFile.exists() &&
            !releaseSigning.storePassword.isNullOrBlank() &&
            !releaseSigning.keyAlias.isNullOrBlank() &&
            !releaseSigning.keyPassword.isNullOrBlank()

        if (!hasReleaseSigningConfig && !hasInjectedSigning) {
            throw GradleException(
                "Release signing configuration is missing or incomplete. " +
                    "Configure ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD " +
                    "(or provide keystore.properties in the project root) before building a release artifact."
            )
        }
    }
}

tasks.matching { task ->
    task.name == "assembleRelease" ||
        task.name == "bundleRelease" ||
        task.name == "packageRelease" ||
        task.name == "packageReleaseBundle"
}.configureEach {
    dependsOn(validateReleaseSigning)
}

dependencies {
    // Core / lifecycle
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.6")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Animated Material 3 wave seekbar for the expanded player
    implementation("ir.mahozad.multiplatform:wavy-slider:2.2.0")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Network
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okhttp3:logging-interceptor:5.3.2")

    // DI
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-compiler:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Backdrop blur for the Liquid Glass floating surfaces
    implementation("dev.chrisbanes.haze:haze:1.7.0")

    // Background checks for update/engagement notifications (about twice a day)
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Baseline Profile installer for faster startup and reduced jank
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Coil image loading
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    // Geekify InnerTube backend adapter + InnerTubeX stream extraction
    implementation(project(":innertube"))
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("androidx.collection:collection-ktx:1.5.0")
    implementation("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-client-encoding:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.6.1")

    // Firebase (BOM keeps versions aligned)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    // FCM pushes announce published app releases; WorkManager remains the fallback.
    implementation("com.google.firebase:firebase-messaging")

    // Google Credential Manager (replaces deprecated signInWithPopup on Android)
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// InnerTubeX v0.8.4 contains Java 21 bytecode (class-file version 65).
// Run host-side unit tests on JDK 21 while keeping Android/Kotlin output targeted to Java 17.
val jdk21TestLauncher = extensions
    .getByType<org.gradle.jvm.toolchain.JavaToolchainService>()
    .launcherFor {
        languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21))
    }

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    javaLauncher.set(jdk21TestLauncher)
}
