package com.mediar.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DoseLogicTest {

    private val zone = ZoneId.of("Asia/Tehran")
    private val day = LocalDate.of(2026, 10, 8) // پنج‌شنبه
    private fun at(h: Int, m: Int = 0, d: LocalDate = day): ZonedDateTime = d.atTime(h, m).atZone(zone)
    private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()

    private val twiceDaily = Schedule("""{"type":"daily","times":["08:00","20:00"]}""")
    private val threeTimes = Schedule("""{"type":"daily","times":["08:00","14:00","20:00"]}""")
    private val onceDaily = Schedule("""{"type":"daily","times":["08:00"]}""")

    private fun decide(
        s: Schedule, now: ZonedDateTime,
        logs: Map<Long, TakenInfo> = emptyMap(),
        early: Long = 120, late: Long = 360
    ): Decision = DoseLogic.decide(
        s, now, early, late,
        logFor = { logs[it] },
        lastLog = logs.values.maxByOrNull { it.takenAt }
    )

    @Test fun dueWithinEarlyWindow() {
        val d = decide(twiceDaily, at(7, 0))
        assertTrue(d is Decision.Due)
        assertEquals(at(8), (d as Decision.Due).slot.due)
    }

    @Test fun tooEarlyBeforeWindow() {
        val d = decide(twiceDaily, at(5, 0))
        assertTrue(d is Decision.TooEarly)
        assertEquals(at(8), (d as Decision.TooEarly).next.due)
        // نوبت دیشب جا مانده و گزارش می‌شود
        assertEquals(at(20, 0, day.minusDays(1)), d.missed?.due)
    }

    @Test fun alreadyTakenSameSlot() {
        val logs = mapOf(ms(at(8)) to TakenInfo(ms(at(8)), ms(at(8, 5))))
        val d = decide(twiceDaily, at(9, 0), logs)
        assertTrue(d is Decision.AlreadyTaken)
    }

    @Test fun missedMorningDoesNotStealEveningSlot() {
        // صبح جا مانده؛ ساعت ۱۹:۳۰ باید نوبت ۲۰:۰۰ حساب شود نه نوبت صبح
        val d = decide(twiceDaily, at(19, 30))
        assertTrue(d is Decision.Due)
        assertEquals(at(20), (d as Decision.Due).slot.due)
    }

    @Test fun lateMorningStillMorningBeforeMidpoint() {
        val d = decide(twiceDaily, at(13, 0))
        assertTrue(d is Decision.Due)
        assertEquals(at(8), (d as Decision.Due).slot.due)
    }

    @Test fun tooSoonAfterLateDose() {
        // نوبت ۸ ساعت ۱۱:۵۰ خورده شد؛ ساعت ۱۲:۰۵ پنجره نوبت ۱۴ باز است ولی نباید دوز دوم ثبت شود
        val logs = mapOf(ms(at(8)) to TakenInfo(ms(at(8)), ms(at(11, 50))))
        val d = decide(threeTimes, at(12, 5), logs)
        assertTrue(d is Decision.TooSoon)
        assertEquals(at(14, 50), (d as Decision.TooSoon).allowedAt)
        // ساعت ۱۵ دیگر مشکلی نیست
        assertTrue(decide(threeTimes, at(15, 0), logs) is Decision.Due)
    }

    @Test fun missedAfterLateWindow() {
        val d = decide(onceDaily, at(20, 30))
        assertTrue(d is Decision.DoneForToday)
        assertEquals(at(8), (d as Decision.DoneForToday).missed?.due)
        assertEquals(at(8, 0, day.plusDays(1)), d.next)
    }

    @Test fun eveningDoseAcrossMidnight() {
        val late = Schedule("""{"type":"daily","times":["23:00"]}""")
        val d = decide(late, at(0, 30, day.plusDays(1)))
        assertTrue(d is Decision.Due)
        assertEquals(at(23), (d as Decision.Due).slot.due)
    }

    @Test fun weeklyNotToday() {
        // ۸ اکتبر ۲۰۲۶ پنج‌شنبه (۴) است؛ دارو فقط دوشنبه‌ها (۱)
        val weekly = Schedule("""{"type":"weekly","days":[1],"times":["09:00"]}""")
        val d = decide(weekly, at(10, 0))
        assertTrue(d is Decision.NotToday)
        assertEquals(LocalDate.of(2026, 10, 12), (d as Decision.NotToday).next?.toLocalDate())
    }

    @Test fun intervalEveryOtherDay() {
        val s = Schedule("""{"type":"interval","every":2,"anchor":"2026-10-07","times":["09:00"]}""")
        assertTrue(s.isDueOn(LocalDate.of(2026, 10, 9)))
        assertTrue(!s.isDueOn(day))
    }

    @Test fun alarmAtDueThenRepeats() {
        val p0 = DoseLogic.nextAlarm(onceDaily, at(7, 0), 120, 720, 10, 12) { false }
        assertNotNull(p0)
        assertEquals(at(8), p0!!.triggerAt)
        assertEquals(0, p0.repeatIndex)

        val p1 = DoseLogic.nextAlarm(onceDaily, at(8, 25), 120, 720, 10, 12) { false }
        assertEquals(at(8, 30), p1!!.triggerAt)
        assertEquals(3, p1.repeatIndex)
    }

    @Test fun alarmSkipsLoggedAndExhausted() {
        val logged = setOf(ms(at(8)))
        val p = DoseLogic.nextAlarm(twiceDaily, at(9, 0), 120, 720, 10, 12) { it in logged }
        assertEquals(at(20), p!!.triggerAt)

        // تکرارها تمام شده (۱۲ × ۱۰ دقیقه) → نوبت بعدی
        val p2 = DoseLogic.nextAlarm(twiceDaily, at(10, 30), 120, 720, 10, 12) { false }
        assertEquals(at(20), p2!!.triggerAt)
    }

    @Test fun alarmForWeeklyFarAway() {
        val weekly = Schedule("""{"type":"weekly","days":[1],"times":["09:00"]}""")
        val p = DoseLogic.nextAlarm(weekly, at(10, 0), 120, 720, 10, 12) { false }
        assertEquals(at(9, 0, LocalDate.of(2026, 10, 12)), p!!.triggerAt)
    }

    @Test fun noScheduleNoCrash() {
        val empty = Schedule("""{"type":"weekly","days":[],"times":["09:00"]}""")
        assertTrue(decide(empty, at(10, 0)) is Decision.NotToday)
        assertNull(DoseLogic.nextAlarm(empty, at(10, 0), 120, 720, 10, 12) { false })
    }

    @Test fun jalaliDate() {
        assertEquals(Triple(1405, 7, 16), Fmt.toJalali(day))
        assertEquals(Triple(1403, 1, 1), Fmt.toJalali(LocalDate.of(2024, 3, 20)))
    }
}
