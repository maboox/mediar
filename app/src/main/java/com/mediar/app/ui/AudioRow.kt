package com.mediar.app.ui

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mediar.app.audio.AudioStore

/** یک ردیف ضبط/پخش صدا که در تنظیمات و صفحه دارو استفاده می‌شود */
object AudioRow {

    fun build(activity: Activity, medId: String?, type: String, label: String): LinearLayout {
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
            title.text = label + if (has) "  \u2705" else "  (ضبط نشده)"
            title.setTextColor(if (has) Color.parseColor("#2E7D32") else Color.parseColor("#757575"))
            btnRecord.text = if (AudioStore.isRecording()) "ضبط تموم" else "ضبط"
            btnPlay.text = "پخش"
            btnPlay.isEnabled = has
            btnDelete.text = "حذف"
            btnDelete.isEnabled = has
        }

        var recordingHere = false

        btnRecord.setOnClickListener {
            if (recordingHere) {
                AudioStore.stopRecording()
                recordingHere = false
                Toast.makeText(activity, "ضبط شد \u2705", Toast.LENGTH_SHORT).show()
            } else {
                if (AudioStore.startRecording(activity, medId, type)) {
                    recordingHere = true
                    Toast.makeText(activity, "در حال ضبط… دوباره بزن تا تموم بشه", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "ضبط ممکن نشد — مجوز میکروفون را بررسی کن", Toast.LENGTH_LONG).show()
                }
            }
            refresh()
        }

        btnPlay.setOnClickListener {
            if (!AudioStore.play(activity, medId, type)) {
                Toast.makeText(activity, "فایلی برای پخش نیست", Toast.LENGTH_SHORT).show()
            }
        }

        btnDelete.setOnClickListener {
            AudioStore.delete(activity, medId, type)
            refresh()
        }

        refresh()
        return row
    }
}
