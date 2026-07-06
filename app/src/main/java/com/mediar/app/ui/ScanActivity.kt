package com.mediar.app.ui

import android.graphics.Color
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mediar.app.R
import com.mediar.app.audio.AudioStore
import com.mediar.app.data.Db
import com.mediar.app.logic.Fmt
import com.mediar.app.logic.ScanEngine
import com.mediar.app.logic.ScanOutcome
import com.mediar.app.nfc.NfcUtil
import com.mediar.app.nfc.ScanGate
import java.time.ZonedDateTime

/** نتیجه اسکن تگ — صفحه بزرگ و ساده + پخش صدای ضبط‌شده */
class ScanActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        findViewById<View>(R.id.btnOk).setOnClickListener { finish() }
        handler.postDelayed({ if (!isFinishing) finish() }, 60_000)

        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null) {
            finish()
            return
        }
        handleId(id)
    }

    override fun onResume() {
        super.onResume()
        // تا وقتی این صفحه باز است، خودمان تگ را می‌خوانیم تا سیستم
        // با دیدن دوباره همان تگ، اپ را از نو باز نکند و صدا قطع نشود.
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this,
            { tag ->
                val id = NfcUtil.readIdFromTag(tag)
                if (id != null && ScanGate.shouldHandle(id)) {
                    runOnUiThread { if (!isFinishing) handleId(id) }
                }
            },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500) }
        )
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        AudioStore.stopAll()
    }

    private fun handleId(id: String) {
        if (id == NfcUtil.REPORT_ID) showReport() else handleMed(id)
    }

    private fun handleMed(id: String) {
        val med = Db.get(this).getMed(id)
        if (med == null || med.archived) {
            show("\u2753", "تگ ناشناخته", "این تگ به هیچ دارویی وصل نیست.", Color.parseColor("#ECEFF1"))
            return
        }

        when (val outcome = ScanEngine.evaluateAndLog(this, med)) {
            is ScanOutcome.Logged -> {
                vibrate()
                val sub = StringBuilder(med.name)
                if (outcome.status == "late") sub.append("\nبا ").append(Fmt.delayText(outcome.delayMin)).append(" ثبت شد")
                else sub.append("\nثبت شد — نوش جان!")
                if (outcome.lowStock) sub.append("\n\u26A0\uFE0F این دارو داره تموم می‌شه!")
                show("\u2705", "الان وقتشه — بخور", sub.toString(), Color.parseColor("#C8E6C9"))
                AudioStore.play(this, med.id, AudioStore.TYPE_DUE)
            }
            is ScanOutcome.AlreadyTaken -> {
                show(
                    "\uD83D\uDED1", "این قرص رو خوردی!",
                    med.name + "\nدیگه لازم نیست بخوری.\nنوبت بعدی: " + Fmt.whenText(outcome.next),
                    Color.parseColor("#FFE0B2")
                )
                AudioStore.play(this, med.id, AudioStore.TYPE_TAKEN)
            }
            is ScanOutcome.TooEarly -> {
                show(
                    "\u23F0", "هنوز زوده",
                    med.name + "\nوقتش: " + Fmt.whenText(outcome.dueAt),
                    Color.parseColor("#FFF9C4")
                )
                AudioStore.play(this, med.id, AudioStore.TYPE_EARLY)
            }
            is ScanOutcome.NotToday -> {
                show(
                    "\uD83D\uDCC5", "امروز نوبت این قرص نیست",
                    med.name + "\nنوبت بعدی: " + Fmt.whenText(outcome.next),
                    Color.parseColor("#B3E5FC")
                )
                AudioStore.play(this, med.id, AudioStore.TYPE_NOT_TODAY)
            }
        }
    }

    private fun showReport() {
        val now = ZonedDateTime.now()
        val rows = ScanEngine.dosesBetween(this, now.toLocalDate(), now.toLocalDate())
            .sortedBy { it.dueAt }
        val remaining = rows.filter { it.log == null }

        val sb = StringBuilder()
        for (r in rows) {
            sb.append(if (r.log != null) "\u2705 " else "\u2B1C ")
                .append(r.med.name).append(" — ساعت ").append(Fmt.time(r.dueAt)).append("\n")
        }
        if (rows.isEmpty()) sb.append("امروز نوبت هیچ قرصی نیست.")

        if (remaining.isEmpty()) {
            show("\uD83C\uDF89", "همه قرص‌های امروز خورده شده", sb.toString().trim(), Color.parseColor("#C8E6C9"))
            AudioStore.play(this, null, AudioStore.TYPE_REPORT_DONE)
        } else {
            show("\uD83D\uDCCB", "گزارش امروز — " + remaining.size + " قرص مانده", sb.toString().trim(), Color.parseColor("#FFF9C4"))
            AudioStore.play(this, null, AudioStore.TYPE_REPORT_REMAINING)
        }
    }

    private fun show(emoji: String, title: String, sub: String, bgColor: Int) {
        findViewById<LinearLayout>(R.id.scanRoot).setBackgroundColor(bgColor)
        findViewById<TextView>(R.id.scanEmoji).text = emoji
        findViewById<TextView>(R.id.scanTitle).text = title
        findViewById<TextView>(R.id.scanSub).text = sub
    }

    private fun vibrate() {
        try {
            @Suppress("DEPRECATION")
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    companion object {
        const val EXTRA_ID = "scan_id"
    }
}
