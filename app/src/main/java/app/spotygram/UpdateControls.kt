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
    val install by app.updates.installer.state.collectAsStateWithLifecycle()
    val version = install.version.ifEmpty { update.version }
    if (
        (update.automatic || install.phase != InstallPhase.IDLE) &&
            version.isNotEmpty() &&
            update.dismissed != version
    ) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr(R.string.update_available, version),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(onClick = { app.updates.dismiss(version) }) {
                        Icon(Icons.Rounded.Close, tr(R.string.update_dismiss))
                    }
                }
                InstallControls(app, install)
            }
        }
    }
}

@Composable
private fun InstallControls(app: SpotygramApp, install: InstallState) {
    when (install.phase) {
        InstallPhase.DOWNLOADING -> {
            if (install.total > 0)
                LinearProgressIndicator(
                    progress = { (install.downloaded.toFloat() / install.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            Text(
                tr(
                    R.string.update_download_progress,
                    bytes(install.downloaded),
                    bytes(install.total),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { app.updates.installer.cancel() }) {
                Text(tr(R.string.update_cancel_download))
            }
        }
        InstallPhase.VERIFYING,
        InstallPhase.CANCELLING -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                tr(
                    if (install.phase == InstallPhase.VERIFYING) R.string.update_verifying
                    else R.string.update_cancelling
                ),
                Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            if (install.phase == InstallPhase.VERIFYING)
                TextButton(onClick = { app.updates.installer.cancel() }) {
                    Text(tr(R.string.update_cancel_download))
                }
        }
        else -> {
            Button(onClick = { app.updates.installer.start(app.updates.asset) }) {
                Text(
                    tr(
                        if (install.phase == InstallPhase.READY) R.string.update_install
                        else R.string.update_download_install
                    )
                )
            }
            if (install.phase == InstallPhase.READY)
                TextButton(onClick = { app.updates.installer.cancel() }) {
                    Text(tr(R.string.update_delete_download))
                }
        }
    }
    if (install.error != 0)
        Text(
            tr(install.error),
            Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
}

@Composable
fun UpdateSettings(app: SpotygramApp) {
    val update by app.updates.state.collectAsStateWithLifecycle()
    val install by app.updates.installer.state.collectAsStateWithLifecycle()
    Text(
        tr(R.string.updates),
        Modifier.padding(top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
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
    val version = install.version.ifEmpty { update.version }
    if (version.isNotEmpty()) {
        Text(tr(R.string.update_available, version), Modifier.padding(bottom = 8.dp))
        InstallControls(app, install)
        TextButton(onClick = { app.updates.openRelease() }) { Text(tr(R.string.update_view)) }
    } else if (install.error != 0)
        Text(tr(install.error), style = MaterialTheme.typography.bodySmall)
}
