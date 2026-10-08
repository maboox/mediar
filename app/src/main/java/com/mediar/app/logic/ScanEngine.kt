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

/** نتیجه اسکن تگ یک دارو — برای نمایش در پاپ‌آپ */
sealed class ScanOutcome {
    abstract val med: Med

    /** وقتشه — اسکن اول؛ بعد از خوردن باید دوباره تگ را بزند (یا دکمه «خوردم») */
    data class AskToTake(override val med: Med, val slot: Slot, val lateMin: Long) : ScanOutcome()

    /** همین الان ثبت شد */
    data class Logged(
        override val med: Med, val slot: Slot, val log: DoseLog, val lowStock: Boolean
    ) : ScanOutcome()

    /** این نوبت قبلا خورده شده */
    data class AlreadyTaken(
        override val med: Med, val slot: Slot, val takenAt: ZonedDateTime, val next: ZonedDateTime?
    ) : ScanOutcome()

    /** تازه یک دوز خورده — هنوز نباید دوز بعدی را بخورد */
    data class TooSoon(
        override val med: Med, val lastTakenAt: ZonedDateTime, val allowedAt: ZonedDateTime
    ) : ScanOutcome()

    /** هنوز زوده */
    data class TooEarly(
        override val med: Med, val next: Slot, val missed: Slot?, val lastTakenAt: ZonedDateTime?
    ) : ScanOutcome()

    /** نوبت‌های امروز تمام شده */
    data class DoneForToday(
        override val med: Med, val next: ZonedDateTime?, val missed: Slot?, val lastTakenAt: ZonedDateTime?
    ) : ScanOutcome()

    /** امروز نوبت این دارو نیست */
    data class NotToday(override val med: Med, val next: ZonedDateTime?) : ScanOutcome()
}

object ScanEngine {

    private fun atZone(ms: Long, now: ZonedDateTime): ZonedDateTime =
        java.time.Instant.ofEpochMilli(ms).atZone(now.zone)

    fun decide(ctx: Context, med: Med, now: ZonedDateTime = ZonedDateTime.now()): Decision {
        val db = Db.get(ctx)
        val prefs = Prefs.get(ctx)
        return DoseLogic.decide(
            Schedule(med.scheduleJson), now,
            prefs.earlyWindowMin, prefs.lateWindowMin,
            logFor = { due -> db.logFor(med.id, due)?.let { TakenInfo(it.dueAt, it.takenAt) } },
            lastLog = db.lastLog(med.id)?.let { TakenInfo(it.dueAt, it.takenAt) }
        )
    }

    /**
     * منطق اصلی اسکن تگ یک دارو.
     * @param confirm true یعنی کاربر صریحا گفت «خوردم» (دکمه یا اسکن دوم)
     */
    fun scan(ctx: Context, med: Med, confirm: Boolean = false, now: ZonedDateTime = ZonedDateTime.now()): ScanOutcome {
        val prefs = Prefs.get(ctx)
        return when (val d = decide(ctx, med, now)) {
            is Decision.Due -> {
                val armed = prefs.armedDue(med.id) == d.slot.dueMillis
                if (confirm || armed || prefs.singleTap) {
                    logDose(ctx, med, d.slot, now)
                } else {
                    prefs.setArmed(med.id, d.slot.dueMillis)
                    val late = Duration.between(d.slot.due, now).toMinutes().coerceAtLeast(0)
                    ScanOutcome.AskToTake(med, d.slot, late)
                }
            }
            is Decision.AlreadyTaken -> ScanOutcome.AlreadyTaken(med, d.slot, atZone(d.takenAt, now), d.next)
            is Decision.TooSoon -> ScanOutcome.TooSoon(med, atZone(d.lastTakenAt, now), d.allowedAt)
            is Decision.TooEarly -> ScanOutcome.TooEarly(med, d.next, d.missed, d.lastTaken?.let { atZone(it.takenAt, now) })
            is Decision.DoneForToday -> ScanOutcome.DoneForToday(med, d.next, d.missed, d.lastTaken?.let { atZone(it.takenAt, now) })
            is Decision.NotToday -> ScanOutcome.NotToday(med, d.next)
        }
    }

    private fun logDose(ctx: Context, med: Med, slot: Slot, now: ZonedDateTime): ScanOutcome.Logged {
        val db = Db.get(ctx)
        val delayMin = Duration.between(slot.due, now).toMinutes()
        val status = when {
            delayMin < -5 -> "early"
            delayMin <= 30 -> "ontime"
            else -> "late"
        }
        val log = DoseLog(
            id = UUID.randomUUID().toString(),
            medId = med.id,
            dueAt = slot.dueMillis,
            takenAt = now.toInstant().toEpochMilli(),
            delayMin = delayMin,
            status = status
        )
        db.addLog(log)
        db.addStock(med.id, -med.doseAmount)
        Prefs.get(ctx).clearArmed(med.id)
        val after = db.getMed(med.id)
        val lowStock = after != null && after.stock <= after.lowThreshold

        Alarms.cancelNotification(ctx, med.id)
        Alarms.rescheduleMed(ctx, after ?: med)
        return ScanOutcome.Logged(after ?: med, slot, log, lowStock)
    }

    /** برگرداندن یک ثبت اشتباه (موجودی هم برمی‌گردد) */
    fun undo(ctx: Context, logId: String) {
        val db = Db.get(ctx)
        val log = db.getLog(logId) ?: return
        db.deleteLog(logId)
        val med = db.getMed(log.medId) ?: return
        db.addStock(med.id, med.doseAmount)
        Prefs.get(ctx).clearArmed(med.id)
        Alarms.rescheduleMed(ctx, med)
    }

    /** ثبت دستی یک نوبت گذشته توسط مراقب (زمان دقیق خوردن نامعلوم است) */
    fun manualLog(ctx: Context, med: Med, dueAt: Long) {
        val db = Db.get(ctx)
        if (db.logFor(med.id, dueAt) != null) return
        db.addLog(
            DoseLog(
                id = UUID.randomUUID().toString(),
                medId = med.id,
                dueAt = dueAt,
                takenAt = minOf(dueAt, System.currentTimeMillis()),
                delayMin = 0,
                status = "manual"
            )
        )
        db.addStock(med.id, -med.doseAmount)
        Alarms.cancelNotification(ctx, med.id)
        Alarms.rescheduleMed(ctx, med)
    }

    /** یک سطر برای داشبورد: نوبت برنامه‌ریزی‌شده + ثبت متناظر (اگر باشد) */
    data class DoseRow(val med: Med, val slot: Slot, val log: DoseLog?) {
        val dueAt: ZonedDateTime get() = slot.due
    }

    /** همه نوبت‌های برنامه‌ریزی‌شده در بازه روزهای [from, toInclusive] */
    fun dosesBetween(ctx: Context, from: LocalDate, toInclusive: LocalDate): List<DoseRow> {
        val db = Db.get(ctx)
        val prefs = Prefs.get(ctx)
        val zone = ZonedDateTime.now().zone
        val out = mutableListOf<DoseRow>()
        for (med in db.activeMeds()) {
            val schedule = Schedule(med.scheduleJson)
            val slots = DoseLogic.slotsBetween(
                schedule,
                from.atStartOfDay(zone),
                toInclusive.plusDays(1).atStartOfDay(zone).minusNanos(1),
                prefs.earlyWindowMin, prefs.lateWindowMin
            )
            for (s in slots) out.add(DoseRow(med, s, db.logFor(med.id, s.dueMillis)))
        }
        return out.sortedByDescending { it.dueAt }
    }
}
