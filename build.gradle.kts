import org.gradle.api.tasks.bundling.Zip

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
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

