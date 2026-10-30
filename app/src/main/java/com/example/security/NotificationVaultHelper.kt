package com.example.security

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object NotificationVaultHelper {
    fun policy(context: Context, value: String) {
        context
            .getSharedPreferences("notification-privacy", Context.MODE_PRIVATE)
            .edit()
            .putString("mode", value)
            .apply()
        NotificationManagerCompat.from(context).cancelAll()
    }

    fun postDiscreetNotification(context: Context, id: String, conversationId: String) {
        val stealth =
            context
                .getSharedPreferences("notification-privacy", Context.MODE_PRIVATE)
                .getString("mode", "PRIVATE") != "PRIVATE"
        val visibility =
            if (stealth) NotificationCompat.VISIBILITY_SECRET
            else NotificationCompat.VISIBILITY_PRIVATE
        val channel = if (stealth) "protected_activity" else "private_messages"
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                    channel,
                    if (stealth) "Protected activity" else "Private messages",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply {
                    lockscreenVisibility = visibility
                    setShowBadge(false)
                }
        )
        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        )
            return
        val intent =
            Intent(context, MainActivity::class.java)
                .putExtra("extra_notification_id", id)
                .putExtra("extra_conversation_id", conversationId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending =
            PendingIntent.getActivity(
                context,
                id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val publicVersion =
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("RoseVault")
                .setContentText("New private message — unlock to read")
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        val notification =
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("RoseVault")
                .setContentText(
                    if (stealth) "Protected Activity" else "New private message — unlock to read"
                )
                .setVisibility(visibility)
                .setPublicVersion(publicVersion)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }
}
