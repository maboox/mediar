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
        list.sorted()
    }

    fun isDueOn(date: LocalDate): Boolean = when (type) {
        "daily" -> true
        "interval" -> {
            val anchor = parseAnchor()
            val every = obj.optInt("every", 2).coerceAtLeast(1)
            !date.isBefore(anchor) && ChronoUnit.DAYS.between(anchor, date) % every == 0L
        }
        "weekly" -> {
            val days = obj.optJSONArray("days") ?: JSONArray()
            var found = false
            for (i in 0 until days.length()) {
                if (days.optInt(i) == date.dayOfWeek.value) found = true
            }
            found
        }
        "cycle" -> {
            val anchor = parseAnchor()
            val on = obj.optInt("on", 10).coerceAtLeast(1)
            val off = obj.optInt("off", 20).coerceAtLeast(0)
            val d = ChronoUnit.DAYS.between(anchor, date)
            d >= 0 && (d % (on + off)) < on
        }
        else -> true
    }

    private fun parseAnchor(): LocalDate =
        try { LocalDate.parse(obj.optString("anchor")) } catch (e: Exception) { LocalDate.now() }

    /** همه نوبت‌های یک روز مشخص */
    fun dueTimesForDate(date: LocalDate, zone: ZoneId): List<ZonedDateTime> =
        if (isDueOn(date)) times.map { date.atTime(it).atZone(zone) } else emptyList()

    /** اولین نوبت بعد از زمان داده‌شده (تا یک سال جلوتر جستجو می‌کند) */
    fun nextDue(after: ZonedDateTime): ZonedDateTime? {
        var d = after.toLocalDate()
        for (i in 0 until 370) {
            val hit = dueTimesForDate(d, after.zone).firstOrNull { it.isAfter(after) }
            if (hit != null) return hit
            d = d.plusDays(1)
        }
        return null
    }

    /** توضیح فارسی برای نمایش در لیست‌ها */
    fun describe(): String {
        val t = times.joinToString("، ") { String.format("%02d:%02d", it.hour, it.minute) }
        return when (type) {
            "daily" -> "هر روز ساعت $t"
            "interval" -> "هر " + obj.optInt("every", 2) + " روز یک‌بار ساعت $t"
            "weekly" -> {
                val names = mapOf(
                    1 to "دوشنبه", 2 to "سه‌شنبه", 3 to "چهارشنبه",
                    4 to "پنج‌شنبه", 5 to "جمعه", 6 to "شنبه", 7 to "یک‌شنبه"
                )
                val days = obj.optJSONArray("days") ?: JSONArray()
                val list = mutableListOf<String>()
                for (i in 0 until days.length()) names[days.optInt(i)]?.let { list.add(it) }
                "روزهای " + list.joinToString("، ") + " ساعت $t"
            }
            "cycle" -> obj.optInt("on", 10).toString() + " روز مصرف، " +
                obj.optInt("off", 20) + " روز استراحت — ساعت $t"
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
            anchor: String = LocalDate.now().toString()
        ): String {
            val o = JSONObject()
            o.put("type", type)
            o.put("times", JSONArray(times))
            when (type) {
                "interval" -> { o.put("every", every); o.put("anchor", anchor) }
                "weekly" -> o.put("days", JSONArray(days))
                "cycle" -> { o.put("on", on); o.put("off", off); o.put("anchor", anchor) }
            }
            return o.toString()
        }
    }
}
