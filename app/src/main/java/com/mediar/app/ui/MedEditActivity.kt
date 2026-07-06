package com.mediar.app.ui

import android.app.AlertDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mediar.app.R
import com.mediar.app.alarm.Alarms
import com.mediar.app.audio.AudioStore
import com.mediar.app.data.Db
import com.mediar.app.data.Med
import com.mediar.app.logic.Schedule
import com.mediar.app.nfc.NfcUtil
import org.json.JSONObject
import java.util.UUID

/** افزودن/ویرایش دارو + ضبط صداها + نوشتن تگ NFC */
class MedEditActivity : AppCompatActivity() {

    private lateinit var med: Med
    private var isNew = false
    private val times = mutableListOf<String>()
    private var writeDialog: AlertDialog? = null
    private var writeMode = false
    @Volatile private var writing = false

    private lateinit var timesText: TextView
    private lateinit var typeSpinner: Spinner
    private lateinit var intervalRow: View
    private lateinit var weeklyRow: LinearLayout
    private lateinit var cycleRow: View
    private val weekChecks = mutableListOf<CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_med_edit)

        val existingId = intent.getStringExtra(EXTRA_MED_ID)
        val existing = existingId?.let { Db.get(this).getMed(it) }
        isNew = existing == null
        med = existing ?: Med(id = UUID.randomUUID().toString(), name = "")

        timesText = findViewById(R.id.timesText)
        typeSpinner = findViewById(R.id.typeSpinner)
        intervalRow = findViewById(R.id.intervalRow)
        weeklyRow = findViewById(R.id.weeklyRow)
        cycleRow = findViewById(R.id.cycleRow)

        findViewById<TextView>(R.id.editTitle).text =
            if (isNew) "داروی جدید" else "ویرایش دارو"

        findViewById<EditText>(R.id.inputName).setText(med.name)
        findViewById<EditText>(R.id.inputDose).setText(trim(med.doseAmount))
        findViewById<EditText>(R.id.inputStock).setText(trim(med.stock))
        findViewById<EditText>(R.id.inputThreshold).setText(trim(med.lowThreshold))

        setupScheduleUi()
        setupAudioRows()

        findViewById<Button>(R.id.btnAddTime).setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                val t = String.format("%02d:%02d", h, m)
                if (!times.contains(t)) times.add(t)
                times.sort()
                renderTimes()
            }, 8, 0, true).show()
        }
        findViewById<Button>(R.id.btnClearTimes).setOnClickListener {
            times.clear()
            renderTimes()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { save(true) }

        findViewById<Button>(R.id.btnWriteTag).setOnClickListener {
            if (!save(false)) return@setOnClickListener
            startWriteTag()
        }

        val btnArchive = findViewById<Button>(R.id.btnArchive)
        btnArchive.visibility = if (isNew) View.GONE else View.VISIBLE
        btnArchive.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("حذف دارو")
                .setMessage("این دارو از لیست حذف شود؟ (تاریخچه نگه داشته می‌شود)")
                .setPositiveButton("حذف") { _, _ ->
                    Db.get(this).archiveMed(med.id)
                    Alarms.cancelAlarm(this, med.id)
                    finish()
                }
                .setNegativeButton("انصراف", null)
                .show()
        }
    }

    override fun onPause() {
        super.onPause()
        stopWriteMode()
        AudioStore.stopAll()
    }

    /** مسیر دوم دریافت تگ: Foreground Dispatch (برای گوشی‌هایی که ReaderMode را با دیالوگ باز تحویل نمی‌دهند) */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (writeMode && !writing) {
            val tag = NfcUtil.tagFromIntent(intent)
            if (tag != null) handleWriteTag(tag)
        }
    }

    // ---------- زمان‌بندی ----------

    private fun setupScheduleUi() {
        val labels = listOf(
            "هر روز",
            "هر چند روز یک‌بار (مثلا یک روز در میون)",
            "روزهای خاص هفته",
            "دوره‌ای (چند روز مصرف، چند روز استراحت)"
        )
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        // چک‌باکس روزهای هفته (مقدار ISO: 1=دوشنبه … 7=یک‌شنبه)
        val dayLabels = listOf(
            6 to "شنبه", 7 to "یک‌شنبه", 1 to "دوشنبه", 2 to "سه‌شنبه",
            3 to "چهارشنبه", 4 to "پنج‌شنبه", 5 to "جمعه"
        )
        for ((iso, label) in dayLabels) {
            val cb = CheckBox(this)
            cb.text = label
            cb.tag = iso
            weekChecks.add(cb)
            weeklyRow.addView(cb)
        }

        // مقداردهی از زمان‌بندی فعلی
        val sched = Schedule(med.scheduleJson)
        times.clear()
        times.addAll(sched.times.map { String.format("%02d:%02d", it.hour, it.minute) })
        renderTimes()

        val obj = try { JSONObject(med.scheduleJson) } catch (e: Exception) { JSONObject() }
        findViewById<EditText>(R.id.inputEvery).setText(obj.optInt("every", 2).toString())
        findViewById<EditText>(R.id.inputOn).setText(obj.optInt("on", 10).toString())
        findViewById<EditText>(R.id.inputOff).setText(obj.optInt("off", 20).toString())
        val days = obj.optJSONArray("days")
        if (days != null) {
            val set = mutableSetOf<Int>()
            for (i in 0 until days.length()) set.add(days.optInt(i))
            for (cb in weekChecks) cb.isChecked = set.contains(cb.tag as Int)
        }

        typeSpinner.setSelection(
            when (sched.type) {
                "interval" -> 1
                "weekly" -> 2
                "cycle" -> 3
                else -> 0
            }
        )
        typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                intervalRow.visibility = if (pos == 1) View.VISIBLE else View.GONE
                weeklyRow.visibility = if (pos == 2) View.VISIBLE else View.GONE
                cycleRow.visibility = if (pos == 3) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    private fun renderTimes() {
        timesText.text = if (times.isEmpty()) "هنوز ساعتی اضافه نشده"
        else "ساعت‌ها: " + times.joinToString("، ")
    }

    private fun buildScheduleJson(): String? {
        if (times.isEmpty()) {
            Toast.makeText(this, "حداقل یک ساعت اضافه کن", Toast.LENGTH_SHORT).show()
            return null
        }
        return when (typeSpinner.selectedItemPosition) {
            1 -> {
                val every = findViewById<EditText>(R.id.inputEvery).text.toString().toIntOrNull() ?: 2
                Schedule.build("interval", times, every = every.coerceAtLeast(1))
            }
            2 -> {
                val days = weekChecks.filter { it.isChecked }.map { it.tag as Int }
                if (days.isEmpty()) {
                    Toast.makeText(this, "حداقل یک روز هفته را انتخاب کن", Toast.LENGTH_SHORT).show()
                    return null
                }
                Schedule.build("weekly", times, days = days)
            }
            3 -> {
                val on = findViewById<EditText>(R.id.inputOn).text.toString().toIntOrNull() ?: 10
                val off = findViewById<EditText>(R.id.inputOff).text.toString().toIntOrNull() ?: 20
                Schedule.build("cycle", times, on = on.coerceAtLeast(1), off = off.coerceAtLeast(0))
            }
            else -> Schedule.build("daily", times)
        }
    }

    // ---------- ذخیره ----------

    private fun save(close: Boolean): Boolean {
        val name = findViewById<EditText>(R.id.inputName).text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "نام دارو را وارد کن", Toast.LENGTH_SHORT).show()
            return false
        }
        val schedule = buildScheduleJson() ?: return false
        med.name = name
        med.doseAmount = findViewById<EditText>(R.id.inputDose).text.toString().toDoubleOrNull() ?: 1.0
        med.stock = findViewById<EditText>(R.id.inputStock).text.toString().toDoubleOrNull() ?: 0.0
        med.lowThreshold = findViewById<EditText>(R.id.inputThreshold).text.toString().toDoubleOrNull() ?: 5.0
        med.scheduleJson = schedule
        Db.get(this).upsertMed(med)
        isNew = false
        Alarms.rescheduleAll(this)
        if (close) {
            Toast.makeText(this, "ذخیره شد \u2705", Toast.LENGTH_SHORT).show()
            finish()
        }
        return true
    }

    private fun trim(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    // ---------- صداها ----------

    private fun setupAudioRows() {
        val container = findViewById<LinearLayout>(R.id.audioContainer)
        container.removeAllViews()
        val items = listOf(
            AudioStore.TYPE_DUE to "«الان وقتشه این قرص رو بخوری»",
            AudioStore.TYPE_TAKEN to "«این قرص رو خوردی، دیگه نخور»",
            AudioStore.TYPE_EARLY to "«هنوز زوده، بعدا بخور»",
            AudioStore.TYPE_NOT_TODAY to "«امروز نوبت این قرص نیست»"
        )
        for ((type, label) in items) {
            container.addView(AudioRow.build(this, med.id, type, label))
        }
    }

    // ---------- نوشتن تگ ----------

    private fun startWriteTag() {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) {
            Toast.makeText(this, "این گوشی NFC ندارد", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(this, "NFC گوشی خاموش است — اول از تنظیمات روشنش کن", Toast.LENGTH_LONG).show()
            return
        }
        writeMode = true
        writeDialog = AlertDialog.Builder(this)
            .setTitle("اتصال تگ به «" + med.name + "»")
            .setMessage("تگ را به پشت گوشی بچسبان و تا پایان کار ثابت نگه دار…")
            .setNegativeButton("انصراف") { _, _ -> stopWriteMode() }
            .setOnCancelListener { stopWriteMode() }
            .show()

        // مسیر ۱: ReaderMode
        adapter.enableReaderMode(
            this,
            { tag -> if (writeMode && !writing) handleWriteTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
            Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
        )
        // مسیر ۲: Foreground Dispatch (اگر ReaderMode روی این گوشی تگ را تحویل ندهد)
        try {
            val flags = if (Build.VERSION.SDK_INT >= 31)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getActivity(
                this, 0,
                Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags
            )
            adapter.enableForegroundDispatch(this, pi, null, null)
        } catch (_: Exception) {
        }
    }

    private fun handleWriteTag(tag: Tag) {
        synchronized(this) {
            if (!writeMode || writing) return
            writing = true
        }
        Thread {
            val err = NfcUtil.writeTag(tag, med.id)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (err == null) {
                    vibrate()
                    // نوشتن تمام شد؛ گیرنده‌ها تا خروج از صفحه فعال ولی غیرمسلح می‌مانند
                    // تا دیدن دوباره همان تگ، نوشتن دوم یا باز شدن صفحه اسکن را رقم نزند
                    writeMode = false
                    writeDialog?.dismiss()
                    writeDialog = null
                    AlertDialog.Builder(this)
                        .setTitle("\u2705 انجام شد")
                        .setMessage("تگ به «" + med.name + "» وصل شد.\nحالا تگ را روی جعبه همین دارو بچسبان.")
                        .setPositiveButton("باشه", null)
                        .show()
                } else {
                    // دیالوگ باز می‌ماند و دوباره گوش می‌دهیم
                    writing = false
                    writeDialog?.setMessage(
                        "\u274c نشد: " + err + "\n" +
                            "تگ را دوباره به پشت گوشی بچسبان و چند ثانیه ثابت نگه دار…"
                    )
                }
            }
        }.start()
    }

    private fun stopWriteMode() {
        writeMode = false
        writing = false
        val adapter = NfcAdapter.getDefaultAdapter(this)
        try { adapter?.disableReaderMode(this) } catch (_: Exception) {}
        try { adapter?.disableForegroundDispatch(this) } catch (_: Exception) {}
        writeDialog?.dismiss()
        writeDialog = null
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    companion object {
        const val EXTRA_MED_ID = "med_id"
    }
}
