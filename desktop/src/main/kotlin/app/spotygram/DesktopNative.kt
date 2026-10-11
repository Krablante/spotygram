package app.spotygram

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

fun prepareDesktopGraphics(cache: File) {
    if (DesktopTarget.os != "windows" || System.getProperty("skiko.library.path") != null) return
    val resources = DesktopTdClient::class.java.classLoader
    fun checksum(name: String) = checkNotNull(resources.getResourceAsStream("$name.sha256"))
        .bufferedReader().use { it.readLine().trim().lowercase() }
        .also { require(it.matches(Regex("[0-9a-f]{64}"))) }
    val digest = checksum("libGLESv2.dll")
    val storage = File(cache, "graphics")
    System.setProperty("skiko.data.path", storage.absolutePath)
    val directory = File(storage, "libEGL-${checksum("libEGL.dll")}").apply { check(isDirectory || mkdirs()) }
    val library = File(directory, "libGLESv2.dll")
    if (!library.isFile || SignedBundle.sha256(library) != digest) {
        val temporary = java.nio.file.Files.createTempFile(directory.toPath(), "graphics-", ".dll")
        try {
            checkNotNull(resources.getResourceAsStream("libGLESv2.dll")).use { source ->
                java.nio.file.Files.copy(source, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            check(SignedBundle.sha256(temporary.toFile()) == digest)
            java.nio.file.Files.move(temporary, library.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { java.nio.file.Files.deleteIfExists(temporary) }
    }
    // Skiko extracts libEGL before its libGLESv2 dependency; load that dependency first.
    System.load(library.absolutePath)
}

object DesktopTarget {
    val os = when {
        System.getProperty("os.name").startsWith("Windows") -> "windows"
        System.getProperty("os.name").startsWith("Mac") -> "macos"
        else -> "linux"
    }
    val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x64"
    val id = "$os-$arch"
    val resources = File(System.getProperty("compose.application.resources.dir", System.getProperty("user.dir")))
    val installerExtension: String? by lazy {
        val marker = File(resources, "installation")
        val expected = when (os) { "windows" -> "msi"; "macos" -> "dmg"; else -> "deb" }
        marker.takeIf { it.isFile && it.length() <= 16 }?.readText()?.trim()?.takeIf { it == expected }
    }
    val nativeDirectory = File(System.getProperty("spotygram.native.dir", resources.path))

    fun library(name: String): String {
        val file = when (os) {
            "windows" -> File(nativeDirectory, if (name == "mpv") "libmpv-2.dll" else "$name.dll")
            "macos" -> File(nativeDirectory, "lib$name.dylib")
            else -> File(nativeDirectory, "lib$name.so")
        }
        check(file.isFile) { "Spotygram native libraries are missing. Reinstall the complete package." }
        return file.absolutePath
    }
}

interface TdJsonNative : Library {
    fun td_create_client_id(): Int
    fun td_send(client: Int, request: String)
    fun td_receive(timeout: Double): Pointer?
    fun td_execute(request: String): Pointer?
}

class DesktopTdClient : TdClient {
    private val native by lazy {
        Native.load(DesktopTarget.library("tdjson"), TdJsonNative::class.java,
            mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
    }
    override fun createClientId() = native.td_create_client_id()
    override fun send(client: Int, request: String) = native.td_send(client, request)
    override fun receive(timeout: Double) = native.td_receive(timeout)?.getString(0, "UTF-8")
    override fun execute(request: String) = native.td_execute(request)?.getString(0, "UTF-8")
}
