package com.mediar.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** بعد از روشن شدن گوشی یا آپدیت اپ، همه آلارم‌ها دوباره تنظیم می‌شوند */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Alarms.rescheduleAll(ctx)
        }
    }
}
