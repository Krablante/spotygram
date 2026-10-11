package app.spotygram

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.Structure

interface MpvNative : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(handle: Pointer): Int
    fun mpv_set_option_string(handle: Pointer, name: String, value: String): Int
    fun mpv_set_property_string(handle: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(handle: Pointer, name: String): Pointer?
    fun mpv_get_property(handle: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_command_async(handle: Pointer, ticket: Long, arguments: Pointer): Int
    fun mpv_command(handle: Pointer, arguments: Pointer): Int
    fun mpv_observe_property(handle: Pointer, ticket: Long, name: String, format: Int): Int
    fun mpv_wait_event(handle: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(handle: Pointer)
    fun mpv_terminate_destroy(handle: Pointer)
    fun mpv_free(pointer: Pointer)
    fun mpv_stream_cb_add_ro(handle: Pointer, protocol: String, userData: Pointer?, open: StreamOpen): Int
}

private interface NumericLocale : Library {
    fun setlocale(category: Int, locale: String): Pointer?
}

fun interface StreamOpen : Callback { fun invoke(userData: Pointer?, uri: String, info: Pointer): Int }
fun interface StreamRead : Callback { fun invoke(cookie: Pointer?, buffer: Pointer, size: Long): Long }
fun interface StreamSeek : Callback { fun invoke(cookie: Pointer?, position: Long): Long }
fun interface StreamSize : Callback { fun invoke(cookie: Pointer?): Long }
fun interface StreamClose : Callback { fun invoke(cookie: Pointer?) }
fun interface StreamCancel : Callback { fun invoke(cookie: Pointer?) }

@Structure.FieldOrder("cookie", "read", "seek", "sizeCallback", "closeCallback", "cancel")
class MpvStreamInfo(pointer: Pointer) : Structure(pointer) {
    @JvmField var cookie: Pointer? = null
    @JvmField var read: StreamRead? = null
    @JvmField var seek: StreamSeek? = null
    @JvmField var sizeCallback: StreamSize? = null
    @JvmField var closeCallback: StreamClose? = null
    @JvmField var cancel: StreamCancel? = null
}

@Structure.FieldOrder("id", "error", "ticket", "data")
class MpvEvent(pointer: Pointer) : Structure(pointer) {
    @JvmField var id: Int = 0
    @JvmField var error: Int = 0
    @JvmField var ticket: Long = 0
    @JvmField var data: Pointer? = null
    init { read() }
}

@Structure.FieldOrder("name", "format", "data")
class MpvProperty(pointer: Pointer) : Structure(pointer) {
    @JvmField var name: Pointer? = null
    @JvmField var format: Int = 0
    @JvmField var data: Pointer? = null
    init { read() }
}

object Mpv {
    const val FLAG = 3
    const val INT64 = 4
    const val DOUBLE = 5
    const val EVENT_SHUTDOWN = 1
    const val EVENT_START_FILE = 6
    const val EVENT_END_FILE = 7
    const val EVENT_FILE_LOADED = 8
    const val EVENT_PLAYBACK_RESTART = 21
    const val EVENT_PROPERTY_CHANGE = 22
    val api: MpvNative by lazy {
        Native.load(DesktopTarget.library("mpv"), MpvNative::class.java,
            mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
    }

    fun create(options: Map<String, String>): Pointer {
        // AWT adopts the system locale; libmpv requires C decimal syntax in its native threads.
        val numeric = Native.load(if (DesktopTarget.os == "windows") "ucrtbase" else com.sun.jna.Platform.C_LIBRARY_NAME,
            NumericLocale::class.java)
        checkNotNull(numeric.setlocale(if (DesktopTarget.os == "linux") 1 else 4, "C"))
        val handle = checkNotNull(api.mpv_create()) { "Audio engine could not start" }
        try {
            options.forEach { (name, value) ->
                check(api.mpv_set_option_string(handle, name, value) >= 0) { "Audio option unavailable: $name" }
            }
            check(api.mpv_initialize(handle) >= 0) { "Audio engine could not initialize" }
            return handle
        } catch (error: Throwable) { api.mpv_terminate_destroy(handle); throw error }
    }
    fun command(handle: Pointer, vararg values: String): Int =
        api.mpv_command_async(handle, 0, StringArray(values, "UTF-8"))
    fun commandSync(handle: Pointer, vararg values: String): Int =
        api.mpv_command(handle, StringArray(values, "UTF-8"))
    fun string(handle: Pointer, name: String): String? {
        val pointer = api.mpv_get_property_string(handle, name) ?: return null
        return try { pointer.getString(0, "UTF-8") } finally { api.mpv_free(pointer) }
    }
    fun double(handle: Pointer, name: String, fallback: Double = 0.0): Double = Memory(8).use { value ->
        if (api.mpv_get_property(handle, name, DOUBLE, value) < 0) fallback else value.getDouble(0)
    }
    fun integer(handle: Pointer, name: String, fallback: Long = -1): Long = Memory(8).use { value ->
        if (api.mpv_get_property(handle, name, INT64, value) < 0) fallback else value.getLong(0)
    }
    fun flag(handle: Pointer, name: String): Boolean = Memory(4).use { value ->
        api.mpv_get_property(handle, name, FLAG, value) >= 0 && value.getInt(0) != 0
    }
}
