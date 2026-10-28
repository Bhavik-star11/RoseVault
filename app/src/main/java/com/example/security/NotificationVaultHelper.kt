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
    fun postDiscreetNotification(context: Context, id: String, conversationId: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                    "protected_activity",
                    "Protected activity",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply {
                    lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
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
        val notification =
            NotificationCompat.Builder(context, "protected_activity")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("RoseVault")
                .setContentText("Protected Activity")
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }
}
