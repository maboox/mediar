package com.mediar.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** بعد از روشن شدن گوشی، آپدیت اپ یا تغییر ساعت/منطقه زمانی، همه آلارم‌ها دوباره تنظیم می‌شوند */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" ->
                try { Alarms.rescheduleAll(ctx) } catch (_: Exception) {}
        }
    }
}
