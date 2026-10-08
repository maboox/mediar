package com.mediar.app.logic

import java.time.Duration
import java.time.ZonedDateTime

/**
 * یک «نوبت» دارو با پنجره زمانی قبولش.
 *  - start: از این لحظه اسکن تگ برای این نوبت حساب می‌شود (موعد منهای پنجره زودتر)
 *  - end:   بعد از این لحظه نوبت «جامانده» حساب می‌شود (موعد به‌علاوه پنجره تأخیر)
 * پنجره‌های نوبت‌های پشت‌سرهم هیچ‌وقت روی هم نمی‌افتند.
 */
data class Slot(val due: ZonedDateTime, val start: ZonedDateTime, val end: ZonedDateTime) {
    val dueMillis: Long get() = due.toInstant().toEpochMilli()
    fun contains(t: ZonedDateTime): Boolean = !t.isBefore(start) && t.isBefore(end)
}

/** خلاصه یک ثبت خوردن برای منطق تصمیم‌گیری */
data class TakenInfo(val dueAt: Long?, val takenAt: Long)

/** نتیجه بررسی یک دارو در یک لحظه */
sealed class Decision {
    /** وقت خوردن است و این نوبت هنوز ثبت نشده */
    data class Due(val slot: Slot) : Decision()

    /** همین نوبت قبلا ثبت شده — نباید دوباره بخورد */
    data class AlreadyTaken(val slot: Slot, val takenAt: Long, val next: ZonedDateTime?) : Decision()

    /** نوبت جدید شروع شده ولی تازه یک دوز خورده — جلوگیری از دوز پشت‌سرهم */
    data class TooSoon(val slot: Slot, val lastTakenAt: Long, val allowedAt: ZonedDateTime) : Decision()

    /** امروز نوبت دیگری هست ولی هنوز زود است */
    data class TooEarly(val next: Slot, val missed: Slot?, val lastTaken: TakenInfo?) : Decision()

    /** نوبت‌های امروز تمام شده (خورده یا جامانده) */
    data class DoneForToday(val next: ZonedDateTime?, val missed: Slot?, val lastTaken: TakenInfo?) : Decision()

    /** امروز اصلا نوبت این دارو نیست */
    data class NotToday(val next: ZonedDateTime?) : Decision()
}

/** زمان آلارم بعدی یک دارو */
data class AlarmPlan(val slot: Slot, val triggerAt: ZonedDateTime, val repeatIndex: Int)

/** منطق خالص (بدون وابستگی به اندروید) — قابل تست */
object DoseLogic {

    /** حداکثر فاصله‌ای که برای پیدا کردن نوبت‌های اطراف بررسی می‌شود (روز) */
    private const val LOOK_BACK_DAYS = 2L

    private fun mid(a: ZonedDateTime, b: ZonedDateTime): ZonedDateTime =
        a.plus(Duration.between(a, b).dividedBy(2))

    private fun startOf(due: ZonedDateTime, prev: ZonedDateTime?, earlyMin: Long): ZonedDateTime {
        var s = due.minusMinutes(earlyMin.coerceAtLeast(0))
        if (prev != null) {
            val m = mid(prev, due)
            if (s.isBefore(m)) s = m
        }
        return s
    }

    /** ساخت پنجره یک نوبت با توجه به نوبت قبلی و بعدی */
    fun slotFor(
        schedule: Schedule, due: ZonedDateTime, earlyMin: Long, lateMin: Long
    ): Slot {
        val prev = schedule.prevDue(due)
        val next = schedule.nextDue(due)
        val start = startOf(due, prev, earlyMin)
        var end = due.plusMinutes(lateMin.coerceAtLeast(1))
        if (next != null) {
            val nextStart = startOf(next, due, earlyMin)
            if (end.isAfter(nextStart)) end = nextStart
        }
        if (!end.isAfter(due)) end = due.plusMinutes(1)
        return Slot(due, start, end)
    }

    /** همه نوبت‌ها (با پنجره) که موعدشان در بازه [from, to] است */
    fun slotsBetween(
        schedule: Schedule, from: ZonedDateTime, to: ZonedDateTime, earlyMin: Long, lateMin: Long
    ): List<Slot> =
        schedule.dueTimesBetween(from.toLocalDate(), to.toLocalDate(), from.zone)
            .filter { !it.isBefore(from) && !it.isAfter(to) }
            .map { slotFor(schedule, it, earlyMin, lateMin) }

    /** نوبتی که «الان» پنجره‌اش باز است (اگر باشد) */
    fun currentSlot(schedule: Schedule, now: ZonedDateTime, earlyMin: Long, lateMin: Long): Slot? {
        // نوبت‌های نزدیک: از دو روز قبل تا یک روز بعد
        val around = slotsBetween(
            schedule,
            now.minusDays(LOOK_BACK_DAYS),
            now.plusDays(1).plusMinutes(earlyMin),
            earlyMin, lateMin
        )
        around.firstOrNull { it.contains(now) }?.let { return it }
        // نوبت‌های خیلی دور (مثلا هفتگی با پنجره بلند)
        val prev = schedule.prevDue(now.plusMinutes(earlyMin.coerceAtLeast(0) + 1))
        if (prev != null) {
            val s = slotFor(schedule, prev, earlyMin, lateMin)
            if (s.contains(now)) return s
        }
        return null
    }

    /** اولین نوبتی که پنجره‌اش بعد از الان شروع می‌شود */
    fun upcomingSlot(schedule: Schedule, now: ZonedDateTime, earlyMin: Long, lateMin: Long): Slot? {
        var t = now
        repeat(4) {
            val due = schedule.nextDue(t) ?: return null
            val s = slotFor(schedule, due, earlyMin, lateMin)
            if (s.start.isAfter(now)) return s
            t = due
        }
        return null
    }

    /**
     * حداقل فاصله لازم بین دوز قبلی و نوبت فعلی:
     * نصف فاصله برنامه‌ریزی‌شده بین آن دو نوبت (حداکثر ۱۲ ساعت).
     * برای ثبت دستی بدون موعد، ۶۰ دقیقه.
     */
    fun minGapMinutes(current: Slot, last: TakenInfo): Long {
        val lastDue = last.dueAt ?: return 60
        if (lastDue >= current.dueMillis) return 0
        val half = (current.dueMillis - lastDue) / 2 / 60_000L
        return half.coerceIn(0, 12 * 60)
    }

    /**
     * تصمیم اصلی برای اسکن تگ یک دارو.
     * @param logFor ثبت مربوط به یک موعد (epoch millis) یا null
     * @param lastLog آخرین ثبت این دارو (هر نوبتی)
     */
    fun decide(
        schedule: Schedule,
        now: ZonedDateTime,
        earlyMin: Long,
        lateMin: Long,
        logFor: (Long) -> TakenInfo?,
        lastLog: TakenInfo?
    ): Decision {
        val nowMs = now.toInstant().toEpochMilli()
        val current = currentSlot(schedule, now, earlyMin, lateMin)

        if (current != null) {
            val log = logFor(current.dueMillis)
            if (log != null) {
                return Decision.AlreadyTaken(current, log.takenAt, schedule.nextDue(current.due))
            }
            if (lastLog != null && lastLog.takenAt <= nowMs) {
                val allowedAtMs = lastLog.takenAt + minGapMinutes(current, lastLog) * 60_000L
                if (nowMs < allowedAtMs) {
                    val allowedAt = java.time.Instant.ofEpochMilli(allowedAtMs).atZone(now.zone)
                    return Decision.TooSoon(current, lastLog.takenAt, allowedAt)
                }
            }
            return Decision.Due(current)
        }

        val today = now.toLocalDate()
        val startOfDay = today.atStartOfDay(now.zone)
        val lastTakenToday = lastLog?.takeIf { it.takenAt >= startOfDay.toInstant().toEpochMilli() }

        // آخرین نوبتی که در ۲۴ ساعت گذشته بسته شده و ثبت نشده
        val recent = slotsBetween(schedule, now.minusDays(LOOK_BACK_DAYS), now, earlyMin, lateMin)
        val missed = recent.lastOrNull {
            !it.end.isAfter(now) && it.end.isAfter(now.minusHours(24)) && logFor(it.dueMillis) == null
        }

        val upcoming = upcomingSlot(schedule, now, earlyMin, lateMin)
        if (upcoming != null && upcoming.due.toLocalDate() == today) {
            return Decision.TooEarly(upcoming, missed, lastTakenToday)
        }
        val hadToday = schedule.dueTimesForDate(today, now.zone).isNotEmpty()
        return if (hadToday || missed != null) {
            Decision.DoneForToday(upcoming?.due, missed, lastTakenToday)
        } else {
            Decision.NotToday(upcoming?.due)
        }
    }

    /**
     * زمان آلارم بعدی یک دارو.
     * آلارم سر موعد زده می‌شود و تا وقتی نوبت ثبت نشده و پنجره‌اش باز است،
     * هر repeatMin دقیقه (حداکثر maxRepeats بار) تکرار می‌شود.
     */
    fun nextAlarm(
        schedule: Schedule,
        now: ZonedDateTime,
        earlyMin: Long,
        lateMin: Long,
        repeatMin: Long,
        maxRepeats: Int,
        isLogged: (Long) -> Boolean
    ): AlarmPlan? {
        val repeat = repeatMin.coerceAtLeast(1)
        var candidates = slotsBetween(schedule, now.minusDays(LOOK_BACK_DAYS), now.plusDays(3), earlyMin, lateMin)
        var guard = 0
        while (guard++ < 30) {
            for (s in candidates) {
                if (!s.end.isAfter(now) || isLogged(s.dueMillis)) continue
                if (now.isBefore(s.due)) return AlarmPlan(s, s.due, 0)
                val elapsed = Duration.between(s.due, now).toMinutes()
                val k = elapsed / repeat + 1
                val trigger = s.due.plusMinutes(k * repeat)
                if (k <= maxRepeats && trigger.isBefore(s.end)) return AlarmPlan(s, trigger, k.toInt())
            }
            // نوبت‌های دورتر (هفتگی، هر چند روز، دوره‌ای)
            val lastDue = candidates.lastOrNull()?.due ?: now.plusDays(3)
            val next = schedule.nextDue(lastDue) ?: return null
            candidates = listOf(slotFor(schedule, next, earlyMin, lateMin))
        }
        return null
    }
}
