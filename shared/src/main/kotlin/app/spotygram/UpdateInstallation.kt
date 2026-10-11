package app.spotygram

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

data class UpdateAsset(val version: String, val size: Long, val sha256: String, val abi: String) {
    val url get() = "https://github.com/Krablante/spotygram/releases/download/v$version/" +
        AppText.platform.assetName(version, abi)

    fun valid() = Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}").matches(version) &&
        size in 1..(if (abi in setOf("arm64", "x86_64")) 268_435_456L else 536_870_912L) &&
        Regex("[0-9a-f]{64}").matches(sha256) &&
        abi in setOf("arm64", "x86_64", "windows-x64", "linux-x64", "macos-x64", "macos-arm64")

    fun newerThanInstalled(): Boolean {
        val candidate = version.split('.').map { it.toInt() }
        val installed = BuildConfig.VERSION_NAME.split('.').map { it.toInt() }
        for (i in 0..2) if (candidate[i] != installed[i]) return candidate[i] > installed[i]
        return false
    }

    fun toJson() = JSONObject().put("version", version).put("size", size)
        .put("sha256", sha256).put("abi", abi).toString()

    companion object {
        fun fromJson(text: String?): UpdateAsset? = runCatching {
            val value = JSONObject(text ?: return null)
            UpdateAsset(value.getString("version"), value.getLong("size"),
                value.getString("sha256"), value.getString("abi")).takeIf { it.valid() }
        }.getOrNull()
    }
}

enum class InstallPhase { IDLE, DOWNLOADING, VERIFYING, READY, CANCELLING }
data class InstallState(
    val phase: InstallPhase = InstallPhase.IDLE,
    val version: String = "",
    val downloaded: Long = 0,
    val total: Long = 0,
    val error: Int = 0,
)

interface UpdateInstallation {
    val state: MutableStateFlow<InstallState>
    fun start(asset: UpdateAsset?)
    fun cancel()
}
