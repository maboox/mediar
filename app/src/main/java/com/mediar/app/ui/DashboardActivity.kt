package com.mediar.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mediar.app.R
import com.mediar.app.alarm.Alarms
import com.mediar.app.data.Db
import com.mediar.app.data.DoseLog
import com.mediar.app.logic.Fmt
import com.mediar.app.logic.ScanEngine
import com.mediar.app.logic.Schedule
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID

/** داشبورد ۳۰ روز گذشته: پایبندی، تأخیرها، موجودی، نوبت‌های جامانده */
class DashboardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        findViewById<Button>(R.id.btnShareReport).setOnClickListener { shareReport() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val summary = findViewById<LinearLayout>(R.id.summaryContainer)
        val missedC = findViewById<LinearLayout>(R.id.missedContainer)
        val logsC = findViewById<LinearLayout>(R.id.logsContainer)
        summary.removeAllViews()
        missedC.removeAllViews()
        logsC.removeAllViews()

        val now = ZonedDateTime.now()
        val from = now.toLocalDate().minusDays(29)
        val allRows = ScanEngine.dosesBetween(this, from, now.toLocalDate())
        val pastRows = allRows.filter { !it.dueAt.isAfter(now) }

        // ---------- خلاصه برای هر دارو ----------
        for (med in Db.get(this).activeMeds()) {
            val rows = pastRows.filter { it.med.id == med.id }
            val total = rows.size
            val taken = rows.count { it.log != null }
            val late = rows.filter { it.log?.status == "late" }
            val missed = total - taken
            val adherence = if (total > 0) (taken * 100 / total) else 100
            val avgDelay = if (late.isNotEmpty()) late.map { it.log!!.delayMin }.average().toLong() else 0L

            val sched = Schedule(med.scheduleJson)
            var dosesNext30 = 0
            var d = now.toLocalDate()
            for (i in 0 until 30) {
                dosesNext30 += sched.dueTimesForDate(d, now.zone).size
                d = d.plusDays(1)
            }
            val perDay = dosesNext30 * med.doseAmount / 30.0
            val daysLeft = if (perDay > 0) (med.stock / perDay).toInt() else -1

            val sb = StringBuilder()
            sb.append("\uD83D\uDC8A ").append(med.name).append("\n")
            sb.append("پایبندی ۳۰ روز: ").append(adherence).append("٪")
            sb.append(" (").append(taken).append(" از ").append(total).append(" نوبت)").append("\n")
            if (late.isNotEmpty()) sb.append("میانگین تأخیر: ").append(avgDelay).append(" دقیقه (").append(late.size).append(" نوبت با تأخیر)").append("\n")
            if (missed > 0) sb.append("جامانده: ").append(missed).append(" نوبت").append("\n")
            sb.append("موجودی: ").append(trim(med.stock))
            if (daysLeft in 0..365) sb.append(" — حدود ").append(daysLeft).append(" روز دیگه تموم می‌شه")
            if (med.stock <= med.lowThreshold) sb.append("  \u26A0\uFE0F کم!")

            addCard(summary, sb.toString(),
                if (adherence >= 90) "#E8F5E9" else if (adherence >= 70) "#FFF9C4" else "#FFCDD2")
        }
        if (Db.get(this).activeMeds().isEmpty()) {
            addCard(summary, "هنوز دارویی ثبت نشده.", "#F5F5F5")
        }

        // ---------- نوبت‌های جامانده (قابل ثبت دستی) ----------
        val missedRows = pastRows.filter { it.log == null }.take(20)
        if (missedRows.isEmpty()) {
            addCard(missedC, "نوبت جامانده‌ای نیست \uD83C\uDF89", "#E8F5E9")
        }
        for (r in missedRows) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(16, 8, 16, 8)
            val tv = TextView(this)
            tv.text = r.med.name + " — " + Fmt.whenText(r.dueAt)
            tv.textSize = 15f
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val btn = Button(this)
            btn.text = "ثبت دستی"
            btn.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("ثبت دستی")
                    .setMessage(r.med.name + " — " + Fmt.whenText(r.dueAt) + "\nاین نوبت خورده شده؟")
                    .setPositiveButton("بله، ثبت کن") { _, _ ->
                        val nowMs = System.currentTimeMillis()
                        val dueMs = r.dueAt.toInstant().toEpochMilli()
                        Db.get(this).addLog(
                            DoseLog(
                                UUID.randomUUID().toString(), r.med.id, dueMs, nowMs,
                                Duration.ofMillis(nowMs - dueMs).toMinutes(), "manual"
                            )
                        )
                        Db.get(this).addStock(r.med.id, -r.med.doseAmount)
                        Alarms.rescheduleAll(this)
                        refresh()
                    }
                    .setNegativeButton("انصراف", null)
                    .show()
            }
            row.addView(tv)
            row.addView(btn)
            missedC.addView(row)
        }

        // ---------- تاریخچه ثبت‌ها ----------
        val meds = Db.get(this).activeMeds().associateBy { it.id }
        val logs = Db.get(this).logsBetween(
            from.atStartOfDay(now.zone).toInstant().toEpochMilli(),
            System.currentTimeMillis() + 1
        ).take(100)
        if (logs.isEmpty()) addCard(logsC, "هنوز ثبتی وجود ندارد.", "#F5F5F5")
        for (l in logs) {
            val medName = meds[l.medId]?.name ?: "داروی حذف‌شده"
            val takenZ = java.time.Instant.ofEpochMilli(l.takenAt).atZone(now.zone)
            val statusFa = when (l.status) {
                "ontime" -> "سر وقت \u2705"
                "early" -> "کمی زودتر"
                "late" -> "با " + Fmt.delayText(l.delayMin)
                else -> "ثبت دستی"
            }
            val tv = TextView(this)
            tv.text = medName + " — " + Fmt.whenText(takenZ, now) + " — " + statusFa
            tv.textSize = 14f
            tv.setPadding(16, 12, 16, 12)
            logsC.addView(tv)
        }
    }

    private fun addCard(parent: LinearLayout, text: String, colorHex: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 15f
        tv.setPadding(28, 24, 28, 24)
        tv.setBackgroundColor(Color.parseColor(colorHex))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, 16)
        tv.layoutParams = lp
        parent.addView(tv)
    }

    private fun shareReport() {
        val now = ZonedDateTime.now()
        val from = now.toLocalDate().minusDays(29)
        val pastRows = ScanEngine.dosesBetween(this, from, now.toLocalDate())
            .filter { !it.dueAt.isAfter(now) }

        val sb = StringBuilder("گزارش مصرف دارو — ۳۰ روز گذشته\n\n")
        for (med in Db.get(this).activeMeds()) {
            val rows = pastRows.filter { it.med.id == med.id }
            if (rows.isEmpty()) continue
            val taken = rows.count { it.log != null }
            val late = rows.count { it.log?.status == "late" }
            sb.append("• ").append(med.name).append(": ")
                .append(taken).append(" از ").append(rows.size).append(" نوبت خورده شد")
            if (late > 0) sb.append(" (").append(late).append(" نوبت با تأخیر)")
            sb.append("\n")
        }
        sb.append("\nتولید شده با اپ مدیار")

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(send, "ارسال گزارش"))
    }

    private fun trim(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
