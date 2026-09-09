package app.spotygram

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun UpdateBanner(app: SpotygramApp) {
    val update by app.updates.state.collectAsStateWithLifecycle()
    if (update.automatic && update.version.isNotEmpty() && update.dismissed != update.version) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr(R.string.update_available, update.version),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { app.updates.openRelease() }) {
                    Text(tr(R.string.update_view))
                }
                IconButton(onClick = { app.updates.dismiss() }) {
                    Icon(Icons.Rounded.Close, tr(R.string.update_dismiss))
                }
            }
        }
    }
}

@Composable
fun UpdateSettings(app: SpotygramApp) {
    val update by app.updates.state.collectAsStateWithLifecycle()
    Text(
        tr(R.string.updates),
        Modifier.padding(top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr(R.string.update_automatic))
            Text(
                tr(R.string.update_automatic_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = update.automatic, onCheckedChange = { app.updates.automatic(it) })
    }
    TextButton(onClick = { app.updates.check(manual = true) }, enabled = !update.checking) {
        Text(tr(if (update.checking) R.string.update_checking else R.string.update_check))
    }
    if (update.result != 0) Text(tr(update.result), style = MaterialTheme.typography.bodySmall)
    if (update.version.isNotEmpty()) {
        Text(tr(R.string.update_available, update.version))
        TextButton(onClick = { app.updates.openRelease() }) { Text(tr(R.string.update_view)) }
    }
}
