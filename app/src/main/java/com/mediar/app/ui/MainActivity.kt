package com.mediar.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mediar.app.R
import com.mediar.app.alarm.Alarms
import com.mediar.app.audio.Speaker
import com.mediar.app.data.Db
import com.mediar.app.logic.Fmt
import com.mediar.app.logic.ScanEngine
import com.mediar.app.logic.Schedule
import com.mediar.app.nfc.NfcUtil
import java.time.ZonedDateTime

/** صفحه اصلی — مخصوص سالمند: لیست بزرگ و ساده امروز */
class MainActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var nextText: TextView
    private lateinit var dateText: TextView
    private lateinit var nfcWarning: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listContainer = findViewById(R.id.listContainer)
        nextText = findViewById(R.id.nextText)
        dateText = findViewById(R.id.dateText)
        nfcWarning = findViewById(R.id.nfcWarning)

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, PinActivity::class.java))
        }
        nfcWarning.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
        }

        requestNotificationPermissionIfNeeded()
        Speaker.warmUp(this)
        Alarms.rescheduleAll(this)
        forwardNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forwardNfcIntent(intent)
    }

    /** اگر اپ با تگ قدیمی باز شد، کار را به پاپ‌آپ می‌سپارد */
    private fun forwardNfcIntent(intent: Intent?) {
        val id = intent?.let { NfcUtil.readIdFromIntent(it) } ?: return
        startActivity(PopupActivity.forScan(this, id))
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        val adapter = NfcAdapter.getDefaultAdapter(this)
        nfcWarning.visibility = if (adapter != null && !adapter.isEnabled) View.VISIBLE else View.GONE
        if (adapter == null) {
            nfcWarning.visibility = View.VISIBLE
            nfcWarning.text = "این گوشی NFC ندارد"
        }
        // وقتی اپ باز است، خودمان تگ را می‌خوانیم و پاپ‌آپ را باز می‌کنیم
        try {
            adapter?.enableReaderMode(
                this,
                { tag ->
                    val id = NfcUtil.readIdFromTag(tag)
                    if (id != null) runOnUiThread { startActivity(PopupActivity.forScan(this, id)) }
                },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
                null
            )
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        try { NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this) } catch (_: Exception) {}
    }

    private fun refresh() {
        val now = ZonedDateTime.now()
        dateText.text = Fmt.dayName(now.toLocalDate()) + " " + Fmt.jalali(now.toLocalDate()) +
            " — ساعت " + Fmt.time(now)

        listContainer.removeAllViews()
        val rows = ScanEngine.dosesBetween(this, now.toLocalDate(), now.toLocalDate())
            .sortedBy { it.dueAt }

        val meds = Db.get(this).activeMeds()
        if (meds.isEmpty()) {
            addRow("هنوز دارویی تعریف نشده.\nاز دکمه «مدیریت» شروع کن.", Color.parseColor("#ECEFF1"), null)
        } else if (rows.isEmpty()) {
            addRow("امروز نوبت هیچ قرصی نیست 🎉", Color.parseColor("#E8F5E9"), null)
        }

        for (r in rows) {
            val time = Fmt.time(r.dueAt)
            when {
                r.log != null -> {
                    val takenAt = java.time.Instant.ofEpochMilli(r.log.takenAt).atZone(now.zone)
                    val extra = if (r.log.status == "manual") "" else " (ساعت " + Fmt.time(takenAt) + ")"
                    addRow("✅ " + r.med.name + " — " + time + "\nخورده شد" + extra,
                        Color.parseColor("#C8E6C9"), r.med.id)
                }
                r.slot.contains(now) && !now.isBefore(r.dueAt) ->
                    addRow("👉 " + r.med.name + " — " + time + "\nالان وقتشه! بخور و گوشی رو به جعبه بزن",
                        Color.parseColor("#FFE082"), r.med.id)
                r.slot.contains(now) ->
                    addRow("👉 " + r.med.name + " — " + time + "\nمی‌تونی بخوری",
                        Color.parseColor("#FFF9C4"), r.med.id)
                !r.slot.end.isAfter(now) ->
                    addRow("❗ " + r.med.name + " — " + time + "\nجا موند",
                        Color.parseColor("#FFCDD2"), r.med.id)
                else ->
                    addRow("⏳ " + r.med.name + " — " + time, Color.parseColor("#ECEFF1"), r.med.id)
            }
        }

        val pendingToday = rows.filter { it.log == null && it.slot.end.isAfter(now) }.minByOrNull { it.dueAt }
        val next = pendingToday?.let { it.dueAt to it.med.name }
            ?: meds.mapNotNull { med -> Schedule(med.scheduleJson).nextDue(now)?.let { it to med.name } }
                .filter { it.first.toLocalDate() != now.toLocalDate() }
                .minByOrNull { it.first }
        nextText.text = when {
            meds.isEmpty() -> "برای شروع، دکمه «مدیریت» را بزن"
            next != null -> "نوبت بعدی: " + next.second + " — " + Fmt.whenText(next.first, now)
            else -> "نوبت بعدی ندارد"
        }
    }

    private fun addRow(text: String, bgColor: Int, medId: String?) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 22f
        tv.setTextColor(Color.parseColor("#212121"))
        tv.setPadding(32, 36, 32, 36)
        tv.setBackgroundColor(bgColor)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, 20)
        tv.layoutParams = lp
        if (medId != null) {
            // لمس ردیف = مثل زدن تگ همان دارو (اگر تگ گم یا خراب شد)
            tv.setOnClickListener { startActivity(PopupActivity.forMed(this, medId)) }
        }
        listContainer.addView(tv)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
