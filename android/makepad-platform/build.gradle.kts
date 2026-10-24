plugins { id("com.android.library") }

val state = file(providers.environmentVariable("SPOTYGRAM_STATE").get()).canonicalFile
val generated = state.resolve("rust/android/generated")

android {
    namespace = "dev.makepad.android"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    sourceSets.getByName("main") {
        java.directories.add(generated.resolve("java").path)
        res.directories.add(generated.resolve("res").path)
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
}
layout.buildDirectory.set(state.resolve("rust/android/makepad-build"))
