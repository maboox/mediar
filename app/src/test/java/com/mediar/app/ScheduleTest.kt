package com.mediar.app

import com.mediar.app.logic.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ScheduleTest {
    private val start=LocalDate.of(2026,10,4)
    private fun plan(mode: String="daily")=Plan(medicine="medicine",start=start,mode=mode,weekdays=setOf(1,3,5),interval=3,on=2,off=3,slots=listOf(Slot("morning","08:00",1.0),Slot("night","20:00",.5)))
    @Test fun dailyKeepsPerDoseAmounts(){val slots=Schedule.on(plan(),start,ZoneId.of("Asia/Tehran"));assertEquals(listOf(1.0,.5),slots.map{it.amount});assertNotEquals(slots[0].id,slots[1].id)}
    @Test fun intervalAnchoredAtStart(){val p=plan("interval");assertTrue(Schedule.active(p,start));assertFalse(Schedule.active(p,start.plusDays(1)));assertTrue(Schedule.active(p,start.plusDays(3)))}
    @Test fun weeklyOnlySelectedDays(){val p=plan("weekly");assertFalse(Schedule.active(p,start));assertTrue(Schedule.active(p,start.plusDays(1)))}
    @Test fun cycleCrossesMonth(){val p=plan("cycle");assertEquals(listOf(true,true,false,false,false,true,true), (0L..6).map{Schedule.active(p,start.plusDays(it))})}
    @Test fun boundariesPauseAndEnd(){val p=plan().copy(end=start.plusDays(1));assertFalse(Schedule.active(p,start.minusDays(1)));assertTrue(Schedule.active(p,start.plusDays(1)));assertFalse(Schedule.active(p,start.plusDays(2)));assertFalse(Schedule.active(p.copy(paused=true),start))}
    @Test fun versionsKeepDistinctIds(){val a=plan();val b=a.copy(id="replacement");assertNotEquals(Schedule.on(a,start,ZoneId.of("UTC")).first().id,Schedule.on(b,start,ZoneId.of("UTC")).first().id)}
    @Test fun effectiveDateDoesNotMoveCycleAnchor(){val p=plan("interval").copy(effectiveFrom=start.plusDays(2));assertFalse(Schedule.active(p,start));assertTrue(Schedule.active(p,start.plusDays(3)))}
    @Test fun timezonePreservesSlotIdAndWallTime(){val p=plan();val a=Schedule.on(p,start,ZoneId.of("UTC")).first();val b=Schedule.on(p,start,ZoneId.of("Asia/Tehran")).first();assertEquals(a.id,b.id);assertNotEquals(a.due,b.due);assertEquals(LocalTime.of(8,0),Instant.ofEpochMilli(b.due).atZone(ZoneId.of("Asia/Tehran")).toLocalTime())}
    @Test fun daylightSavingGapDoesNotCrashOrDuplicate(){val day=LocalDate.of(2026,3,29);val p=Plan(medicine="m",start=day,slots=listOf(Slot(time="02:30",amount=1.0)));val result=Schedule.on(p,day,ZoneId.of("Europe/Berlin"));assertEquals(1,result.size);assertEquals(LocalTime.of(3,30),Instant.ofEpochMilli(result[0].due).atZone(ZoneId.of("Europe/Berlin")).toLocalTime())}
    @Test fun overlappingHoursStillRequireSelection(){val slots=Schedule.on(plan(),start,ZoneId.of("UTC"));assertNull(Schedule.scanCandidate(slots,"medicine"){"unknown"});assertNull(Schedule.scanCandidate(slots,"medicine"){if(it==slots[0].id)"taken" else "unknown"})}
    @Test fun singleCompletedDoseCannotCreateAnother(){val slot=Schedule.on(plan().copy(slots=listOf(Slot(time="08:00",amount=1.0))),start,ZoneId.of("UTC"));assertNull(Schedule.scanCandidate(slot,"medicine"){"taken"});assertNotNull(Schedule.scanCandidate(slot,"medicine"){"unknown"})}
    @Test fun earlyAndOutsideStayDifferent(){val e=Schedule.on(plan(),start,ZoneId.of("UTC")).first();assertEquals("early",Schedule.category(e,e.due-1));assertEquals("outside",Schedule.category(null,e.due));assertEquals("late",Schedule.category(e,e.due+31*60_000))}
    @Test fun repeatedCallbacksNeedPhysicalRemoval(){val gate=ScanGate();assertFalse(gate.read("tag",1000));assertFalse(gate.read("tag",2000));gate.removed("other");assertFalse(gate.read("tag",2500));gate.removed("tag");assertTrue(gate.read("tag",3000));assertFalse(gate.read("tag",3001))}
    @Test fun windowAndDifferentPhysicalTagsCannotConfirm(){val g=ScanGate();g.read("uidA",1000);g.removed("uidA");assertFalse(g.read("uidB",2000));g.removed("uidB");assertFalse(g.read("uidB",40_000))}
    @Test fun rotationResetNeedsNewFirstTap(){val g=ScanGate();g.read("t",0);g.removed("t");g.reset();assertFalse(g.read("t",1000))}
    @Test fun invalidTimesAmountsAndEmptyWeekAreRejected(){assertFalse(Schedule.valid(plan().copy(slots=listOf(Slot(time="99:00",amount=1.0)))));assertFalse(Schedule.valid(plan().copy(slots=listOf(Slot(time="08:00",amount=Double.NaN)))));assertFalse(Schedule.valid(plan("weekly").copy(weekdays=emptySet())))}
    @Test fun stockEstimateIncludesOffDaysAndFractions(){val p=plan("interval");assertEquals(3,Schedule.daysOfStock(1.5,p,start,ZoneId.of("UTC")));assertEquals(0,Schedule.daysOfStock(0.0,p,start,ZoneId.of("UTC")))}
}
