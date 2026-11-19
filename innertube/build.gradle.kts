plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.geekify.android.innertube"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // InnerTubeX provides client fallback, player config/cipher handling, SABR and stream extraction.
    api(libs.innertubex)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
}

// InnerTubeX v0.8.4 contains Java 21 bytecode (class-file version 65).
// Run this module's host-side unit tests on JDK 21 while keeping Android/Kotlin
// output targeted to Java 17 for app compatibility.
val jdk21TestLauncher = extensions
    .getByType<org.gradle.jvm.toolchain.JavaToolchainService>()
    .launcherFor {
        languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21))
    }

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    javaLauncher.set(jdk21TestLauncher)
}
