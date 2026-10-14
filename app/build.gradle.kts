import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val buildState = rootProject.extra["spotygramState"] as String
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
        from(rootProject.file("shared/licenses/Fluent-Emoji-MIT.txt"))
        from(file("$buildState/tdlib-src/LICENSE_1_0.txt")) { rename { "Boost-1.0.txt" } }
        from(file("$buildState/native/openssl/LICENSE.txt")) { rename { "Apache-2.0.txt" } }
        into(layout.buildDirectory.dir("generated/licenseAssets"))
    }

android {
    namespace = "app.spotygram"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "app.spotygram"
        minSdk = 26
        targetSdk = 36
        versionCode = 44
        versionName = rootProject.version.toString()
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
    sourceSets["main"].jniLibs.directories += "$buildState/jniLibs"
    sourceSets["main"].java.srcDir(rootProject.file("shared/src/main/kotlin"))
    sourceSets["main"].res.srcDir(rootProject.file("shared/src/main/res"))
    sourceSets["main"].assets.directories += layout.buildDirectory.get().dir("generated/licenseAssets").asFile.path
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
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
