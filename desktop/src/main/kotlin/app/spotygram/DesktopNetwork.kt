package app.spotygram

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.File

/** Queried only by the active download queue; there is no idle network polling job. */
class DesktopNetwork {
    private var checkedAt = Long.MIN_VALUE
    private var result = false
    @Synchronized fun wifi(): Boolean {
        val now = System.nanoTime() / 1_000_000
        if (checkedAt != Long.MIN_VALUE && now - checkedAt < 2000) return result
        result = runCatching { when (DesktopTarget.os) {
            "windows" -> windowsWifi()
            "macos" -> macWifi()
            else -> linuxWifi()
        } }.getOrDefault(false)
        checkedAt = now
        return result
    }
    private fun linuxWifi(): Boolean {
        val route = File("/proc/net/route").readLines().drop(1).map { it.trim().split(Regex("\\s+")) }
            .filter { it.size > 7 && it[1] == "00000000" && (it[3].toInt(16) and 1) != 0 }
            .minByOrNull { it[6].toIntOrNull() ?: Int.MAX_VALUE }?.first()
            ?: File("/proc/net/ipv6_route").takeIf(File::isFile)?.readLines()
                ?.map { it.trim().split(Regex("\\s+")) }
                ?.firstOrNull { it.size >= 10 && it[0].all { c -> c == '0' } && it[1] == "00" }?.last()
            ?: return false
        return File("/sys/class/net/$route/wireless").isDirectory || File("/sys/class/net/$route/phy80211").exists()
    }
    private interface IpHelper : StdCallLibrary {
        fun GetBestInterface(destination: Int, index: IntByReference): Int
        fun GetIfEntry(row: Pointer): Int
    }
    private val ip by lazy { Native.load("Iphlpapi", IpHelper::class.java) }
    private fun windowsWifi(): Boolean {
        val index = IntByReference()
        if (ip.GetBestInterface(0x08080808, index) != 0) return false
        return Memory(1024).use { row ->
            row.clear()
            row.setInt(512, index.value)
            ip.GetIfEntry(row) == 0 && row.getInt(516) == 71
        }
    }
    private interface SystemConfiguration : Library {
        fun SCDynamicStoreCopyValue(store: Pointer?, key: Pointer): Pointer?
        fun SCNetworkInterfaceCopyAll(): Pointer?
        fun SCNetworkInterfaceGetBSDName(network: Pointer): Pointer?
        fun SCNetworkInterfaceGetInterfaceType(network: Pointer): Pointer?
    }
    private interface CoreFoundation : Library {
        fun CFStringCreateWithCString(allocator: Pointer?, value: String, encoding: Int): Pointer
        fun CFStringGetCString(value: Pointer, buffer: Pointer, size: Long, encoding: Int): Byte
        fun CFDictionaryGetValue(dictionary: Pointer, key: Pointer): Pointer?
        fun CFArrayGetCount(array: Pointer): Long
        fun CFArrayGetValueAtIndex(array: Pointer, index: Long): Pointer
        fun CFRelease(value: Pointer)
    }
    private val sc by lazy { Native.load("/System/Library/Frameworks/SystemConfiguration.framework/SystemConfiguration",
        SystemConfiguration::class.java) }
    private val cf by lazy { Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
        CoreFoundation::class.java) }
    private fun text(value: Pointer?): String? {
        if (value == null) return null
        return Memory(1024).use { buffer ->
            if (cf.CFStringGetCString(value, buffer, 1024, 0x08000100).toInt() != 0)
                buffer.getString(0, "UTF-8") else null
        }
    }
    private fun primaryNetwork(): String? {
        for (protocol in listOf("IPv4", "IPv6")) {
            val key = cf.CFStringCreateWithCString(null, "State:/Network/Global/$protocol", 0x08000100)
            val property = cf.CFStringCreateWithCString(null, "PrimaryInterface", 0x08000100)
            try {
                val value = sc.SCDynamicStoreCopyValue(null, key) ?: continue
                try { text(cf.CFDictionaryGetValue(value, property))?.let { return it } }
                finally { cf.CFRelease(value) }
            } finally { cf.CFRelease(property); cf.CFRelease(key) }
        }
        return null
    }
    private fun macWifi(): Boolean {
        val name = primaryNetwork() ?: return false
        val interfaces = sc.SCNetworkInterfaceCopyAll() ?: return false
        try {
            for (i in 0 until cf.CFArrayGetCount(interfaces)) {
                val network = cf.CFArrayGetValueAtIndex(interfaces, i)
                if (text(sc.SCNetworkInterfaceGetBSDName(network)) == name)
                    return text(sc.SCNetworkInterfaceGetInterfaceType(network)) == "IEEE80211"
            }
            return false
        } finally { cf.CFRelease(interfaces) }
    }
}
