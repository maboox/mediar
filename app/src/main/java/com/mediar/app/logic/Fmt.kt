package com.mediar.app.logic

import java.time.LocalDate
import java.time.ZonedDateTime

/** قالب‌بندی متن‌های فارسی زمان */
object Fmt {

    private val dayNames = mapOf(
        1 to "دوشنبه", 2 to "سه‌شنبه", 3 to "چهارشنبه",
        4 to "پنج‌شنبه", 5 to "جمعه", 6 to "شنبه", 7 to "یک‌شنبه"
    )

    fun time(z: ZonedDateTime): String = String.format("%02d:%02d", z.hour, z.minute)

    fun dayName(d: LocalDate): String = dayNames[d.dayOfWeek.value] ?: ""

    /** «امروز ساعت ۸:۰۰»، «فردا ساعت …»، «شنبه …» */
    fun whenText(z: ZonedDateTime?, now: ZonedDateTime = ZonedDateTime.now()): String {
        if (z == null) return "نامشخص"
        val d = z.toLocalDate()
        val today = now.toLocalDate()
        return when (d) {
            today -> "امروز ساعت " + time(z)
            today.plusDays(1) -> "فردا ساعت " + time(z)
            else -> dayName(d) + " " + d.toString() + " ساعت " + time(z)
        }
    }

    fun delayText(delayMin: Long): String = when {
        delayMin <= 0 -> "سر وقت"
        delayMin < 60 -> "$delayMin دقیقه تأخیر"
        else -> (delayMin / 60).toString() + " ساعت و " + (delayMin % 60) + " دقیقه تأخیر"
    }
}
