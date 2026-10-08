package com.mediar.app.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.mediar.app.audio.AudioStore

/** یک ردیف ضبط/پخش صدا که در تنظیمات و صفحه دارو استفاده می‌شود */
object AudioRow {

    /** همه ردیف‌های ساخته‌شده در صفحه فعلی — برای به‌روز کردن وضعیت دکمه‌ها */
    private val refreshers = mutableListOf<() -> Unit>()

    fun clear() = refreshers.clear()

    fun build(activity: Activity, medId: String?, type: String, label: String): LinearLayout {
        val key = AudioStore.key(medId, type)
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(16, 16, 16, 16)

        val title = TextView(activity)
        title.textSize = 15f
        row.addView(title)

        val buttons = LinearLayout(activity)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.START
        row.addView(buttons)

        val btnRecord = Button(activity)
        val btnPlay = Button(activity)
        val btnDelete = Button(activity)
        buttons.addView(btnRecord)
        buttons.addView(btnPlay)
        buttons.addView(btnDelete)

        fun refresh() {
            val has = AudioStore.has(activity, medId, type)
            val recordingHere = AudioStore.recordingKey == key
            title.text = label + when {
                recordingHere -> "  🔴 در حال ضبط…"
                has -> "  ✅"
                else -> "  (ضبط نشده)"
            }
            title.setTextColor(
                when {
                    recordingHere -> Color.parseColor("#C62828")
                    has -> Color.parseColor("#2E7D32")
                    else -> Color.parseColor("#757575")
                }
            )
            btnRecord.text = if (recordingHere) "⏹ تمام" else "🎙 ضبط"
            btnPlay.text = "▶ پخش"
            btnPlay.isEnabled = has && !recordingHere
            btnDelete.text = "حذف"
            btnDelete.isEnabled = has && !recordingHere
        }
        refreshers.add(::refresh)

        btnRecord.setOnClickListener {
            if (AudioStore.recordingKey == key) {
                if (AudioStore.stopRecording()) {
                    Toast.makeText(activity, "ضبط شد ✅", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "ضبط خیلی کوتاه بود یا ناموفق شد — دوباره امتحان کن", Toast.LENGTH_LONG).show()
                }
            } else {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
                    Toast.makeText(activity, "اول اجازه میکروفون را بده و دوباره «ضبط» را بزن", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (AudioStore.startRecording(activity, medId, type)) {
                    Toast.makeText(activity, "در حال ضبط… حرفت را بزن و بعد «تمام» را بزن", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "ضبط ممکن نشد — مجوز میکروفون را بررسی کن", Toast.LENGTH_LONG).show()
                }
            }
            refreshers.forEach { it() }
        }

        btnPlay.setOnClickListener {
            if (!AudioStore.play(activity, medId, type)) {
                Toast.makeText(activity, "فایلی برای پخش نیست", Toast.LENGTH_SHORT).show()
            }
        }

        btnDelete.setOnClickListener {
            AudioStore.delete(activity, medId, type)
            refreshers.forEach { it() }
        }

        refresh()
        return row
    }
}
