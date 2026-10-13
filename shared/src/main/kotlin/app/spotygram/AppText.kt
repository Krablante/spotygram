package app.spotygram

import androidx.compose.runtime.mutableIntStateOf

/** Application resources shared by Compose callbacks, background services and TDLib errors. */
object AppText {
    private val languageRevision = mutableIntStateOf(0)
    fun languageChanged() { languageRevision.intValue++ }
    fun string(id: Int, vararg arguments: Any?): String {
        languageRevision.intValue
        return platform.string(id, *arguments)
    }
    fun plural(id: Int, quantity: Int, vararg arguments: Any?): String {
        languageRevision.intValue
        return platform.plural(id, quantity, *arguments)
    }
    lateinit var platform: Platform
        private set

    var savedChatId: Long = 0
        private set

    fun initialize(platform: Platform) {
        this.platform = platform
        savedChatId = platform.preferences("settings").getLong("saved_chat_id", 0)
    }

    fun rememberSavedChat(id: Long) {
        savedChatId = id
        platform
            .preferences("settings")
            .edit()
            .putLong("saved_chat_id", id)
            .apply()
    }
}

fun chatTitle(id: Long, title: String): String =
    if (id != 0L && id == AppText.savedChatId) tr(R.string.saved_messages) else title

fun tr(id: Int, vararg arguments: Any?): String = AppText.string(id, *arguments)
