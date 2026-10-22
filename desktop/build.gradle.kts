import java.util.Properties
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

version = rootProject.version
val buildState = rootProject.extra["spotygramState"] as String
val definitions = layout.buildDirectory.dir("generated/definitions")
val generateDefinitions = tasks.register("generateDesktopDefinitions") {
    val strings = rootProject.file("shared/src/main/res/values/strings.xml")
    val drawables = rootProject.fileTree("shared/src/main/res/drawable-nodpi") { include("*.png") }
    inputs.file(strings)
    inputs.files(drawables)
    inputs.property("version", rootProject.version.toString())
    inputs.files(listOfNotNull(file("$buildState/telegram.env").takeIf { it.isFile }))
    inputs.property("telegramConfiguration", MessageDigest.getInstance("SHA-256")
        .digest("${System.getenv("TELEGRAM_API_ID").orEmpty()}\n${System.getenv("TELEGRAM_API_HASH").orEmpty()}".toByteArray())
        .joinToString("") { "%02x".format(it) })
    inputs.property("debug", providers.gradleProperty("desktopDebug").orElse("false"))
    outputs.dir(definitions)
    doLast {
        val credentials = Properties().apply {
            val configuration = file("$buildState/telegram.env")
            if (configuration.isFile) configuration.inputStream().use { load(it) }
        }
        fun credential(name: String) =
            (System.getenv(name) ?: credentials.getProperty(name, "")).trim().trim('"', '\'')
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(strings)
        val output = definitions.get().asFile.resolve("app/spotygram").apply { mkdirs() }
        val names = linkedMapOf<Int, String>()
        var id = 1
        output.resolve("R.kt").writeText(buildString {
            appendLine("package app.spotygram")
            appendLine("object R {")
            for (type in listOf("string", "plurals")) {
                appendLine("    object $type {")
                val nodes = document.getElementsByTagName(type)
                for (i in 0 until nodes.length) {
                    val name = (nodes.item(i) as org.w3c.dom.Element).getAttribute("name")
                    names[id] = name
                    appendLine("        const val $name = ${id++}")
                }
                appendLine("    }")
            }
            appendLine("    object drawable {")
            val drawableNames = drawables.files.sortedBy { it.name }.map { it.nameWithoutExtension }
            drawableNames.forEachIndexed { index, name ->
                appendLine("        const val $name = ${10000 + index}")
            }
            appendLine("        val names = mapOf(")
            drawableNames.forEach { name -> appendLine("            $name to \"$name\",") }
            appendLine("        )")
            appendLine("    }")
            appendLine("    val names = mapOf(")
            names.forEach { (number, name) -> appendLine("        $number to \"$name\",") }
            appendLine("    )")
            appendLine("}")
        })
        val apiId = credential("TELEGRAM_API_ID").toIntOrNull() ?: 0
        val apiHash = credential("TELEGRAM_API_HASH").replace("\\", "\\\\").replace("\"", "\\\"")
        output.resolve("BuildConfig.kt").writeText("""
            package app.spotygram
            object BuildConfig {
                const val VERSION_NAME = "${rootProject.version}"
                const val APPLICATION_ID = "app.spotygram"
                const val DEBUG = ${providers.gradleProperty("desktopDebug").orElse("false").get().toBoolean()}
                const val TELEGRAM_API_ID = $apiId
                const val TELEGRAM_API_HASH = "$apiHash"
                const val TELEGRAM_TEST_DC = false
            }
        """.trimIndent())
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    sourceSets.main {
        kotlin.srcDir(rootProject.file("shared/src/main/kotlin"))
        kotlin.srcDir(definitions)
    }
}
sourceSets.main { resources.srcDir(rootProject.file("shared/src/main/res")) }
tasks.named("compileKotlin").configure { dependsOn(generateDefinitions) }

val targetOs = when {
    System.getProperty("os.name").startsWith("Windows") -> "windows"
    System.getProperty("os.name").startsWith("Mac") -> "macos"
    else -> "linux"
}
val targetArch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x64"
val nativeDirectory = file("$buildState/desktop/deps/$targetOs-$targetArch")
val prepareNative = tasks.register<Exec>("prepareNativeLibraries") {
    commandLine(if (targetOs == "windows") "python" else "python3",
        rootProject.file("tools/desktop-dependencies.py"), "--target", "$targetOs-$targetArch", "--state", buildState)
    doLast {
        nativeDirectory.resolve("licenses").mkdirs()
        rootProject.file("LICENSE").copyTo(nativeDirectory.resolve("licenses/Spotygram.txt"), overwrite = true)
        file("src/main/resources/third-party-notices.txt").copyTo(
            nativeDirectory.resolve("licenses/Spotygram-dependencies.txt"), overwrite = true)
        rootProject.file("shared/licenses/Fluent-Emoji-MIT.txt").copyTo(
            nativeDirectory.resolve("licenses/Fluent-Emoji-MIT.txt"), overwrite = true)
        rootProject.file("shared/licenses/Takopi-artwork-NOTICE.txt").copyTo(
            nativeDirectory.resolve("licenses/Takopi-artwork-NOTICE.txt"), overwrite = true)
    }
}
val icons = layout.buildDirectory.dir("icons")
val prepareIcons = tasks.register<JavaExec>("prepareDesktopIcons") {
    dependsOn("classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.spotygram.DesktopMainKt")
    args("--write-icons", icons.get().asFile.absolutePath)
    jvmArgs("-Djava.awt.headless=true", "-Xmx128m")
    inputs.file(rootProject.file("shared/src/main/res/drawable-nodpi/spotygram_launcher.png"))
    outputs.dir(icons)
}

dependencies {
    if (targetOs == "windows") components.withModule("org.jetbrains.skiko:skiko-awt") {
        val rendererVersion = id.version
        allVariants {
            withDependencies { add("org.jetbrains.skiko:skiko-awt-runtime-angle-windows-$targetArch:$rendererVersion") }
        }
    }
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.json:json:20250517")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    implementation("com.github.javakeyring:java-keyring:1.0.4")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("dev.nucleusframework:nucleus.media-control:2.5.0")
    implementation("org.slf4j:slf4j-nop:2.0.17")
}

compose.desktop {
    application {
        mainClass = "app.spotygram.DesktopMainKt"
        jvmArgs += listOf("-Xms32m", "-Xmx256m", "-XX:+UseG1GC")
        nativeDistributions {
            packageName = "Spotygram"
            packageVersion = rootProject.version.toString()
            modules("java.sql", "jdk.crypto.ec", "java.net.http", "jdk.unsupported", "jdk.security.auth")
            appResourcesRootDir.set(nativeDirectory.parentFile)
            linux { iconFile.set(icons.get().asFile.resolve("spotygram.png")) }
            windows { iconFile.set(icons.get().asFile.resolve("spotygram.ico")) }
            macOS {
                // jpackage requires a positive major; the final app bundle keeps our public version.
                packageVersion = rootProject.version.toString().split('.').let {
                    "${it[0].toInt() + 1}.${it[1]}.${it[2]}"
                }
                iconFile.set(icons.get().asFile.resolve("spotygram.icns"))
                bundleID = "app.spotygram"
            }
            outputBaseDir.set(layout.buildDirectory.dir("distributions"))
        }
    }
}
tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        dependsOn(prepareNative)
        jvmArgs("-Dspotygram.native.dir=${nativeDirectory.absolutePath}")
        providers.gradleProperty("desktopData").orNull?.let { jvmArgs("-Dspotygram.data.dir=$it") }
        providers.gradleProperty("telegramTestDc").orNull?.let { jvmArgs("-Dspotygram.telegram.test=$it") }
    }
}
tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    dependsOn(prepareNative, prepareIcons)
    if (targetOs == "windows") doLast {
        val variant = if (name == "createReleaseDistributable") "release" else "main"
        val destination = layout.buildDirectory.dir("distributions/$variant/app/Spotygram/app").get().asFile
        val angle = configurations.runtimeClasspath.get().single { it.name.startsWith("skiko-awt-runtime-angle-windows-") }
        ZipFile(angle).use { archive ->
            listOf("libEGL.dll", "libGLESv2.dll").forEach { name ->
                archive.getInputStream(checkNotNull(archive.getEntry(name))).use { source ->
                    destination.resolve(name).outputStream().use { source.copyTo(it) }
                }
            }
        }
    }
    if (targetOs != "windows") doLast {
        val variant = if (name == "createReleaseDistributable") "release" else "main"
        val resources = layout.buildDirectory.dir("distributions/$variant/app/" +
            if (targetOs == "macos") "Spotygram.app/Contents/app/resources" else "Spotygram/lib/app/resources")
            .get().asFile.toPath()
        // Gradle's resource copy follows native aliases, duplicating large libraries.
        nativeDirectory.listFiles().orEmpty().filter { Files.isSymbolicLink(it.toPath()) }.forEach { source ->
            val destination = resources.resolve(source.name)
            Files.deleteIfExists(destination)
            Files.createSymbolicLink(destination, Files.readSymbolicLink(source.toPath()))
        }
    }
    if (targetOs == "macos") doLast {
        val variant = if (name == "createReleaseDistributable") "release" else "main"
        val bundle = layout.buildDirectory.dir("distributions/$variant/app/Spotygram.app").get().asFile
        val plist = bundle.resolve("Contents/Info.plist")
        providers.exec {
            commandLine("/usr/libexec/PlistBuddy", "-c", "Set :CFBundleShortVersionString ${rootProject.version}", plist)
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine("/usr/libexec/PlistBuddy", "-c", "Set :CFBundleVersion ${rootProject.version}", plist)
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine("codesign", "--force", "--deep", "--sign", "-", bundle)
        }.result.get().assertNormalExitValue()
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(prepareNative)
}
