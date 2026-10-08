package com.mediar.app.logic

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * قانون زمان‌بندی یک دارو. فرمت JSON:
 *  {"type":"daily","times":["08:00","20:00"]}
 *  {"type":"interval","every":2,"anchor":"2026-07-06","times":["09:00"]}   // هر N روز
 *  {"type":"weekly","days":[1,4],"times":["08:00"]}                        // 1=دوشنبه ... 7=یکشنبه (ISO)
 *  {"type":"cycle","on":10,"off":20,"anchor":"2026-07-01","times":["08:00"]} // دوره‌ای
 */
class Schedule(json: String) {

    private val obj = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
    val type: String = obj.optString("type", "daily")

    val times: List<LocalTime> = run {
        val arr = obj.optJSONArray("times") ?: JSONArray().put("08:00")
        val list = mutableListOf<LocalTime>()
        for (i in 0 until arr.length()) {
            try { list.add(LocalTime.parse(arr.getString(i))) } catch (_: Exception) {}
        }
        if (list.isEmpty()) list.add(LocalTime.of(8, 0))
        list.distinct().sorted()
    }

    val every: Int = obj.optInt("every", 2).coerceAtLeast(1)
    val on: Int = obj.optInt("on", 10).coerceAtLeast(1)
    val off: Int = obj.optInt("off", 20).coerceAtLeast(0)

    val days: Set<Int> = run {
        val arr = obj.optJSONArray("days") ?: JSONArray()
        val set = mutableSetOf<Int>()
        for (i in 0 until arr.length()) set.add(arr.optInt(i))
        set
    }

    /** تاریخ شروع (برای «هر N روز» و «دوره‌ای»)؛ null اگر ذخیره نشده باشد */
    val anchor: LocalDate? =
        try { LocalDate.parse(obj.optString("anchor")) } catch (e: Exception) { null }

    fun isDueOn(date: LocalDate): Boolean = when (type) {
        "daily" -> true
        "interval" -> {
            val a = anchor ?: date
            !date.isBefore(a) && ChronoUnit.DAYS.between(a, date) % every == 0L
        }
        "weekly" -> days.contains(date.dayOfWeek.value)
        "cycle" -> {
            val a = anchor ?: date
            val d = ChronoUnit.DAYS.between(a, date)
            d >= 0 && (d % (on + off)) < on
        }
        else -> true
    }

    /** همه نوبت‌های یک روز مشخص */
    fun dueTimesForDate(date: LocalDate, zone: ZoneId): List<ZonedDateTime> =
        if (isDueOn(date)) times.map { date.atTime(it).atZone(zone) } else emptyList()

    /** همه نوبت‌ها در بازه روزهای [from, toInclusive]، مرتب */
    fun dueTimesBetween(from: LocalDate, toInclusive: LocalDate, zone: ZoneId): List<ZonedDateTime> {
        val out = mutableListOf<ZonedDateTime>()
        var d = from
        while (!d.isAfter(toInclusive)) {
            out.addAll(dueTimesForDate(d, zone))
            d = d.plusDays(1)
        }
        return out
    }

    /** اولین نوبت بعد از زمان داده‌شده (تا حدود یک سال جلوتر جستجو می‌کند) */
    fun nextDue(after: ZonedDateTime): ZonedDateTime? {
        var d = after.toLocalDate()
        for (i in 0 until 400) {
            val hit = dueTimesForDate(d, after.zone).firstOrNull { it.isAfter(after) }
            if (hit != null) return hit
            d = d.plusDays(1)
        }
        return null
    }

    /** آخرین نوبت قبل از زمان داده‌شده (تا حدود یک سال عقب‌تر) */
    fun prevDue(before: ZonedDateTime): ZonedDateTime? {
        var d = before.toLocalDate()
        for (i in 0 until 400) {
            val hit = dueTimesForDate(d, before.zone).lastOrNull { it.isBefore(before) }
            if (hit != null) return hit
            d = d.minusDays(1)
        }
        return null
    }

    /** توضیح فارسی برای نمایش در لیست‌ها */
    fun describe(): String {
        val t = times.joinToString("، ") { String.format("%02d:%02d", it.hour, it.minute) }
        return when (type) {
            "daily" -> "هر روز ساعت $t"
            "interval" -> "هر $every روز یک‌بار ساعت $t"
            "weekly" -> {
                val order = listOf(6, 7, 1, 2, 3, 4, 5)
                "روزهای " + order.filter { days.contains(it) }
                    .joinToString("، ") { Fmt.dayNameIso(it) } + " ساعت $t"
            }
            "cycle" -> "$on روز مصرف، $off روز استراحت — ساعت $t"
            else -> t
        }
    }

    companion object {
        fun build(
            type: String,
            times: List<String>,
            every: Int = 2,
            days: List<Int> = emptyList(),
            on: Int = 10,
            off: Int = 20,
            anchor: LocalDate = LocalDate.now()
        ): String {
            val o = JSONObject()
            o.put("type", type)
            o.put("times", JSONArray(times.distinct().sorted()))
            when (type) {
                "interval" -> { o.put("every", every); o.put("anchor", anchor.toString()) }
                "weekly" -> o.put("days", JSONArray(days.distinct().sorted()))
                "cycle" -> { o.put("on", on); o.put("off", off); o.put("anchor", anchor.toString()) }
            }
            return o.toString()
        }
    }
}
