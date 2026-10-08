package com.mediar.app.logic

import java.time.LocalDate
import java.time.ZonedDateTime

/** قالب‌بندی متن‌های فارسی زمان */
object Fmt {

    private val dayNames = mapOf(
        1 to "دوشنبه", 2 to "سه‌شنبه", 3 to "چهارشنبه",
        4 to "پنج‌شنبه", 5 to "جمعه", 6 to "شنبه", 7 to "یک‌شنبه"
    )

    private val monthNames = listOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    fun time(z: ZonedDateTime): String = String.format("%02d:%02d", z.hour, z.minute)

    fun dayNameIso(iso: Int): String = dayNames[iso] ?: ""

    fun dayName(d: LocalDate): String = dayNameIso(d.dayOfWeek.value)

    /** تاریخ شمسی، مثلا «۱۷ مهر» */
    fun jalali(d: LocalDate): String {
        val (_, m, day) = toJalali(d)
        return "$day " + monthNames[m - 1]
    }

    /** «امروز ساعت ۸:۰۰»، «فردا ساعت …»، «شنبه ساعت …»، «شنبه ۱۷ مهر ساعت …» */
    fun whenText(z: ZonedDateTime?, now: ZonedDateTime = ZonedDateTime.now()): String {
        if (z == null) return "نامشخص"
        val d = z.toLocalDate()
        val today = now.toLocalDate()
        return when {
            d == today -> "امروز ساعت " + time(z)
            d == today.plusDays(1) -> "فردا ساعت " + time(z)
            d == today.minusDays(1) -> "دیروز ساعت " + time(z)
            d.isAfter(today) && d.isBefore(today.plusDays(7)) -> dayName(d) + " ساعت " + time(z)
            else -> dayName(d) + " " + jalali(d) + " ساعت " + time(z)
        }
    }

    /** زمان برای خواندن با صدا: «ساعت ۸ صبح»، «ساعت ۹ و ۳۰ دقیقه شب» */
    fun spokenTime(z: ZonedDateTime): String {
        val h = z.hour
        val h12 = if (h % 12 == 0) 12 else h % 12
        val part = when (h) {
            in 0..3 -> "بامداد"
            in 4..11 -> "صبح"
            in 12..13 -> "ظهر"
            in 14..16 -> "بعد از ظهر"
            in 17..19 -> "عصر"
            else -> "شب"
        }
        val m = if (z.minute == 0) "" else " و " + z.minute + " دقیقه"
        return "ساعت $h12$m $part"
    }

    /** مثل whenText ولی برای خواندن با صدا */
    fun spokenWhen(z: ZonedDateTime?, now: ZonedDateTime = ZonedDateTime.now()): String {
        if (z == null) return ""
        val d = z.toLocalDate()
        val today = now.toLocalDate()
        val day = when {
            d == today -> "امروز"
            d == today.plusDays(1) -> "فردا"
            else -> dayName(d)
        }
        return day + " " + spokenTime(z)
    }

    fun delayText(delayMin: Long): String = when {
        delayMin <= 0 -> "سر وقت"
        delayMin < 60 -> "$delayMin دقیقه تأخیر"
        delayMin % 60 == 0L -> (delayMin / 60).toString() + " ساعت تأخیر"
        else -> (delayMin / 60).toString() + " ساعت و " + (delayMin % 60) + " دقیقه تأخیر"
    }

    /** مدت به فارسی: «۴۵ دقیقه»، «۲ ساعت و ۱۰ دقیقه» */
    fun duration(min: Long): String = when {
        min < 60 -> "$min دقیقه"
        min % 60 == 0L -> (min / 60).toString() + " ساعت"
        else -> (min / 60).toString() + " ساعت و " + (min % 60) + " دقیقه"
    }

    fun num(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    /** تبدیل میلادی به شمسی (الگوریتم استاندارد) — خروجی: سال، ماه، روز */
    fun toJalali(date: LocalDate): Triple<Int, Int, Int> {
        val gy = date.year
        val gm = date.monthValue
        val gd = date.dayOfMonth
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) +
            ((gy2 + 399) / 400) + gd + gdm[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return Triple(jy, jm, jd)
    }
}
