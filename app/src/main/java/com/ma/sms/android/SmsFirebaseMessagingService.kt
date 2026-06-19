package com.ma.sms.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "sms_dossiers"
    }

    override fun onNewToken(token: String) {
        val app = application as SmsApplication
        if (app.tokenManager.isLoggedIn()) {
            CoroutineScope(Dispatchers.IO).launch {
                app.fcmRepository.registerToken(token)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: "SMS - Nouveau dossier"
        val body = message.notification?.body ?: "Un dossier vous a été affecté."
        val dossierId = message.data["dossierId"]?.toLongOrNull()

        showNotification(title, body, dossierId)
    }

    private fun showNotification(title: String, body: String, dossierId: Long?) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Dossiers affectés",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications pour les nouveaux dossiers affectés"
        }
        manager.createNotificationChannel(channel)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            dossierId?.let { putExtra("dossierId", it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
