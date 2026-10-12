plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.geekify.android.innertube"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
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
    // InnerTubeX provides client fallback, player config/cipher handling and stream extraction.
    api("com.github.MetrolistGroup.innertubex:innertubex:v0.8.4")
    implementation("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-client-encoding:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.jakewharton.timber:timber:5.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-client-mock:3.6.0")
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
