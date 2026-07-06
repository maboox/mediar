package com.mediar.app.logic

import android.content.Context
import com.mediar.app.alarm.Alarms
import com.mediar.app.data.Db
import com.mediar.app.data.DoseLog
import com.mediar.app.data.Med
import com.mediar.app.data.Prefs
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID

sealed class ScanOutcome {
    /** این نوبت قبلا خورده شده */
    data class AlreadyTaken(val next: ZonedDateTime?) : ScanOutcome()
    /** همین الان ثبت شد */
    data class Logged(val status: String, val delayMin: Long, val dueAt: ZonedDateTime, val lowStock: Boolean) : ScanOutcome()
    /** هنوز خیلی زوده */
    data class TooEarly(val dueAt: ZonedDateTime) : ScanOutcome()
    /** امروز نوبت این دارو نیست */
    data class NotToday(val next: ZonedDateTime?) : ScanOutcome()
}

object ScanEngine {

    /** منطق اصلی اسکن تگ یک دارو */
    fun evaluateAndLog(ctx: Context, med: Med, now: ZonedDateTime = ZonedDateTime.now()): ScanOutcome {
        val db = Db.get(ctx)
        val schedule = Schedule(med.scheduleJson)
        val earlyWindow = Prefs.get(ctx).earlyWindowMin

        val todayDoses = schedule.dueTimesForDate(now.toLocalDate(), now.zone)
        if (todayDoses.isEmpty()) {
            return ScanOutcome.NotToday(schedule.nextDue(now))
        }

        val unlogged = todayDoses.filter { !db.hasLogFor(med.id, it.toInstant().toEpochMilli()) }
        if (unlogged.isEmpty()) {
            return ScanOutcome.AlreadyTaken(schedule.nextDue(now))
        }

        val due = unlogged.first()
        if (now.isBefore(due.minusMinutes(earlyWindow))) {
            return ScanOutcome.TooEarly(due)
        }

        val delayMin = Duration.between(due, now).toMinutes()
        val status = when {
            delayMin < 0 -> "early"
            delayMin <= 30 -> "ontime"
            else -> "late"
        }
        db.addLog(
            DoseLog(
                id = UUID.randomUUID().toString(),
                medId = med.id,
                dueAt = due.toInstant().toEpochMilli(),
                takenAt = now.toInstant().toEpochMilli(),
                delayMin = delayMin,
                status = status
            )
        )
        db.addStock(med.id, -med.doseAmount)
        val after = db.getMed(med.id)
        val lowStock = after != null && after.stock <= after.lowThreshold

        Alarms.cancelNotification(ctx, med.id)
        Alarms.rescheduleAll(ctx)
        return ScanOutcome.Logged(status, delayMin, due, lowStock)
    }

    /** یک سطر برای داشبورد: نوبت برنامه‌ریزی‌شده + ثبت متناظر (اگر باشد) */
    data class DoseRow(val med: Med, val dueAt: ZonedDateTime, val log: DoseLog?)

    /** همه نوبت‌های برنامه‌ریزی‌شده در بازه [from, toInclusive] */
    fun dosesBetween(ctx: Context, from: LocalDate, toInclusive: LocalDate): List<DoseRow> {
        val db = Db.get(ctx)
        val zone = ZonedDateTime.now().zone
        val out = mutableListOf<DoseRow>()
        for (med in db.activeMeds()) {
            val schedule = Schedule(med.scheduleJson)
            var d = from
            while (!d.isAfter(toInclusive)) {
                for (due in schedule.dueTimesForDate(d, zone)) {
                    out.add(DoseRow(med, due, db.logFor(med.id, due.toInstant().toEpochMilli())))
                }
                d = d.plusDays(1)
            }
        }
        return out.sortedByDescending { it.dueAt }
    }
}
