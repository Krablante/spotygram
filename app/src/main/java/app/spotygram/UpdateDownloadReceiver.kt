package app.spotygram

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** A system download-notification click returns to the in-app update controls. */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_NOTIFICATION_CLICKED) return
        val id =
            context
                .getSharedPreferences("update_install", Context.MODE_PRIVATE)
                .getLong("download_id", 0)
        if (
            id == 0L ||
                intent
                    .getLongArrayExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS)
                    ?.contains(id) != true
        )
            return
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .setAction("app.spotygram.SHOW_UPDATES")
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
        )
    }
}
