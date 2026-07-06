package com.mediar.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.mediar.app.data.Db
import com.mediar.app.data.Med
import com.mediar.app.logic.Schedule
import java.time.LocalTime
import java.time.ZonedDateTime

object Alarms {

    const val EXTRA_MED_ID = "med_id"
    const val EXTRA_DUE_AT = "due_at"
    const val EXTRA_REPEAT = "repeat_count"

    /** برای هر دارو، آلارم اولین نوبت ثبت‌نشده را تنظیم می‌کند */
    fun rescheduleAll(ctx: Context) {
        val db = Db.get(ctx)
        val now = ZonedDateTime.now()
        for (med in db.activeMeds()) {
            val next = nextUnloggedDose(ctx, med, now)
            if (next == null) {
                cancelAlarm(ctx, med.id)
                continue
            }
            val dueMillis = next.toInstant().toEpochMilli()
            val triggerAt = maxOf(dueMillis, System.currentTimeMillis() + 5_000)
            scheduleAt(ctx, med.id, dueMillis, triggerAt, 0)
        }
    }

    /** اولین نوبت بدون ثبت — شامل نوبت‌های عقب‌افتاده امروز */
    fun nextUnloggedDose(ctx: Context, med: Med, now: ZonedDateTime): ZonedDateTime? {
        val db = Db.get(ctx)
        val schedule = Schedule(med.scheduleJson)
        val today = schedule.dueTimesForDate(now.toLocalDate(), now.zone)
            .firstOrNull { !db.hasLogFor(med.id, it.toInstant().toEpochMilli()) }
        if (today != null) return today
        val endOfDay = now.toLocalDate().atTime(LocalTime.MAX).atZone(now.zone)
        return schedule.nextDue(endOfDay)
    }

    fun scheduleAt(ctx: Context, medId: String, dueAt: Long, triggerAtMillis: Long, repeatCount: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, medId, dueAt, repeatCount)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    fun cancelAlarm(ctx: Context, medId: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx, medId, 0L, 0))
    }

    fun cancelNotification(ctx: Context, medId: String) {
        NotificationManagerCompat.from(ctx).cancel(medId.hashCode())
    }

    private fun pending(ctx: Context, medId: String, dueAt: Long, repeatCount: Int): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_MED_ID, medId)
            putExtra(EXTRA_DUE_AT, dueAt)
            putExtra(EXTRA_REPEAT, repeatCount)
        }
        return PendingIntent.getBroadcast(
            ctx,
            medId.hashCode(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
