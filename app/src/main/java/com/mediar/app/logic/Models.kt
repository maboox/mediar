package com.mediar.app.logic

import java.time.*
import java.time.temporal.ChronoUnit
import java.util.UUID

data class Slot(val id: String = UUID.randomUUID().toString(), val time: String, val amount: Double, val group: String = "")
data class Plan(
    val id: String = UUID.randomUUID().toString(), val medicine: String,
    val start: LocalDate = LocalDate.now(), val end: LocalDate? = null,
    val mode: String = "daily", val weekdays: Set<Int> = (1..7).toSet(),
    val interval: Int = 1, val on: Int = 1, val off: Int = 1, val paused: Boolean = false,
    val slots: List<Slot>, val effectiveFrom: LocalDate = start, val effectiveTo: LocalDate? = null
)
data class Medicine(val id: String, val name: String, val note: String, val unit: String, val low: Double, val archived: Boolean, val stock: Double)
data class Expected(val id: String, val plan: String, val medicine: String, val slot: String, val group: String,
    val date: LocalDate, val due: Long, val amount: Double, val name: String, val unit: String, val obsolete: Boolean = false)
data class Event(val id: String, val medicine: String, val expected: String?, val takenAt: Long, val recordedAt: Long,
    val amount: Double, val method: String, val category: String, val corrected: Boolean, val request: String,
    val name: String, val unit: String, val correction: String = "")
data class Group(val id: String, val name: String)
data class Binding(val token: String, val kind: String, val target: String, val active: Boolean)
data class Reminder(val id: String, val next: Long, val attempt: Int, val ended: Boolean, val snoozes: Int)
data class Snapshot(val medicines: List<Medicine> = emptyList(), val plans: List<Plan> = emptyList(),
    val expected: List<Expected> = emptyList(), val events: List<Event> = emptyList(),
    val declined: Set<String> = emptySet(), val groups: List<Group> = emptyList(),
    val tags: List<Binding> = emptyList(), val settings: Map<String, String> = emptyMap()) {
    fun status(id: String) = when {
        events.any { it.expected == id && !it.corrected } -> "taken"
        id in declined -> "declined"
        else -> "unknown"
    }
    fun event(id: String) = events.firstOrNull { it.expected == id && !it.corrected }
    fun today(date: LocalDate = LocalDate.now()) = expected.filter { it.date == date && !it.obsolete && medicines.none { med -> med.id==it.medicine && med.archived } }.sortedBy { it.due }
}
object Schedule {
    fun valid(p: Plan): Boolean = p.mode in setOf("daily", "weekly", "interval", "cycle") &&
        p.interval in 1..365 && p.on in 1..365 && p.off in 1..365 &&
        (p.mode != "weekly" || p.weekdays.isNotEmpty()) && p.weekdays.all { it in 1..7 } &&
        (p.end == null || !p.end.isBefore(p.start)) && p.slots.isNotEmpty() && p.slots.size <= 12 &&
        p.slots.map { it.time }.distinct().size == p.slots.size && p.slots.all { it.amount.isFinite() && it.amount > 0 && runCatching { LocalTime.parse(it.time) }.isSuccess }
    fun active(p: Plan, day: LocalDate): Boolean {
        if (p.paused || day < p.start || day < p.effectiveFrom || (p.end != null && day > p.end) || (p.effectiveTo != null && day > p.effectiveTo)) return false
        val distance = ChronoUnit.DAYS.between(p.start, day)
        return when (p.mode) {
            "weekly" -> day.dayOfWeek.value in p.weekdays
            "interval" -> distance % p.interval == 0L
            "cycle" -> distance % (p.on + p.off) < p.on
            else -> true
        }
    }
    fun on(p: Plan, day: LocalDate, zone: ZoneId, name: String = "", unit: String = ""): List<Expected> =
        if (!active(p, day)) emptyList() else p.slots.map { s ->
            Expected(UUID.nameUUIDFromBytes("${p.id}|$day|${s.id}".toByteArray(Charsets.UTF_8)).toString(), p.id, p.medicine, s.id, s.group,
                day, day.atTime(LocalTime.parse(s.time)).atZone(zone).toInstant().toEpochMilli(), s.amount, name, unit)
        }
    fun category(e: Expected?, takenAt: Long) = when {
        e == null -> "outside"
        takenAt < e.due -> "early"
        takenAt > e.due + 30 * 60_000 -> "late"
        else -> "scheduled"
    }
    // Auto-confirm only the single defined slot for that medicine today.
    // A completed morning slot must not silently select an evening dose.
    fun scanCandidate(allToday: List<Expected>, medicine: String, status: (String) -> String): Expected? =
        allToday.filter { it.medicine == medicine && !it.obsolete }.singleOrNull()?.takeIf { status(it.id) == "unknown" }
    fun daysOfStock(stock: Double, p: Plan, from: LocalDate, zone: ZoneId): Int? {
        if (stock <= 0) return 0
        var used = 0.0
        for (n in 0..730) { used += on(p, from.plusDays(n.toLong()), zone).sumOf { it.amount }; if (used > stock) return n }
        return null
    }
}

class ScanGate(private val window: Long = 30_000) {
    private var tag: String? = null
    private var first = 0L
    private var detached = false
    @Synchronized fun read(physicalTag: String, now: Long): Boolean {
        if (physicalTag == tag && detached && now - first in 500..window) { reset(); return true }
        if (physicalTag != tag || now - first > window) { tag = physicalTag; first = now; detached = false }
        return false
    }
    @Synchronized fun removed(physicalTag: String) { if (physicalTag == tag) detached = true }
    @Synchronized fun reset() { tag = null; first = 0; detached = false }
}
