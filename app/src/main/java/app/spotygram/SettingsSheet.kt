package app.spotygram

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
    onCache: () -> Unit,
) {
    val theme by app.theme.collectAsStateWithLifecycle()
    val hideDuplicates by app.hideDuplicates.collectAsStateWithLifecycle()
    val resumeLongAudio by app.resumeLongAudio.collectAsStateWithLifecycle()
    val resumeMinutes by app.resumeMinutes.collectAsStateWithLifecycle()
    var editingResume by rememberSaveable { mutableStateOf(false) }
    var wifi by remember { mutableStateOf(app.prefs.getBoolean("wifi_only", false)) }
    val download by app.downloading.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
    ) {
        Text(tr(R.string.settings), style = MaterialTheme.typography.headlineMedium)
        Text(
            tr(R.string.appearance),
            Modifier.padding(top = 18.dp, bottom = 8.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        val selectedTheme =
            if (theme == "system") {
                if (isSystemInDarkTheme()) "dark" else "light"
            } else theme
        Row(Modifier.fillMaxWidth().liquidGlass(22.dp).padding(4.dp)) {
            listOf("light" to tr(R.string.light_theme), "dark" to tr(R.string.dark_theme))
                .forEach { (key, label) ->
                    Box(
                        Modifier.weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .then(
                                if (selectedTheme == key)
                                    Modifier.liquidGlass(24.dp, tint = spectrumAccent(0))
                                else Modifier
                            )
                            .clickable { app.changeTheme(key) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color =
                                if (selectedTheme == key) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
        }
        ActionRow(Icons.Rounded.Language, tr(R.string.language)) {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                app.startActivity(
                    android.content
                        .Intent(
                            android.provider.Settings.ACTION_APP_LOCALE_SETTINGS,
                            android.net.Uri.parse("package:${app.packageName}"),
                        )
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else app.notices.tryEmit(tr(R.string.language_phone_settings))
        }
        Text(
            tr(R.string.language_help),
            Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(
            Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth()
                .toggleable(
                    value = resumeLongAudio,
                    role = Role.Switch,
                    onValueChange = app::changeResumeLongAudio,
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(tr(R.string.resume_long_audio))
                Text(
                    tr(R.string.resume_long_audio_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GlassSwitch(checked = resumeLongAudio, onCheckedChange = null)
        }
        ListItem(
            headlineContent = { Text(tr(R.string.resume_threshold)) },
            supportingContent = { Text(tr(R.string.resume_threshold_minutes, resumeMinutes)) },
            leadingContent = { Icon(Icons.Rounded.History, null) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
            modifier = Modifier.clickable { editingResume = true },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        HorizontalDivider(
            Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth()
                .toggleable(
                    value = hideDuplicates,
                    role = Role.Switch,
                    onValueChange = app::changeHideDuplicates,
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(tr(R.string.hide_duplicates))
                Text(
                    tr(R.string.hide_duplicates_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GlassSwitch(checked = hideDuplicates, onCheckedChange = null)
        }
        val local =
            remember(library, hideDuplicates) {
                if (hideDuplicates) library.duplicates.view(library.localTracks)
                else library.localTracks
            }
        ListItem(
            headlineContent = { Text(tr(R.string.on_device)) },
            supportingContent = {
                Text("${trackCount(local.size)} · ${bytes(library.localSize)}")
            },
            leadingContent = { Icon(Icons.Rounded.DownloadForOffline, null) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
            modifier = Modifier.clickable(onClick = onLocal),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Text(
            tr(R.string.local_storage_help),
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val listening by app.listeningCache.state.collectAsStateWithLifecycle()
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(tr(R.string.discard_listened))
                Text(
                    tr(R.string.discard_listened_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GlassSwitch(
                checked = listening.enabled,
                onCheckedChange = { app.listeningCache.setEnabled(it) },
                enabled = !listening.changing,
            )
        }
        Text(
            tr(R.string.discard_listened_limits),
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (listening.changing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (listening.deferred) {
            Text(
                tr(R.string.temporary_cleanup_deferred),
                Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { app.listeningCache.retry() }) {
                Text(tr(R.string.retry_temporary_cleanup))
            }
        }
        ListItem(
            headlineContent = { Text(tr(R.string.clear_music_cache)) },
            supportingContent = { Text(tr(R.string.cache_settings_help)) },
            leadingContent = { Icon(Icons.Rounded.DeleteSweep, null) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
            modifier = Modifier.clickable(onClick = onCache),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr(R.string.wifi_only))
                Text(
                    tr(R.string.wifi_only_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GlassSwitch(
                wifi,
                {
                    wifi = it
                    app.prefs.edit().putBoolean("wifi_only", it).apply()
                },
            )
        }
        if (download.queued > 0)
            Text(
                tr(R.string.download_progress, download.queued, (download.progress * 100).toInt()),
                Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        ActionRow(Icons.Rounded.Download, tr(R.string.resume_downloads), onResume)
        if (download.queued > 0)
            ActionRow(Icons.Rounded.Close, tr(R.string.stop_downloads)) {
                app.startService(
                    android.content.Intent(app, DownloadService::class.java).setAction("cancel")
                )
            }
        ActionRow(Icons.Rounded.Add, tr(R.string.import_files), onImport)
        HorizontalDivider(
            Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        )
        ActionRow(
            Icons.Rounded.Forum,
            if (connected) tr(R.string.logout_telegram) else tr(R.string.connect_telegram),
            if (connected) onLogout else onConnect,
        )
        UpdateSettings(app)
        Text(
            "Spotygram ${BuildConfig.VERSION_NAME}",
            Modifier.padding(top = 20.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            tr(R.string.app_description),
            Modifier.padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
    if (editingResume) {
        var value by rememberSaveable { mutableStateOf(resumeMinutes.toString()) }
        val minutes = value.toIntOrNull()?.takeIf { it > 0 }
        val apply = {
            if (minutes != null) {
                app.changeResumeMinutes(minutes)
                editingResume = false
            }
        }
        GlassDialog(
            onDismissRequest = { editingResume = false },
            title = { Text(tr(R.string.resume_threshold)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(tr(R.string.resume_threshold_help))
                    OutlinedTextField(
                        value = value,
                        onValueChange = { input ->
                            if (input.length <= 10 && input.all { it in '0'..'9' }) value = input
                        },
                        label = { Text(tr(R.string.resume_minutes_label)) },
                        singleLine = true,
                        isError = minutes == null,
                        supportingText =
                            if (minutes == null) {
                                { Text(tr(R.string.resume_minutes_error)) }
                            } else null,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                        keyboardActions = KeyboardActions(onDone = { apply() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = apply, enabled = minutes != null) { Text(tr(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingResume = false }) { Text(tr(R.string.cancel)) }
            },
        )
    }
}
