package com.mediar.app

import com.mediar.app.logic.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TapTest {
    private val zone = ZoneId.of("Asia/Tehran")
    private val day = LocalDate.of(2026, 10, 8)
    private fun at(h: Int, m: Int = 0, d: LocalDate = day) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun plan(vararg times: String, mode: String = "daily", weekdays: Set<Int> = (1..7).toSet()) =
        Plan(id = "p", medicine = "m", start = day.minusDays(10), mode = mode, weekdays = weekdays,
            slots = times.map { Slot(id = it, time = it, amount = 1.0) })
    private fun doses(p: Plan) = (-3L..3L).flatMap { Schedule.on(p, day.plusDays(it), zone, "Med", "tab") }
    private fun snap(p: Plan, events: List<Event> = emptyList(), declined: Set<String> = emptySet()) =
        Snapshot(medicines = listOf(Medicine("m", "Med", "", "tab", 0.0, false, 10.0)), plans = listOf(p),
            expected = doses(p), events = events, declined = declined)
    private fun dose(p: Plan, h: Int, d: LocalDate = day) = doses(p).first { it.due == at(h, 0, d) }
    private fun taken(e: Expected?, at: Long) = Event("ev-$at", "m", e?.id, at, at, 1.0, "NFC", "scheduled", false, "r$at", "Med", "tab")

    @Test fun dueInsideEarlyWindow() {
        val p = plan("08:00", "20:00")
        val v = Tap.decide(snap(p), "m", at(7), zone)
        assertTrue(v is TapVerdict.Due); assertEquals(at(8), (v as TapVerdict.Due).dose.due)
    }
    @Test fun tooEarlyBeforeWindow() {
        val p = plan("10:00")
        val v = Tap.decide(snap(p), "m", at(6), zone)
        assertTrue(v is TapVerdict.TooEarly); assertEquals(at(10), (v as TapVerdict.TooEarly).next.dose.due)
    }
    @Test fun missedMorningDoesNotTakeEveningSlot() {
        val p = plan("08:00", "20:00")
        val v = Tap.decide(snap(p), "m", at(19, 30), zone)
        assertTrue(v is TapVerdict.Due); assertEquals(at(20), (v as TapVerdict.Due).dose.due)
    }
    @Test fun multiDoseMedicinePicksMorningThenEvening() {
        val p = plan("08:00", "20:00")
        val morning = dose(p, 8)
        val s = snap(p, listOf(taken(morning, at(8, 5))))
        assertTrue(Tap.decide(s, "m", at(9), zone) is TapVerdict.Taken)
        val evening = Tap.decide(s, "m", at(19), zone)
        assertTrue(evening is TapVerdict.Due); assertEquals(at(20), (evening as TapVerdict.Due).dose.due)
    }
    @Test fun lateDoseBlocksImmediateNextDose() {
        val p = plan("08:00", "14:00", "20:00")
        val s = snap(p, listOf(taken(dose(p, 8), at(11, 50))))
        val v = Tap.decide(s, "m", at(12, 5), zone)
        assertTrue(v is TapVerdict.TooSoon); assertEquals(at(14, 50), (v as TapVerdict.TooSoon).allowedAt)
        assertTrue(Tap.decide(s, "m", at(15), zone) is TapVerdict.Due)
    }
    @Test fun outsidePlanRecordBlocksForAnHour() {
        val p = plan("08:00")
        val s = snap(p, listOf(taken(null, at(7, 30))))
        assertTrue(Tap.decide(s, "m", at(8), zone) is TapVerdict.TooSoon)
        assertTrue(Tap.decide(s, "m", at(8, 31), zone) is TapVerdict.Due)
    }
    @Test fun declinedDoseIsNotOfferedAgain() {
        val p = plan("08:00")
        assertTrue(Tap.decide(snap(p, declined = setOf(dose(p, 8).id)), "m", at(9), zone) is TapVerdict.Declined)
    }
    @Test fun missedAfterLateWindowIsReported() {
        val p = plan("08:00")
        val v = Tap.decide(snap(p), "m", at(20), zone)
        assertTrue(v is TapVerdict.Done); assertEquals(at(8), (v as TapVerdict.Done).missed?.due)
        assertEquals(at(8, 0, day.plusDays(1)), v.next?.due)
    }
    @Test fun lateEveningDoseWorksAfterMidnight() {
        val p = plan("23:00")
        val v = Tap.decide(snap(p), "m", at(0, 30, day.plusDays(1)), zone)
        assertTrue(v is TapVerdict.Due); assertEquals(at(23), (v as TapVerdict.Due).dose.due)
    }
    @Test fun notTodayForWeekly() {
        // 2026-10-08 is Thursday (4); Monday only
        val p = plan("09:00", mode = "weekly", weekdays = setOf(1))
        assertTrue(Tap.decide(snap(p), "m", at(12), zone) is TapVerdict.NotToday)
    }
    @Test fun secondTapRules() {
        val arm = TapArm("tok", "uid", "dose", 1_000_000)
        assertFalse(Tap.confirms(arm, "tok", "uid", "dose", 1_000_000 + 2_000))   // same touch, removal unseen
        assertTrue(Tap.confirms(arm, "tok", "uid", "dose", 1_000_000 + 9_000))
        assertTrue(Tap.confirms(arm.copy(detached = true), "tok", "uid", "dose", 1_000_000 + 2_000))
        assertFalse(Tap.confirms(arm, "tok", "other-uid", "dose", 1_000_000 + 9_000))
        assertFalse(Tap.confirms(arm, "tok", "uid", "other-dose", 1_000_000 + 9_000))
        assertFalse(Tap.confirms(arm, "tok", "uid", "dose", 1_000_000 + Tap.CONFIRM_WINDOW + 1))
        assertFalse(Tap.confirms(null, "tok", "uid", "dose", 1_000_000))
    }
}
