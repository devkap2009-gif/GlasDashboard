package de.glas.dashboard

import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Bekommt vom System alle Benachrichtigungen. Mit dieser Erlaubnis darf die App
 * außerdem Musik-Player wie Spotify steuern (MediaSession).
 */
class NotifListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: NotifListener? = null
            private set

        val watchers = mutableSetOf<() -> Unit>()

        fun changed() = watchers.toList().forEach { it() }

        fun isEnabled(ctx: Context): Boolean {
            val s = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return s.contains(ctx.packageName)
        }
    }

    override fun onListenerConnected() { instance = this; changed() }
    override fun onListenerDisconnected() { instance = null; changed() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = changed()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = changed()
}
