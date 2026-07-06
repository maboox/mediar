package com.mediar.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.provider.Settings

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ALARM,
            "یادآوری قرص",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "آلارم نوبت قرص"
            enableVibration(true)
            setSound(
                Settings.System.DEFAULT_ALARM_ALERT_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ALARM = "mediar_alarm"
    }
}
