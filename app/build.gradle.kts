plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// config.json and photos/ live at the project root so they can be edited without touching
// code; they are copied into the APK's assets at build time. Both are git-ignored, so your
// family's names, numbers and pictures never end up in the repo. Without a config.json the
// build falls back to config.example.json and the placeholder pictures in photos/example/.
val familyConfig = rootProject.file("config.json").takeIf { it.exists() }
    ?: rootProject.file("config.example.json")

val copyFamilyConfig by tasks.registering(Copy::class) {
    from(familyConfig) { rename { "config.json" } }
    from(rootProject.file("photos")) { into("photos") }
    into(layout.buildDirectory.dir("generated/familyAssets"))
}

// Device-owner apps can only be updated with the key they were first installed with.
// scripts/make-keystore.sh creates keystore/release.keystore once; keep it backed up.
// Without it, builds use the standard Android debug key.
val familyKeystore = rootProject.file("keystore/release.keystore")

android {
    namespace = "com.family.grandmacall"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.family.grandmacall"
        minSdk = 22
        // Below 23 so every permission is granted at install time (no prompts). Android 14+
        // refuses to install apps targeting below 23; see README "Supported phones".
        targetSdk = 22
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (familyKeystore.exists()) {
            getByName("debug") {
                storeFile = familyKeystore
                storePassword = "grandmacall"
                keyAlias = "grandmacall"
                keyPassword = "grandmacall"
            }
        }
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/familyAssets"))

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

tasks.named("preBuild") { dependsOn(copyFamilyConfig) }
