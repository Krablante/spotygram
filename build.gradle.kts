plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

val buildState = providers.environmentVariable("SPOTYGRAM_STATE")

if (buildState.isPresent) {
    val directory = buildState.get()
    layout.buildDirectory.set(file("$directory/build/root"))
    subprojects { layout.buildDirectory.set(file("$directory/build/$name")) }
}
