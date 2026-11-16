package hu.personal.phoneagent

import android.app.NotificationManager
import android.content.Context

/** Megmondja, hogy egy értesítés valóban meg fog-e jelenni. */
object NotificationGate {
    fun canPost(manager: NotificationManager, channelId: String): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(channelId) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Igaz, ha az app értesítései és mindkét csatornája (Solaris, emlékeztetők) engedélyezett. */
    fun allAllowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return listOf(SolarisFeed.CHANNEL_ID, ReminderReceiver.CHANNEL_ID).all { canPost(manager, it) }
    }
}
