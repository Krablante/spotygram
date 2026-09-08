package app.spotygram

import android.content.Context
import androidx.annotation.StringRes

/** Application resources shared by Compose callbacks, background services and TDLib errors. */
object AppText {
    lateinit var context: Context
        private set

    var savedChatId: Long = 0
        private set

    fun initialize(context: Context) {
        this.context = context.applicationContext
        savedChatId =
            context
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getLong("saved_chat_id", 0)
    }

    fun rememberSavedChat(id: Long) {
        savedChatId = id
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putLong("saved_chat_id", id)
            .apply()
    }
}

fun chatTitle(id: Long, title: String): String =
    if (id != 0L && id == AppText.savedChatId) tr(R.string.saved_messages) else title

fun tr(@StringRes id: Int, vararg arguments: Any?): String =
    if (arguments.isEmpty()) AppText.context.getString(id)
    else AppText.context.getString(id, *arguments)
