plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.compose") version "1.12.0" apply false
}

version = "0.15.0"

subprojects {
    plugins.withId("org.jetbrains.kotlin.plugin.compose") {
        listOf(
            "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.10.0",
            "io.coil-kt.coil3:coil-compose:3.3.0",
            "dev.chrisbanes.haze:haze:2.0.1",
            "dev.chrisbanes.haze:haze-glass:2.0.1",
            "com.google.zxing:core:3.5.4",
        ).forEach { dependencies.add("implementation", it) }
    }
}

val buildState = providers.environmentVariable("SPOTYGRAM_STATE")

if (buildState.isPresent) {
    val directory = buildState.get()
    layout.buildDirectory.set(file("$directory/build/root"))
    subprojects { layout.buildDirectory.set(file("$directory/build/$name")) }
}
