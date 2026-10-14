package app.spotygram

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One decorative sticker per visible session; selection stays local to this device. */
class HeaderSticker(private val preferences: Preferences) {
    private val choices = mapOf(
        "wave" to R.drawable.emoji_wave,
        "peace" to R.drawable.emoji_peace,
        "horns" to R.drawable.emoji_horns,
        "vulcan" to R.drawable.emoji_vulcan,
        "okay" to R.drawable.emoji_okay,
        "love" to R.drawable.emoji_love,
        "crossed" to R.drawable.emoji_crossed,
        "finger_heart" to R.drawable.emoji_finger_heart,
        "thumbs_up" to R.drawable.emoji_thumbs_up,
        "thumbs_down" to R.drawable.emoji_thumbs_down,
        "fist" to R.drawable.emoji_fist,
        "fist_bump" to R.drawable.emoji_fist_bump,
        "clap" to R.drawable.emoji_clap,
        "raised_hands" to R.drawable.emoji_raised_hands,
        "open_hands" to R.drawable.emoji_open_hands,
        "palms_up" to R.drawable.emoji_palms_up,
        "handshake" to R.drawable.emoji_handshake,
        "heart_hands" to R.drawable.emoji_heart_hands,
        "smile" to R.drawable.emoji_smile,
        "relieved" to R.drawable.emoji_relieved,
        "sunglasses" to R.drawable.emoji_sunglasses,
        "starstruck" to R.drawable.emoji_starstruck,
        "grin" to R.drawable.emoji_grin,
        "beaming" to R.drawable.emoji_beaming,
        "sweat" to R.drawable.emoji_sweat,
        "laugh" to R.drawable.emoji_laugh,
        "kiss" to R.drawable.emoji_kiss,
        "hearts" to R.drawable.emoji_hearts,
        "thinking" to R.drawable.emoji_thinking,
        "eyebrow" to R.drawable.emoji_eyebrow,
        "eye_roll" to R.drawable.emoji_eye_roll,
        "sleep" to R.drawable.emoji_sleep,
        "unamused" to R.drawable.emoji_unamused,
        "pensive" to R.drawable.emoji_pensive,
        "cry" to R.drawable.emoji_cry,
        "sob" to R.drawable.emoji_sob,
        "angry" to R.drawable.emoji_angry,
        "steam" to R.drawable.emoji_steam,
        "astonished" to R.drawable.emoji_astonished,
        "surprised" to R.drawable.emoji_surprised,
    )
    private var selected = choose(preferences.getString("last", null))
    private val image = MutableStateFlow(selected.value)
    val drawable = image.asStateFlow()
    private var visible = false
    private var hiddenAt: Long? = null

    init {
        save()
    }

    /** Called by the platform's window/activity visibility events, on the UI thread. */
    fun visibility(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) {
            val since = hiddenAt
            // Wall time also includes device sleep; this is a cosmetic session boundary.
            if (since != null && System.currentTimeMillis() - since >= 30L * 60 * 1000) {
                selected = choose(selected.key)
                image.value = selected.value
                save()
            }
            hiddenAt = null
        } else {
            hiddenAt = System.currentTimeMillis()
        }
    }

    private fun choose(previous: String?) = choices.entries.filter { it.key != previous }.random()

    private fun save() {
        preferences.edit().putString("last", selected.key).apply()
    }
}
