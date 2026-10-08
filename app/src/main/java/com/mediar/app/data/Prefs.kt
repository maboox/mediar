package com.mediar.app.data

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

class Prefs private constructor(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.getSharedPreferences("mediar_prefs", Context.MODE_PRIVATE)

    /** پنجره قبول زودتر از موعد (دقیقه) — پیش‌فرض ۲ ساعت */
    var earlyWindowMin: Long
        get() = sp.getLong("early_window_min", 120L)
        set(v) = sp.edit().putLong("early_window_min", v).apply()

    /** فاصله تکرار آلارم (دقیقه) */
    var repeatMin: Long
        get() = sp.getLong("repeat_min", 10L)
        set(v) = sp.edit().putLong("repeat_min", v).apply()

    /** حداکثر تعداد تکرار آلارم برای هر نوبت */
    var maxRepeats: Int
        get() = sp.getInt("max_repeats", 12)
        set(v) = sp.edit().putInt("max_repeats", v).apply()

    /** پنجره قبول دیرتر از موعد (دقیقه) — بعد از این، نوبت «جامانده» حساب می‌شود */
    var lateWindowMin: Long
        get() = sp.getLong("late_window_min", 360L)
        set(v) = sp.edit().putLong("late_window_min", v).apply()

    /**
     * حالت ثبت با یک‌بار زدن: اولین اسکن در وقت نوبت، همان لحظه «خورده شد» ثبت می‌شود.
     * حالت پیش‌فرض (false): اسکن اول می‌گوید «وقتشه بخور» و اسکن دوم (یا دکمه «خوردم») ثبت می‌کند.
     */
    var singleTap: Boolean
        get() = sp.getBoolean("single_tap", false)
        set(v) = sp.edit().putBoolean("single_tap", v).apply()

    /** نوبتی که با اسکن اول «آماده ثبت» شده (موعد به epoch millis) */
    fun armedDue(medId: String): Long = sp.getLong("armed_$medId", 0L)

    fun setArmed(medId: String, dueAt: Long) {
        sp.edit().putLong("armed_$medId", dueAt).apply()
    }

    fun clearArmed(medId: String) {
        sp.edit().remove("armed_$medId").apply()
    }

    // ---------- PIN ----------

    fun hasPin(): Boolean = sp.getString("pin_hash", null) != null

    fun setPin(pin: String) {
        sp.edit().putString("pin_hash", hash(pin)).apply()
    }

    fun checkPin(pin: String): Boolean = sp.getString("pin_hash", null) == hash(pin)

    private fun hash(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(("mediar:" + s).toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    companion object {
        @Volatile private var inst: Prefs? = null
        fun get(ctx: Context): Prefs =
            inst ?: synchronized(this) {
                inst ?: Prefs(ctx.applicationContext).also { inst = it }
            }
    }
}
