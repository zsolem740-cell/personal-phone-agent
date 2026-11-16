package hu.personal.phoneagent

import android.app.NotificationManager

/** Megmondja, hogy egy értesítés valóban meg fog-e jelenni. */
object NotificationGate {
    fun canPost(manager: NotificationManager, channelId: String): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(channelId) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }
}
