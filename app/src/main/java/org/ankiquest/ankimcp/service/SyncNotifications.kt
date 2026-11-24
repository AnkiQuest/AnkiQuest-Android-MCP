package org.ankiquest.ankimcp.service

import org.ankiquest.ankimcp.R
import org.ankiquest.ankimcp.anki.AnkiDroidSync
import org.ankiquest.ankimcp.anki.FlashcardAppResolver
import org.ankiquest.ankimcp.anki.FlashcardAppState
import org.ankiquest.ankimcp.anki.SyncRequest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Posts "Tap to sync AnkiDroid"; the tap sends AnkiDroid's `com.ichi2.anki.DO_SYNC` intent, which
 * its `IntentHandler` activity (exported, category DEFAULT) turns into a normal sync, at most one
 * every 2 minutes (AnkiDroid's `INTENT_SYNC_MIN_INTERVAL`, v2.25.3) (D58).
 */
class NotificationSyncRequester(
    context: Context,
    private val permission: NotificationPermissionGate,
    private val apps: FlashcardAppResolver,
) : AnkiDroidSync {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    override fun requestSync(): SyncRequest {
        // The flashcard app in use (D65); AnkiQuest handles the same intent.
        val app = (apps.resolve() as? FlashcardAppState.Ready)?.app ?: return SyncRequest.AnkiDroidMissing
        val intent = Intent(DO_SYNC_ACTION)
            .setPackage(app.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (resolves(intent).not()) return SyncRequest.AnkiDroidMissing
        permission.blockReason("\"Tap to sync AnkiDroid\" notification", CHANNEL_ID)?.let {
            return SyncRequest.NotificationBlocked(it)
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.sync_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = appContext.getString(R.string.sync_channel_description) },
        )
        val sync = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tunnel)
            .setContentTitle(appContext.getString(R.string.sync_request_title))
            .setContentText(appContext.getString(R.string.sync_request_text))
            .setContentIntent(sync)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return SyncRequest.NotificationPosted
    }

    private fun resolves(intent: Intent): Boolean {
        val pm = appContext.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, 0)
        }
        return info != null
    }

    companion object {
        const val DO_SYNC_ACTION = "com.ichi2.anki.DO_SYNC"
        private const val CHANNEL_ID = NotificationChannels.SYNC
        private const val NOTIFICATION_ID = 3
    }
}
