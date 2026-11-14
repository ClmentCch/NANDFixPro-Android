package com.nandfixpro.android.data

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nandfixpro.android.NandFixProApp
import com.nandfixpro.android.R

object NotificationHelper {

    const val ID_USB_STATUS = 1001
    const val ID_OPERATION = 1002

    fun canNotify(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun notify(
        context: Context,
        id: Int,
        title: String,
        text: String,
        ongoing: Boolean = false,
        channel: String = NandFixProApp.CHANNEL_STATUS,
    ) {
        if (!canNotify(context)) return
        val notification: Notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_nand)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(ongoing)
            .setAutoCancel(!ongoing)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun cancel(context: Context, id: Int) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(id)
        }
    }
}
