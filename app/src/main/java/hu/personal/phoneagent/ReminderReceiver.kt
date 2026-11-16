package hu.personal.phoneagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        if (id < 0) return

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminders_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)

        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speaker)
            .setContentTitle(context.getString(R.string.reminder_notification_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        // Letiltott értesítésnél a notify() csendben elnyelődik. Ilyenkor az emlékeztető
        // a tárolóban marad, és az app következő megnyitásakor a csevegésben jelenik meg.
        val posted = NotificationGate.canPost(manager, CHANNEL_ID) && try {
            manager.notify(id, notification)
            true
        } catch (_: SecurityException) {
            false
        }

        if (posted) {
            ReminderStore.remove(context, id)
        }
    }

    companion object {
        const val CHANNEL_ID = "reminders"
        const val EXTRA_ID = "id"
        const val EXTRA_TEXT = "text"
    }
}
