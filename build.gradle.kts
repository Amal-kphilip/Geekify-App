import org.gradle.api.tasks.bundling.Zip

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

tasks.register<Zip>("packageSourceArchive") {
    group = "distribution"
    description = "Packages source code archive excluding signing keys, credentials, local config, private service accounts, and build outputs."
    archiveBaseName.set("geekify-source")
    archiveVersion.set(providers.gradleProperty("appVersionName").orElse("2.5.8"))
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(rootDir) {
        exclude(
            ".git/**",
            ".gradle/**",
            ".kotlin/**",
            ".idea/**",
            "build/**",
            "app/build/**",
            "innertube/build/**",
            "**/build/**",
            "**/*.keystore",
            "**/*.jks",
            "**/*.key",
            "**/*private-key*.pem",
            "**/*signing*.p12",
            "**/*signing*.pfx",
            "**/release-signing.keystore",
            "keystore.properties",
            "**/keystore.properties",
            "local.properties",
            "**/local.properties",
            "**/*service-account*.json",
            "**/*firebase-adminsdk*.json",
            "app/google-services.json",
            "app/src/*/google-services.json",
            "**/*.apk",
            "**/*.aab",
            "geekify-source*.zip",
            "source-archive*.zip",
            "**/*.tar.gz",
            "**/*.tgz",
            "**/*.iml",
            "gradle-build.out.log",
            "gradle-build.err.log",
            "run-gradle-build.cmd"
        )
    }
}

