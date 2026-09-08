package app.spotygram

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsSheet(
    app: SpotygramApp,
    library: LibraryState,
    connected: Boolean,
    onConnect: () -> Unit,
    onLogout: () -> Unit,
    onImport: () -> Unit,
    onLocal: () -> Unit,
    onResume: () -> Unit,
) {
    val theme by app.theme.collectAsStateWithLifecycle()
    var wifi by remember { mutableStateOf(app.prefs.getBoolean("wifi_only", false)) }
    val download by app.downloading.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Оформление",
            Modifier.padding(top = 24.dp, bottom = 8.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        listOf("system" to "Как в системе", "light" to "Светлая", "dark" to "Тёмная").forEach {
            (key, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { app.changeTheme(key) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(theme == key, { app.changeTheme(key) })
                Text(label)
            }
        }
        HorizontalDivider(
            Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        )
        val local = library.tracks.filter { it.local }.distinctBy { it.path }
        ListItem(
            headlineContent = { Text("На телефоне") },
            supportingContent = {
                Text("${trackCount(local.size)} · ${bytes(local.sumOf {it.size})}")
            },
            leadingContent = { Icon(Icons.Rounded.DownloadForOffline, null) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
            modifier = Modifier.clickable(onClick = onLocal),
        )
        Text(
            "Загруженные аудиофайлы остаются на устройстве. Удалить копию можно в меню трека.",
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Скачивать только по Wi-Fi")
                Text(
                    "Для кнопки «Скачать», не для прослушивания",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                wifi,
                {
                    wifi = it
                    app.prefs.edit().putBoolean("wifi_only", it).apply()
                },
            )
        }
        if (download.queued > 0)
            Text(
                "Скачивается: ${download.queued} · ${(download.progress*100).toInt()}%",
                Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        ActionRow(Icons.Rounded.Download, "Продолжить загрузки", onResume)
        if (download.queued > 0)
            ActionRow(Icons.Rounded.Close, "Остановить загрузки") {
                app.startService(
                    android.content.Intent(app, DownloadService::class.java).setAction("cancel")
                )
            }
        ActionRow(Icons.Rounded.Add, "Добавить файлы с телефона", onImport)
        HorizontalDivider(
            Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        )
        ActionRow(
            Icons.Rounded.Forum,
            if (connected) "Выйти из Telegram" else "Подключить Telegram",
            if (connected) onLogout else onConnect,
        )
        Text(
            "Spotygram ${BuildConfig.VERSION_NAME}",
            Modifier.padding(top = 20.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Личный музыкальный плеер на Telegram API. Без своего сервера, рекламы приложения и аналитики. Музыка и сессия остаются на телефоне.",
            Modifier.padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}
