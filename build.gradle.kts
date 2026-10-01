plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

val buildState = providers.environmentVariable("SPOTYGRAM_STATE")

if (buildState.isPresent) {
    val directory = buildState.get()
    layout.buildDirectory.set(file("$directory/build/root"))
    subprojects { layout.buildDirectory.set(file("$directory/build/$name")) }
}
