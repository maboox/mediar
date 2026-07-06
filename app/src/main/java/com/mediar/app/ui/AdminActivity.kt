package com.mediar.app.ui

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mediar.app.R
import com.mediar.app.alarm.Alarms
import com.mediar.app.audio.AudioStore
import com.mediar.app.backup.Backup
import com.mediar.app.data.Db
import com.mediar.app.data.Prefs
import com.mediar.app.logic.Schedule
import com.mediar.app.nfc.NfcUtil
import kotlin.system.exitProcess

/** بخش مدیریت (مخصوص شما) — لیست داروها، تنظیمات، صداهای عمومی، پشتیبان */
class AdminActivity : AppCompatActivity() {

    private lateinit var medList: LinearLayout
    private lateinit var audioContainer: LinearLayout
    private var writeDialog: AlertDialog? = null
    private var writeMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        medList = findViewById(R.id.medList)
        audioContainer = findViewById(R.id.genericAudioContainer)

        findViewById<Button>(R.id.btnAddMed).setOnClickListener {
            startActivity(Intent(this, MedEditActivity::class.java))
        }
        findViewById<Button>(R.id.btnDashboard).setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
        }
        findViewById<Button>(R.id.btnWriteReportTag).setOnClickListener {
            startWriteReportTag()
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.btnExactAlarm).setOnClickListener { requestExactAlarm() }
        findViewById<Button>(R.id.btnBackup).setOnClickListener {
            val f = Backup.exportZip(this)
            Backup.share(this, f, "application/zip", "ذخیره پشتیبان")
        }
        findViewById<Button>(R.id.btnRestore).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
            }
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_IMPORT)
        }

        val prefs = Prefs.get(this)
        val earlyInput = findViewById<EditText>(R.id.inputEarlyWindow)
        val repeatInput = findViewById<EditText>(R.id.inputRepeatMin)
        earlyInput.setText(prefs.earlyWindowMin.toString())
        repeatInput.setText(prefs.repeatMin.toString())
        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            prefs.earlyWindowMin = earlyInput.text.toString().toLongOrNull()?.coerceIn(0, 720) ?: 120L
            prefs.repeatMin = repeatInput.text.toString().toLongOrNull()?.coerceIn(1, 120) ?: 10L
            Alarms.rescheduleAll(this)
            Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
        }

        buildGenericAudioRows()
        maybeRequestRecordPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshMeds()
    }

    override fun onPause() {
        super.onPause()
        stopWriteMode()
        AudioStore.stopAll()
    }

    /** مسیر دوم دریافت تگ در حالت نوشتن (Foreground Dispatch) */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (writeMode) {
            val tag = NfcUtil.tagFromIntent(intent)
            if (tag != null) handleWriteTag(tag)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val ok = contentResolver.openInputStream(uri)?.use { Backup.importZip(this, it) } ?: false
            if (ok) {
                Toast.makeText(this, "بازیابی انجام شد — اپ دوباره باز می‌شود", Toast.LENGTH_LONG).show()
                val restart = packageManager.getLaunchIntentForPackage(packageName)
                restart?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(restart)
                finishAffinity()
                exitProcess(0)
            } else {
                Toast.makeText(this, "بازیابی ناموفق بود", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshMeds() {
        medList.removeAllViews()
        val meds = Db.get(this).activeMeds()
        if (meds.isEmpty()) {
            val tv = TextView(this)
            tv.text = "هنوز دارویی ثبت نشده. با دکمه بالا اولین دارو را اضافه کن."
            tv.textSize = 16f
            tv.setPadding(24, 24, 24, 24)
            medList.addView(tv)
            return
        }
        for (med in meds) {
            val tv = TextView(this)
            val voiceOk = AudioStore.has(this, med.id, AudioStore.TYPE_DUE)
            tv.text = med.name + "\n" + Schedule(med.scheduleJson).describe() +
                "\nموجودی: " + trim(med.stock) +
                (if (voiceOk) "  \uD83C\uDFA4" else "  (بدون صدا)")
            tv.textSize = 17f
            tv.setPadding(28, 28, 28, 28)
            tv.setBackgroundColor(Color.parseColor("#F5F5F5"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, 16)
            tv.layoutParams = lp
            tv.setOnClickListener {
                startActivity(
                    Intent(this, MedEditActivity::class.java)
                        .putExtra(MedEditActivity.EXTRA_MED_ID, med.id)
                )
            }
            medList.addView(tv)
        }
    }

    private fun trim(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    // ---------- صداهای عمومی ----------

    private fun buildGenericAudioRows() {
        audioContainer.removeAllViews()
        val items = listOf(
            AudioStore.TYPE_DUE to "پیام عمومی «الان وقتشه بخوری»",
            AudioStore.TYPE_TAKEN to "پیام عمومی «این رو خوردی، دیگه نخور»",
            AudioStore.TYPE_EARLY to "پیام عمومی «هنوز زوده»",
            AudioStore.TYPE_NOT_TODAY to "پیام عمومی «امروز نوبتش نیست»",
            AudioStore.TYPE_REPORT_DONE to "گزارش: «همه رو خوردی، آفرین»",
            AudioStore.TYPE_REPORT_REMAINING to "گزارش: «هنوز قرص مونده»"
        )
        for ((type, label) in items) {
            audioContainer.addView(AudioRow.build(this, null, type, label))
        }
    }

    private fun maybeRequestRecordPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }
    }

    // ---------- تگ گزارش ----------

    private fun startWriteReportTag() {
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
            .setTitle("نوشتن تگ گزارش روزانه")
            .setMessage("تگ را به پشت گوشی بچسبان و تا پایان کار ثابت نگه دار…")
            .setNegativeButton("انصراف") { _, _ -> stopWriteMode() }
            .setOnCancelListener { stopWriteMode() }
            .show()

        // مسیر ۱: ReaderMode
        adapter.enableReaderMode(
            this,
            { tag -> handleWriteTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
        // مسیر ۲: Foreground Dispatch
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
        Thread {
            val ok = NfcUtil.writeTag(tag, NfcUtil.REPORT_ID)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    vibrate()
                    stopWriteMode()
                    AlertDialog.Builder(this)
                        .setTitle("\u2705 انجام شد")
                        .setMessage("تگ گزارش روزانه نوشته شد.\nآن را جایی مثل یخچال بچسبان.")
                        .setPositiveButton("باشه", null)
                        .show()
                } else {
                    writeDialog?.setMessage(
                        "\u274c نشد! احتمالا تگ زود برداشته شد.\n" +
                            "تگ را دوباره به پشت گوشی بچسبان و چند ثانیه ثابت نگه دار…"
                    )
                }
            }
        }.start()
    }

    private fun stopWriteMode() {
        writeMode = false
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

    // ---------- مجوزها ----------

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "قبلا فعال شده \u2705", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun requestExactAlarm() {
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(AlarmManager::class.java)
            if (am.canScheduleExactAlarms()) {
                Toast.makeText(this, "قبلا فعال شده \u2705", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
        } else {
            Toast.makeText(this, "در این نسخه اندروید نیازی نیست \u2705", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQ_IMPORT = 7
    }
}
