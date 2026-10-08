package com.mediar.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.mediar.app.data.Db
import com.mediar.app.data.Med
import com.mediar.app.data.Prefs
import com.mediar.app.logic.AlarmPlan
import com.mediar.app.logic.DoseLogic
import com.mediar.app.logic.Schedule
import com.mediar.app.ui.MainActivity
import java.time.ZonedDateTime

object Alarms {

    const val EXTRA_MED_ID = "med_id"
    const val EXTRA_DUE_AT = "due_at"
    const val EXTRA_REPEAT = "repeat_count"
    const val EXTRA_TRIGGER_AT = "trigger_at"

    /** برای همه داروها آلارم بعدی را تنظیم می‌کند */
    fun rescheduleAll(ctx: Context) {
        for (med in Db.get(ctx).activeMeds()) rescheduleMed(ctx, med)
    }

    /** محاسبه زمان آلارم بعدی یک دارو */
    fun plan(ctx: Context, med: Med, now: ZonedDateTime = ZonedDateTime.now()): AlarmPlan? {
        val db = Db.get(ctx)
        val prefs = Prefs.get(ctx)
        return DoseLogic.nextAlarm(
            Schedule(med.scheduleJson), now,
            prefs.earlyWindowMin, prefs.lateWindowMin,
            prefs.repeatMin, prefs.maxRepeats
        ) { due -> db.logFor(med.id, due) != null }
    }

    fun rescheduleMed(ctx: Context, med: Med, now: ZonedDateTime = ZonedDateTime.now()) {
        if (med.archived) {
            cancelAlarm(ctx, med.id)
            return
        }
        val p = plan(ctx, med, now)
        if (p == null) {
            cancelAlarm(ctx, med.id)
            return
        }
        val trigger = maxOf(p.triggerAt.toInstant().toEpochMilli(), System.currentTimeMillis() + 3_000)
        scheduleAt(ctx, med.id, p.slot.dueMillis, trigger, p.repeatIndex)
    }

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    private fun scheduleAt(ctx: Context, medId: String, dueAt: Long, triggerAtMillis: Long, repeatCount: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, medId, dueAt, repeatCount, triggerAtMillis)
        try {
            if (canExact(ctx)) {
                // setAlarmClock مطمئن‌ترین نوع آلارم است و در حالت Doze هم سر وقت زده می‌شود
                val show = PendingIntent.getActivity(
                    ctx, 1,
                    Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, show), pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    fun cancelAlarm(ctx: Context, medId: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx, medId, 0L, 0, 0L))
    }

    fun notificationId(medId: String): Int = medId.hashCode()

    fun cancelNotification(ctx: Context, medId: String) {
        NotificationManagerCompat.from(ctx).cancel(notificationId(medId))
    }

    private fun pending(ctx: Context, medId: String, dueAt: Long, repeatCount: Int, triggerAt: Long): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            // action یکتا برای هر دارو تا PendingIntentها با هم قاطی نشوند
            action = "com.mediar.app.ALARM." + medId
            putExtra(EXTRA_MED_ID, medId)
            putExtra(EXTRA_DUE_AT, dueAt)
            putExtra(EXTRA_REPEAT, repeatCount)
            putExtra(EXTRA_TRIGGER_AT, triggerAt)
        }
        return PendingIntent.getBroadcast(
            ctx,
            medId.hashCode(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
