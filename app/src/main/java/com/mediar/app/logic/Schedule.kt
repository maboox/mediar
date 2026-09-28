package com.mediar.app.logic

import java.time.*
import java.time.temporal.ChronoUnit
import java.util.UUID

data class SlotSpec(val id: String, val time: String, val quantity: Double, val groupId: String = "")
data class Plan(val id: String, val medicineId: String, val starts: LocalDate, val ends: LocalDate?, val mode: String, val weekDays: Set<Int>, val everyDays: Int, val onDays: Int, val offDays: Int, val paused: Boolean, val slots: List<SlotSpec>)
data class Expected(val id: String, val planId: String, val medicineId: String, val slotId: String, val groupId: String, val due: ZonedDateTime, val quantity: Double)

object Schedule {
    fun active(plan: Plan, date: LocalDate): Boolean {
        if (plan.paused || date.isBefore(plan.starts) || (plan.ends != null && date.isAfter(plan.ends))) return false
        val d = ChronoUnit.DAYS.between(plan.starts, date)
        return when (plan.mode) {
            "weekly" -> date.dayOfWeek.value in plan.weekDays
            "interval" -> d % plan.everyDays.coerceAtLeast(1) == 0L
            "cycle" -> plan.onDays > 0 && d % (plan.onDays + plan.offDays).coerceAtLeast(1) < plan.onDays
            else -> true
        }
    }
    fun on(plan: Plan, date: LocalDate, zone: ZoneId): List<Expected> = if (!active(plan, date)) emptyList() else plan.slots.mapNotNull { s ->
        runCatching {
            Expected(UUID.nameUUIDFromBytes("${plan.id}|$date|${s.id}".toByteArray()).toString(), plan.id, plan.medicineId, s.id, s.groupId, date.atTime(LocalTime.parse(s.time)).atZone(zone), s.quantity)
        }.getOrNull()
    }
    fun horizon(plans: List<Plan>, from: LocalDate, days: Long, zone: ZoneId): List<Expected> = (0..days).flatMap { offset -> plans.flatMap { on(it, from.plusDays(offset), zone) } }.sortedBy { it.due.toInstant() }
    fun classification(expected: Expected?, at: Instant): String = when {
        expected == null -> "outside"
        at.isBefore(expected.due.toInstant()) -> "early"
        else -> "scheduled"
    }
}

/** A second read is accepted only after a removal callback from the same physical tag. */
class ScanGate(private val windowMs: Long = 30_000L) {
    private var token: String? = null
    private var started = 0L
    private var removed = false
    @Synchronized fun read(value: String, now: Long): Boolean {
        if (value == token && removed && now - started in 250..windowMs) { reset(); return true }
        if (value != token || now - started > windowMs) { token = value; started = now; removed = false }
        return false
    }
    @Synchronized fun removal(value: String) { if (value == token) removed = true }
    @Synchronized fun reset() { token = null; started = 0L; removed = false }
}
