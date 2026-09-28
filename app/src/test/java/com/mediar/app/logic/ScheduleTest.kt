package com.mediar.app.logic

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ScheduleTest {
    private val start = LocalDate.parse("2026-03-01")
    private fun plan(mode: String = "daily", slots: List<SlotSpec> = listOf(SlotSpec("a", "08:00", 1.0), SlotSpec("b", "20:00", .5))) = Plan("v1", "m1", start, null, mode, setOf(1,3), 3, 2, 1, false, slots)
    @Test fun multipleDosesAndVersioning() {
        val a = Schedule.on(plan(), start, ZoneId.of("Asia/Tehran"))
        assertEquals(listOf(1.0,.5), a.map { it.quantity })
        assertEquals(2, a.map { it.id }.distinct().size)
        assertNotEquals(a[0].id, Schedule.on(plan().copy(id="v2"), start, ZoneId.of("Asia/Tehran"))[0].id)
    }
    @Test fun intervalWeeklyCycleAndPause() {
        assertTrue(Schedule.active(plan("interval"), start.plusDays(3)))
        assertFalse(Schedule.active(plan("interval"), start.plusDays(2)))
        assertTrue(Schedule.active(plan("cycle"), start.plusDays(1)))
        assertFalse(Schedule.active(plan("cycle"), start.plusDays(2)))
        assertFalse(Schedule.active(plan().copy(paused=true), start))
    }
    @Test fun midnightZoneAndOutside() {
        val at = Schedule.on(plan(slots=listOf(SlotSpec("x","00:05",1.0))),start,ZoneId.of("Asia/Tehran")).single()
        assertEquals(LocalDate.parse("2026-02-28"), at.due.withZoneSameInstant(ZoneId.of("UTC")).toLocalDate())
        assertEquals("early",Schedule.classification(at,at.due.toInstant().minusSeconds(60)))
        assertEquals("outside",Schedule.classification(null,Instant.now()))
    }
    @Test fun daylightSavingAndWrongTagRemoval() {
        val p=plan(slots=listOf(SlotSpec("midnight","02:30",1.0)))
        val dst=Schedule.on(p.copy(starts=LocalDate.parse("2026-03-08")),LocalDate.parse("2026-03-08"),ZoneId.of("America/New_York")).single()
        assertEquals(LocalDate.parse("2026-03-08"),dst.due.toLocalDate())
        val gate=ScanGate();assertFalse(gate.read("a",1000));gate.removal("b");assertFalse(gate.read("a",2000))
    }
    @Test fun gateNeedsRemovalAndWindow() {
        val g=ScanGate(); assertFalse(g.read("tag",1000)); assertFalse(g.read("tag",1100)); g.removal("tag")
        assertFalse(g.read("tag",1200)); g.removal("tag"); assertTrue(g.read("tag",1400))
        assertFalse(g.read("tag",1500)); g.removal("tag"); assertFalse(g.read("tag",32000))
    }
}
