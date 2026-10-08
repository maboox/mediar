package com.mediar.app.logic

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Dose window used when a medicine tag is tapped.
 * Windows of neighbouring doses of the same medicine never overlap, so a missed
 * morning dose can never be recorded against the evening slot (and vice versa).
 */
data class DoseWindow(val dose: Expected, val start: Long, val end: Long) {
    fun contains(t: Long) = t >= start && t < end
}

sealed class TapVerdict {
    /** The current dose is open and unrecorded. */
    data class Due(val dose: Expected, val window: DoseWindow) : TapVerdict()
    /** The current dose is already recorded. Taking it again would be a double dose. */
    data class Taken(val dose: Expected, val event: Event, val next: Expected?) : TapVerdict()
    /** The current dose was explicitly declared as not taken. */
    data class Declined(val dose: Expected, val next: Expected?) : TapVerdict()
    /** A dose was just taken (e.g. a late previous dose); the next one must wait. */
    data class TooSoon(val dose: Expected, val last: Event, val allowedAt: Long) : TapVerdict()
    /** Another dose is planned later today. */
    data class TooEarly(val next: DoseWindow, val lastToday: Event?, val missed: Expected?) : TapVerdict()
    /** Today's doses are over (recorded or missed). */
    data class Done(val next: Expected?, val lastToday: Event?, val missed: Expected?) : TapVerdict()
    /** No dose of this medicine today. */
    data class NotToday(val next: Expected?) : TapVerdict()
}

/** Pending first tap, waiting for the confirming second tap. */
data class TapArm(val token: String, val physical: String, val expected: String, val at: Long, val detached: Boolean = false)

object Tap {
    const val EARLY = 120 * 60_000L
    const val LATE = 360 * 60_000L
    /** How long after the first tap a second tap still confirms. */
    const val CONFIRM_WINDOW = 15 * 60_000L
    /** Minimum gap when removal of the tag was observed by the platform. */
    const val MIN_GAP_DETACHED = 1_500L
    /** Without a removal callback, a re-read this soon is treated as the same touch. */
    const val MIN_GAP_UNSEEN = 8_000L

    private fun startOf(due: Long, prev: Long?, early: Long): Long {
        var s = due - early
        if (prev != null) s = maxOf(s, prev + (due - prev) / 2)
        return s
    }

    /** Windows for the doses of one medicine (any order in, sorted by due out). */
    fun windows(doses: List<Expected>, early: Long = EARLY, late: Long = LATE): List<DoseWindow> {
        val sorted = doses.filter { !it.obsolete }.sortedBy { it.due }
        return sorted.mapIndexed { i, e ->
            val prev = sorted.getOrNull(i - 1)?.due
            val next = sorted.getOrNull(i + 1)?.due
            val start = startOf(e.due, prev, early)
            var end = e.due + late
            if (next != null) end = minOf(end, startOf(next, e.due, early))
            DoseWindow(e, start, maxOf(end, e.due + 60_000))
        }
    }

    /**
     * Minimum time between the last recorded consumption and the current dose:
     * half of the planned gap between their doses (max 12 h); 60 minutes for an
     * outside-plan record.
     */
    fun minGap(current: Expected, lastDue: Long?): Long {
        if (lastDue == null) return 60 * 60_000L
        if (lastDue >= current.due) return 0
        return ((current.due - lastDue) / 2).coerceIn(0, 12 * 3_600_000L)
    }

    fun decide(
        s: Snapshot, medicine: String, now: Long,
        zone: ZoneId = ZoneId.systemDefault(), early: Long = EARLY, late: Long = LATE
    ): TapVerdict {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val doses = s.expected.filter { it.medicine == medicine && !it.obsolete && it.date >= today.minusDays(3) }
        val ws = windows(doses, early, late)
        val events = s.events.filter { it.medicine == medicine && !it.corrected && it.takenAt <= now + 60_000 }
        val last = events.maxByOrNull { it.takenAt }
        val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val lastToday = last?.takeIf { it.takenAt >= startOfToday }
        fun nextAfter(e: Expected) = ws.firstOrNull { it.dose.due > e.due }?.dose

        val current = ws.firstOrNull { it.contains(now) }
        if (current != null) {
            val dose = current.dose
            when (s.status(dose.id)) {
                "taken" -> return TapVerdict.Taken(dose, s.event(dose.id)!!, nextAfter(dose))
                "declined" -> return TapVerdict.Declined(dose, nextAfter(dose))
            }
            if (last != null) {
                val lastDue = last.expected?.let { id -> s.expected.firstOrNull { it.id == id }?.due }
                val allowedAt = last.takenAt + minGap(dose, lastDue)
                if (now < allowedAt) return TapVerdict.TooSoon(dose, last, allowedAt)
            }
            return TapVerdict.Due(dose, current)
        }

        val missed = ws.lastOrNull { it.end <= now && it.end > now - 24 * 3_600_000L && s.status(it.dose.id) == "unknown" }?.dose
        val upcoming = ws.firstOrNull { it.start > now }
        if (upcoming != null && upcoming.dose.date == today) return TapVerdict.TooEarly(upcoming, lastToday, missed)
        val hadToday = ws.any { it.dose.date == today }
        return if (hadToday || missed != null) TapVerdict.Done(upcoming?.dose, lastToday, missed)
        else TapVerdict.NotToday(upcoming?.dose)
    }

    /** Does this tap confirm the pending first tap? */
    fun confirms(arm: TapArm?, token: String, physical: String, expected: String, now: Long): Boolean {
        if (arm == null || arm.token != token || arm.physical != physical || arm.expected != expected) return false
        val elapsed = now - arm.at
        val min = if (arm.detached) MIN_GAP_DETACHED else MIN_GAP_UNSEEN
        return elapsed in min..CONFIRM_WINDOW
    }

    fun today(now: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
}
