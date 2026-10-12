package dev.vehiclebridge.byd.bridge

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import dev.vehiclebridge.byd.MainActivity
import dev.vehiclebridge.byd.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** The system owns channel visibility; service startup preserves the user's choice. */
internal object BridgeNotifications {
    const val CHANNEL="bemfa_bridge"
    const val RESULT_CHANNEL="vehicle_results"
    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"车辆设备联动",NotificationManager.IMPORTANCE_LOW))
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL,"车辆操作结果",NotificationManager.IMPORTANCE_DEFAULT))
    }
    /** Publish each completed operation independently of the ongoing service channel. */
    fun result(context: Context,text: String) {
        ensureChannel(context)
        val open=PendingIntent.getActivity(context,2,Intent(context,MainActivity::class.java)
            .putExtra("tab",0).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note=Notification.Builder(context,RESULT_CHANNEL).setSmallIcon(R.drawable.ic_app)
            .setContentTitle("车辆助手 · 操作结果").setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open)
            .setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
        // The system controls permission and channel visibility. Never override a blocked channel.
        try {context.getSystemService(NotificationManager::class.java).notify(21,note)}
        catch(_:SecurityException) {}
    }
    fun isVisible(context: Context): Boolean {
        val manager=context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance!=NotificationManager.IMPORTANCE_NONE
    }
}
