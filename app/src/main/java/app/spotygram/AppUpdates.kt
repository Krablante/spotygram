package app.spotygram

import android.content.Intent
import android.net.Uri
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

data class UpdateState(
    val automatic: Boolean = true,
    val checking: Boolean = false,
    val version: String = "",
    val dismissed: String = "",
    val result: Int = 0,
)

/** One foreground-triggered request, with a durable attempt budget; no scheduled work. */
class AppUpdates(private val app: SpotygramApp) {
    private val prefs = app.getSharedPreferences("updates", android.content.Context.MODE_PRIVATE)
    val installer by lazy { UpdateInstaller(app) }
    val showSettings = MutableStateFlow(false)
    var asset: UpdateAsset? = UpdateAsset.fromJson(prefs.getString("asset", null))
        private set

    val state =
        MutableStateFlow(
            UpdateState(
                automatic = prefs.getBoolean("automatic", true),
                version = newer(prefs.getString("version", "").orEmpty()),
                dismissed = prefs.getString("dismissed", "").orEmpty(),
            )
        )

    fun automatic(enabled: Boolean) {
        prefs.edit().putBoolean("automatic", enabled).apply()
        state.value = state.value.copy(automatic = enabled)
    }

    fun dismiss(version: String = state.value.version) {
        prefs.edit().putString("dismissed", version).apply()
        state.value = state.value.copy(dismissed = version)
    }

    fun openRelease() {
        val version = state.value.version
        if (newer(version).isEmpty()) return
        try {
            app.startActivity(
                Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/Krablante/spotygram/releases/tag/v$version"),
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: android.content.ActivityNotFoundException) {
            app.notices.tryEmit(tr(R.string.update_no_browser))
        }
    }

    // Called on the main thread, so checking reserves the single request before launching IO.
    fun check(manual: Boolean = false) {
        if (state.value.checking || (!manual && !state.value.automatic)) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong("attempt", 0)
        val retry = prefs.getLong("retry_after", 0)
        val interval = if (manual) 60_000L else 86_400_000L
        if (last > now) {
            prefs.edit().putLong("attempt", now).putLong("retry_after", 0).apply()
        }
        if (last > now || now - last < interval || now < retry) {
            if (manual) state.value = state.value.copy(result = R.string.update_wait)
            return
        }
        state.value = state.value.copy(checking = true, result = 0)
        app.scope.launch {
            try {
                val version =
                    withContext(Dispatchers.IO) {
                        // Persist BEFORE network access, including failed attempts and process
                        // restarts.
                        kotlin.check(prefs.edit().putLong("attempt", now).commit())
                        fetch(now)
                    }
                state.value =
                    state.value.copy(
                        version = newer(version),
                        result =
                            if (manual && newer(version).isEmpty()) R.string.update_current else 0,
                    )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (manual) state.value = state.value.copy(result = R.string.update_failed)
            } finally {
                state.value = state.value.copy(checking = false)
            }
        }
    }

    private fun fetch(now: Long): String {
        val connection =
            URL("https://api.github.com/repos/Krablante/spotygram/releases/latest").openConnection()
                as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Spotygram/${BuildConfig.VERSION_NAME}")
            prefs
                .getString("etag", null)
                ?.takeIf { asset != null }
                ?.let {
                    connection.setRequestProperty("If-None-Match", it)
                }
            val code = connection.responseCode
            if (code == 304) return prefs.getString("version", "").orEmpty()
            if (code == 403 || code == 429) {
                val seconds = connection.getHeaderField("Retry-After")?.toLongOrNull()
                val reset = connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                val until =
                    maxOf(
                        now + 3_600_000L,
                        now + (seconds ?: 0).coerceIn(0, 86_400) * 1000,
                        (reset ?: 0).coerceIn(0, (now + 86_400_000L) / 1000) * 1000,
                    )
                prefs.edit().putLong("retry_after", until).commit()
            }
            kotlin.check(code == 200)
            val data =
                connection.inputStream.use { input ->
                    val buffer = ByteArray(262_144)
                    var length = 0
                    while (length < buffer.size) {
                        val count = input.read(buffer, length, buffer.size - length)
                        if (count == -1) break
                        length += count
                    }
                    kotlin.check(length < buffer.size)
                    String(buffer, 0, length, Charsets.UTF_8)
                }
            val release = JSONObject(data)
            kotlin.check(!release.getBoolean("draft") && !release.getBoolean("prerelease"))
            val tag = release.getString("tag_name")
            kotlin.check(tag.startsWith("v") && parts(tag.drop(1)) != null)
            val version = tag.drop(1)
            val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            val suffix = if (abi == "arm64-v8a") "arm64" else "x86_64"
            kotlin.check(abi != null)
            val assets = release.getJSONArray("assets")
            val selected =
                (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull {
                        it.optString("name") == "spotygram-$version-$suffix.apk" &&
                            it.optString("state") == "uploaded" &&
                            it.optLong("size") > 0
                    }
            kotlin.check(selected != null)
            val candidate =
                UpdateAsset(
                    version,
                    selected.getLong("size"),
                    selected.getString("digest").removePrefix("sha256:"),
                    suffix,
                )
            kotlin.check(candidate.valid())
            kotlin.check(
                prefs
                    .edit()
                    .putString("version", version)
                    .putString("asset", candidate.toJson())
                    .putString("etag", connection.getHeaderField("ETag"))
                    .putLong("retry_after", 0)
                    .commit()
            )
            asset = candidate
            return version
        } finally {
            connection.disconnect()
        }
    }

    private fun parts(version: String): List<Int>? {
        if (!Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}").matches(version)) return null
        return version.split('.').map { it.toInt() }
    }

    private fun newer(version: String): String {
        val candidate = parts(version) ?: return ""
        val installed = parts(BuildConfig.VERSION_NAME) ?: return ""
        for (i in 0..2) {
            if (candidate[i] > installed[i]) return version
            if (candidate[i] < installed[i]) return ""
        }
        return ""
    }
}
