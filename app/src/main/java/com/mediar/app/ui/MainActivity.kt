package com.mediar.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mediar.app.R
import com.mediar.app.alarm.Alarms
import com.mediar.app.data.Db
import com.mediar.app.logic.Fmt
import com.mediar.app.logic.ScanEngine
import com.mediar.app.logic.Schedule
import com.mediar.app.nfc.NfcUtil
import java.time.ZonedDateTime

/** صفحه اصلی — مخصوص مادر: لیست بزرگ و ساده امروز */
class MainActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var nextText: TextView
    private lateinit var dateText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listContainer = findViewById(R.id.listContainer)
        nextText = findViewById(R.id.nextText)
        dateText = findViewById(R.id.dateText)

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, PinActivity::class.java))
        }

        requestNotificationPermissionIfNeeded()
        Alarms.rescheduleAll(this)
        handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent?) {
        val id = intent?.let { NfcUtil.readIdFromIntent(it) } ?: return
        openScan(id)
    }

    private fun openScan(id: String) {
        startActivity(Intent(this, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_ID, id))
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // وقتی اپ باز است، خودمان تگ را می‌خوانیم
        val adapter = NfcAdapter.getDefaultAdapter(this)
        adapter?.enableReaderMode(
            this,
            { tag ->
                val id = NfcUtil.readIdFromTag(tag)
                if (id != null) runOnUiThread { openScan(id) }
            },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    private fun refresh() {
        val now = ZonedDateTime.now()
        dateText.text = Fmt.dayName(now.toLocalDate()) + " — ساعت " + Fmt.time(now)

        listContainer.removeAllViews()
        val rows = ScanEngine.dosesBetween(this, now.toLocalDate(), now.toLocalDate())
            .sortedBy { it.dueAt }

        if (rows.isEmpty()) {
            addRow("امروز نوبت هیچ قرصی نیست \uD83C\uDF89", Color.parseColor("#E8F5E9"))
        }

        for (r in rows) {
            val time = Fmt.time(r.dueAt)
            when {
                r.log != null -> {
                    val extra = if (r.log.status == "late") " (" + Fmt.delayText(r.log.delayMin) + ")" else ""
                    addRow("\u2705 " + r.med.name + " — ساعت " + time + " خورده شد" + extra,
                        Color.parseColor("#C8E6C9"))
                }
                r.dueAt.isBefore(now) -> {
                    addRow("\u2757 " + r.med.name + " — ساعت " + time + " بود، هنوز نخوردی!",
                        Color.parseColor("#FFCDD2"))
                }
                else -> {
                    addRow("\u23F3 " + r.med.name + " — ساعت " + time,
                        Color.parseColor("#ECEFF1"))
                }
            }
        }

        val next = Db.get(this).activeMeds()
            .mapNotNull { med -> Schedule(med.scheduleJson).nextDue(now)?.let { it to med } }
            .minByOrNull { it.first }
        nextText.text = if (next != null)
            "نوبت بعدی: " + next.second.name + " — " + Fmt.whenText(next.first, now)
        else
            "هنوز دارویی تعریف نشده — از دکمه تنظیمات شروع کن"
    }

    private fun addRow(text: String, bgColor: Int) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 22f
        tv.setTextColor(Color.parseColor("#212121"))
        tv.setPadding(32, 40, 32, 40)
        tv.setBackgroundColor(bgColor)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, 24)
        tv.layoutParams = lp
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
