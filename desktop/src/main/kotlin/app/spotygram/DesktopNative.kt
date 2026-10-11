package app.spotygram

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Memory
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.ptr.PointerByReference
import java.io.File

private interface Direct3D12 : Library {
    fun D3D12CreateDevice(adapter: Pointer?, minimumFeatureLevel: Int, iid: Guid.GUID, device: Pointer?): Int
}

private interface Dxgi : Library {
    fun CreateDXGIFactory1(iid: Guid.GUID, factory: PointerByReference): Int
}

private class DxgiFactory(pointer: Pointer) : Unknown(pointer) {
    fun adapter(index: Int, output: PointerByReference) = _invokeNativeInt(12, arrayOf(pointer, index, output))
}

private class DxgiAdapter(pointer: Pointer) : Unknown(pointer) {
    fun hardware() = Memory(312).use { description ->
        // DXGI_ADAPTER_DESC1 on Windows x64: Flags follows the 128 WCHARs, IDs, sizes and LUID.
        if (_invokeNativeInt(10, arrayOf(pointer, description)) < 0) return@use false
        // Hyper-V can expose Microsoft's WARP adapter without the software flag.
        val warp = description.getInt(256) == 0x1414 && description.getInt(260) == 0x8c
        !warp && (description.getInt(304) and 2) == 0
    }
}

private fun hardwareDirect3D(): Boolean {
    val factoryPointer = PointerByReference()
    if (Native.load("dxgi", Dxgi::class.java).CreateDXGIFactory1(
        Guid.GUID("770AAE78-F26F-4DBA-A829-253C83D1B387"), factoryPointer,
    ) < 0) return false
    val factory = DxgiFactory(checkNotNull(factoryPointer.value))
    try {
        val direct3d = Native.load("d3d12", Direct3D12::class.java)
        val deviceId = Guid.GUID("189819F1-1DB6-4B57-BE54-1821339B85F7")
        var index = 0
        while (true) {
            val pointer = PointerByReference()
            if (factory.adapter(index++, pointer) < 0) return false
            val adapter = DxgiAdapter(checkNotNull(pointer.value))
            try {
                // Null output checks support without allocating a Direct3D device.
                if (adapter.hardware() && direct3d.D3D12CreateDevice(adapter.pointer, 0xb000, deviceId, null) >= 0)
                    return true
            } finally { adapter.Release() }
        }
    } finally { factory.Release() }
}

fun prepareDesktopGraphics(cache: File) {
    if (DesktopTarget.os != "windows") return
    val renderer = System.getenv("SKIKO_RENDER_API") ?: System.getProperty("skiko.renderApi")
    if (renderer == null && System.getProperty("skiko.rendering.angle.enabled") == null) {
        // Skiko's software Direct3D adapter is slower than ANGLE. Choose before creating the window.
        // Probe without loading Skiko: its renderer preferences are cached during native initialization.
        val hardware = runCatching { hardwareDirect3D() }.getOrDefault(false)
        if (!hardware) System.setProperty("skiko.rendering.angle.enabled", "true")
    }
    if (System.getProperty("skiko.library.path") != null) return
    if (renderer != "ANGLE" && System.getProperty("skiko.rendering.angle.enabled") != "true") return
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
        val type = marker.takeIf { it.isFile && it.length() <= 16 }?.readText()?.trim()
        when (os) {
            "windows" -> "exe".takeIf { type == "exe" || type == "msi" }
            "macos" -> "dmg".takeIf { type == "dmg" }
            else -> "deb".takeIf { type == "deb" }
        }
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
