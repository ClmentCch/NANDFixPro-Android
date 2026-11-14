package com.nandfixpro.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class NandFixProApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val status = NotificationChannel(
            CHANNEL_STATUS,
            "NAND Fix Pro — état",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Connexion USB, progression des opérations et résultats d'analyse."
        }

        val diagnostics = NotificationChannel(
            CHANNEL_DIAGNOSTICS,
            "NAND Fix Pro — diagnostics",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Messages de diagnostic non critiques."
        }

        manager.createNotificationChannels(listOf(status, diagnostics))
    }

    companion object {
        const val CHANNEL_STATUS = "nandfix_status"
        const val CHANNEL_DIAGNOSTICS = "nandfix_diagnostics"

        lateinit var instance: NandFixProApp
            private set
    }
}
