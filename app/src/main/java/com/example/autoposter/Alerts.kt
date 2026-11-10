package com.example.autoposter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

object Alerts {
    fun post(ctx: Context, id: Int, title: String, text: String) {
        if (!Settings.notifyResults(ctx)) return
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("alerts", "Post results", NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(id, NotificationCompat.Builder(ctx, "alerts").setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title).setContentText(text).setAutoCancel(true).build())
        }
    }
}
