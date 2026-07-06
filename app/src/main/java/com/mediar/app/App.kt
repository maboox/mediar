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

        // کانال با صدای آلارم سیستم (وقتی پیام صوتی ضبط‌شده وجود ندارد)
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

        // کانال بی‌صدا — وقتی خود اپ پیام صوتی ضبط‌شده را پخش می‌کند
        // تا صدای نوتیف و صدای ضبط‌شده روی هم نیفتند
        val silent = NotificationChannel(
            CHANNEL_SILENT,
            "یادآوری قرص (با پیام صوتی)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "وقتی پیام صوتی ضبط‌شده پخش می‌شود، نوتیف بی‌صدا می‌آید"
            enableVibration(true)
            setSound(null, null)
        }
        nm.createNotificationChannel(silent)
    }

    companion object {
        const val CHANNEL_ALARM = "mediar_alarm"
        const val CHANNEL_SILENT = "mediar_alarm_silent"
    }
}
