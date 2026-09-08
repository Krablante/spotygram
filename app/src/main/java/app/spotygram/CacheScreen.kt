package app.spotygram

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
fun CacheScreen(app: SpotygramApp, connected: Boolean, onBack: () -> Unit, onConnect: () -> Unit) {
    val state by app.musicCache.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.musicCache.refresh() }
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, tr(R.string.back)) }
            Text(
                tr(R.string.music_cache),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { app.musicCache.refresh() },
                enabled = !state.loading && !state.clearing,
            ) {
                Icon(Icons.Rounded.Refresh, tr(R.string.refresh_cache_size))
            }
        }
        Spacer(Modifier.height(24.dp))
        if (state.loading || state.clearing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                tr(if (state.clearing) R.string.cache_clearing else R.string.cache_calculating),
                Modifier.padding(vertical = 16.dp),
            )
        }
        state.bytes?.let { size ->
            Text(cacheBytes(size), style = MaterialTheme.typography.displaySmall)
            Text(
                if (size == 0L) tr(R.string.cache_empty)
                else
                    AppText.context.resources.getQuantityString(
                        R.plurals.cache_files,
                        state.files,
                        state.files,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(tr(R.string.cache_description), Modifier.padding(top = 24.dp))
        Text(
            tr(R.string.cache_preserved),
            Modifier.padding(top = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.freed?.let {
            Text(
                tr(R.string.cache_freed, cacheBytes(it)),
                Modifier.padding(top = 20.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            if ((state.bytes ?: 0) > 0)
                Text(tr(R.string.cache_remaining), style = MaterialTheme.typography.bodySmall)
        }
        state.error?.let {
            Text(it, Modifier.padding(top = 20.dp), color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
        if (!connected && (state.bytes ?: 0L) > 0L) {
            Text(tr(R.string.cache_connect_required), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Text(tr(R.string.connect_telegram))
            }
        } else {
            Button(
                onClick = { confirm = true },
                enabled =
                    connected &&
                        !state.loading &&
                        !state.clearing &&
                        state.error == null &&
                        (state.bytes ?: 0L) > 0L,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.DeleteSweep, null)
                Spacer(Modifier.width(8.dp))
                Text(tr(R.string.clear_music_cache))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(tr(R.string.cache_confirm_title)) },
            text = {
                Text(
                    tr(R.string.cache_confirm_help),
                    Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        app.musicCache.clear()
                    }
                ) {
                    Text(tr(R.string.stop_and_clear_cache))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(tr(R.string.cancel)) }
            },
        )
}
