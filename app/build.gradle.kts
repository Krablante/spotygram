import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildState = providers.environmentVariable("SPOTYGRAM_STATE").orElse("${rootDir}/native").get()
val credentials =
    Properties().apply {
        val file = file("$buildState/telegram.env")
        if (file.exists()) file.inputStream().use { load(it) }
    }

fun credential(name: String) =
    (System.getenv(name) ?: credentials.getProperty(name, "")).trim().trim('"', '\'')

val licenseAssets =
    tasks.register<Copy>("prepareLicenseAssets") {
        from(rootProject.file("LICENSE")) { rename { "Spotygram-MIT.txt" } }
        from(file("$buildState/tdlib-src/LICENSE_1_0.txt")) { rename { "Boost-1.0.txt" } }
        from(file("$buildState/native/openssl/LICENSE.txt")) { rename { "Apache-2.0.txt" } }
        into(layout.buildDirectory.dir("generated/licenseAssets"))
    }

android {
    namespace = "app.spotygram"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "app.spotygram"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        buildConfigField("int", "TELEGRAM_API_ID", credential("TELEGRAM_API_ID").ifBlank { "0" })
        buildConfigField("String", "TELEGRAM_API_HASH", "\"${credential("TELEGRAM_API_HASH")}\"")
        buildConfigField("boolean", "TELEGRAM_TEST_DC", "false")
    }
    signingConfigs {
        create("release") {
            val props = Properties()
            val path = file("$buildState/signing.properties")
            if (path.exists()) path.inputStream().use { props.load(it) }
            storeFile = file("$buildState/release.jks")
            storePassword = props.getProperty("storePassword", "")
            keyAlias = "spotygram"
            keyPassword = props.getProperty("storePassword", "")
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            buildConfigField(
                "boolean",
                "TELEGRAM_TEST_DC",
                providers.gradleProperty("telegramTestDc").orElse("false").get(),
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    sourceSets["main"].jniLibs.srcDir("$buildState/jniLibs")
    sourceSets["main"].assets.srcDir(licenseAssets)
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

tasks.named("preBuild").configure { dependsOn(licenseAssets) }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
}
