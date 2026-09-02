package com.example.whatsapp_summarizer.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.whatsapp_summarizer.R

/**
 * Posts the app's own notifications.
 *
 * Two channels, because they earn interruption differently: an alert is the whole
 * point of the feature and should buzz, while the daily digest should arrive quietly.
 */
object Notifier {

    private const val CHANNEL_ALERTS = "smart_alerts"
    private const val CHANNEL_DIGEST = "daily_digest"

    private const val ID_DIGEST = 2001
    /** Alerts use incrementing ids so several can stack rather than replace. */
    private var nextAlertId = 3000

    fun ensureChannels(context: Context) {
        // No version guard needed: channels arrived in API 26 and minSdk is 26.
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.channel_alerts),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_alerts_description)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DIGEST,
                context.getString(R.string.channel_digest),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_digest_description)
            }
        )
    }

    /**
     * True when we are actually allowed to post. On Android 13+ the user must have
     * granted POST_NOTIFICATIONS, and a background job must not assume they did.
     */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun postAlert(context: Context, title: String, body: String, intent: Intent?) {
        if (!canPost(context)) return
        ensureChannels(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_summarize)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .apply { intent?.let { setContentIntent(activityPendingIntent(context, it)) } }
            .build()

        NotificationManagerCompat.from(context).notify(nextAlertId++, notification)
    }

    fun postDigest(context: Context, title: String, body: String, intent: Intent?) {
        if (!canPost(context)) return
        ensureChannels(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_summarize)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .apply { intent?.let { setContentIntent(activityPendingIntent(context, it)) } }
            .build()

        // One id, so today's digest replaces yesterday's rather than piling up.
        NotificationManagerCompat.from(context).notify(ID_DIGEST, notification)
    }

    private fun activityPendingIntent(context: Context, intent: Intent): PendingIntent {
        return PendingIntent.getActivity(
            context,
            intent.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
