import java.util.Properties

plugins {
    id("com.android.application") version "9.1.1"
    id("com.android.library") version "9.1.1" apply false
}

val state = file(providers.environmentVariable("SPOTYGRAM_STATE").get()).canonicalFile
require(!state.toPath().startsWith(rootDir.parentFile.canonicalFile.toPath())) { "Build state must be outside source" }
val generated = state.resolve("rust/android/generated")
val secrets = Properties().apply { state.resolve("signing.properties").inputStream().use { load(it) } }

android {
    namespace = "app.spotygram.rust"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "app.spotygram.rust"
        minSdk = 26
        targetSdk = 36
        versionCode = 1010
        versionName = "0.2.8-rust"
    }
    signingConfigs {
        create("release") {
            storeFile = state.resolve("release.jks")
            storePassword = secrets.getProperty("storePassword")
            keyAlias = "spotygram"
            keyPassword = secrets.getProperty("storePassword")
        }
    }
    buildTypes {
        getByName("release") { signingConfig = signingConfigs.getByName("release"); isMinifyEnabled = false }
    }
    sourceSets {
        getByName("main") {
            assets.directories.add(generated.resolve("assets").path)
            jniLibs.directories.add(generated.resolve("jniLibs").path)
        }
    }
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "x86_64"); isUniversalApk = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    packaging { jniLibs { useLegacyPackaging = false } }
}

layout.buildDirectory.set(state.resolve("rust/android/build"))

dependencies {
    implementation(project(":makepad-platform"))
    implementation("androidx.annotation:annotation-jvm:1.9.1")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
}
