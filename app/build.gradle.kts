import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
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

val keystorePropertiesFilePath = providers.gradleProperty("geekify.keystorePropertiesFile").orNull ?: "keystore.properties"
val keystorePropertiesFile = rootProject.file(keystorePropertiesFilePath)
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun resolveSigningParam(envName: String, propKey: String, injectedPropName: String): String? {
    val ignoreEnv = providers.gradleProperty("geekify.ignoreSigningEnv").orNull == "true"
    val fromInjected = providers.gradleProperty(injectedPropName).orNull?.takeIf { it.isNotBlank() }
    if (fromInjected != null) return fromInjected
    if (!ignoreEnv) {
        val fromEnv = System.getenv(envName)?.takeIf { it.isNotBlank() }
        if (fromEnv != null) return fromEnv
    }
    return keystoreProperties.getProperty(propKey)?.takeIf { it.isNotBlank() }
}

val resolvedReleaseStorePath = resolveSigningParam("ANDROID_KEYSTORE_PATH", "storeFile", "android.injected.signing.store.file")
val resolvedReleaseStorePassword = resolveSigningParam("ANDROID_KEYSTORE_PASSWORD", "storePassword", "android.injected.signing.store.password")
val resolvedReleaseKeyAlias = resolveSigningParam("ANDROID_KEY_ALIAS", "keyAlias", "android.injected.signing.key.alias")
val resolvedReleaseKeyPassword = resolveSigningParam("ANDROID_KEY_PASSWORD", "keyPassword", "android.injected.signing.key.password")

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
            if (!resolvedReleaseStorePath.isNullOrBlank() &&
                !resolvedReleaseStorePassword.isNullOrBlank() &&
                !resolvedReleaseKeyAlias.isNullOrBlank() &&
                !resolvedReleaseKeyPassword.isNullOrBlank()
            ) {
                storeFile = rootProject.file(resolvedReleaseStorePath)
                storePassword = resolvedReleaseStorePassword
                keyAlias = resolvedReleaseKeyAlias
                keyPassword = resolvedReleaseKeyPassword
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
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
        val missingParams = mutableListOf<String>()
        if (resolvedReleaseStorePath.isNullOrBlank()) missingParams += "storeFile / ANDROID_KEYSTORE_PATH"
        if (resolvedReleaseStorePassword.isNullOrBlank()) missingParams += "storePassword / ANDROID_KEYSTORE_PASSWORD"
        if (resolvedReleaseKeyAlias.isNullOrBlank()) missingParams += "keyAlias / ANDROID_KEY_ALIAS"
        if (resolvedReleaseKeyPassword.isNullOrBlank()) missingParams += "keyPassword / ANDROID_KEY_PASSWORD"

        if (missingParams.isNotEmpty()) {
            throw GradleException(
                "Release signing configuration is missing or incomplete. Missing required signing parameters: " +
                    "${missingParams.joinToString(", ")}. " +
                    "Provide ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD " +
                    "(or keystore.properties / android.injected.signing.* properties) before building a release artifact."
            )
        }

        val keystoreFile = rootProject.file(resolvedReleaseStorePath!!)
        if (!keystoreFile.isFile) {
            throw GradleException(
                "Release signing keystore file was not found at the configured storeFile path (${keystoreFile.relativeToOrSelf(rootDir)}). " +
                    "Ensure the keystore file exists before building a release artifact."
            )
        }
    }
}

tasks.matching { task ->
    task.name == "assembleRelease" ||
        task.name == "bundleRelease" ||
        task.name == "packageRelease" ||
        task.name == "packageReleaseBundle" ||
        task.name == "validateSigningRelease" ||
        task.name == "signReleaseBundle"
}.configureEach {
    dependsOn(validateReleaseSigning)
}

dependencies {
    // Core / lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // Animated Material 3 wave seekbar for the expanded player
    implementation(libs.wavy.slider)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Network
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // DI (Koin)
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.androidx.workmanager)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Backdrop blur for the Liquid Glass floating surfaces
    implementation(libs.haze)

    // Background checks & downloads
    implementation(libs.androidx.work.runtime.ktx)

    // Baseline Profile installer for faster startup and reduced jank
    implementation(libs.androidx.profileinstaller)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coil image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Geekify InnerTube backend adapter + InnerTubeX stream extraction
    implementation(project(":innertube"))
    implementation(libs.timber)
    implementation(libs.androidx.collection.ktx)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Media3 / ExoPlayer
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)

    // Firebase (BOM keeps versions aligned)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)
    // FCM pushes announce published app releases; WorkManager remains the fallback.
    implementation(libs.firebase.messaging)

    // Google Credential Manager (replaces deprecated signInWithPopup on Android)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.room.testing)
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
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
