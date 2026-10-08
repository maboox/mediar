package com.mediar.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mediar.app.R
import com.mediar.app.audio.AudioStore
import com.mediar.app.audio.Speaker
import com.mediar.app.data.Db
import com.mediar.app.data.Med
import com.mediar.app.data.Prefs
import com.mediar.app.logic.Decision
import com.mediar.app.logic.Fmt
import com.mediar.app.logic.ScanEngine
import com.mediar.app.logic.ScanOutcome
import com.mediar.app.logic.Slot
import com.mediar.app.nfc.NfcUtil
import com.mediar.app.nfc.ScanGate
import java.time.ZonedDateTime

/**
 * پاپ‌آپ بزرگ و ساده‌ای که با نزدیک کردن گوشی به تگ، خودکار (بدون باز کردن اپ)
 * روی هر صفحه‌ای باز می‌شود، پیام را با صدا می‌گوید و منتظر اسکن دوم یا دکمه «خوردم» می‌ماند.
 * همین پاپ‌آپ موقع آلارم هم (روی صفحه قفل) نمایش داده می‌شود.
 */
class PopupActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { if (!isFinishing) finish() }

    private lateinit var card: LinearLayout
    private lateinit var emojiV: TextView
    private lateinit var titleV: TextView
    private lateinit var medV: TextView
    private lateinit var subV: TextView
    private lateinit var hintV: TextView
    private lateinit var btnPrimary: Button
    private lateinit var btnSecondary: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_popup)
        setFinishOnTouchOutside(false)
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        card = findViewById(R.id.popupCard)
        emojiV = findViewById(R.id.popupEmoji)
        titleV = findViewById(R.id.popupTitle)
        medV = findViewById(R.id.popupMed)
        subV = findViewById(R.id.popupSub)
        hintV = findViewById(R.id.popupHint)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnSecondary = findViewById(R.id.btnSecondary)

        Speaker.warmUp(this)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // تا وقتی پاپ‌آپ باز است، خودش تگ‌ها را می‌خواند (برای اسکن دوم «خوردم»)
        try {
            NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
                this,
                { tag ->
                    val id = NfcUtil.readIdFromTag(tag)
                    if (id != null) runOnUiThread { onTag(id) }
                },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
                null
            )
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        try { NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        Speaker.stop()
    }

    // ---------- ورودی‌ها ----------

    private fun handle(intent: Intent?) {
        if (intent == null) return
        when {
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                val id = NfcUtil.readIdFromIntent(intent)
                if (id == null) {
                    if (!hasContent()) finish()
                    return
                }
                onTag(id)
            }
            intent.action == ACTION_REMINDER -> {
                showOnLockScreen()
                val medId = intent.getStringExtra(EXTRA_MED_ID) ?: return finishIfEmpty()
                showReminder(medId)
            }
            intent.action == ACTION_OPEN_MED -> {
                val medId = intent.getStringExtra(EXTRA_MED_ID) ?: return finishIfEmpty()
                handleMed(medId)
            }
            intent.hasExtra(EXTRA_SCAN_ID) -> onTag(intent.getStringExtra(EXTRA_SCAN_ID)!!)
            else -> finishIfEmpty()
        }
    }

    private fun hasContent(): Boolean = titleV.text.isNotEmpty()

    private fun finishIfEmpty() {
        if (!hasContent()) finish()
    }

    /** یک تگ اسکن شد (از سیستم یا ReaderMode) */
    private fun onTag(id: String) {
        if (!ScanGate.shouldHandle(id)) return
        if (id == NfcUtil.REPORT_ID) showReport() else handleMed(id)
    }

    @Suppress("DEPRECATION")
    private fun showOnLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    // ---------- دارو ----------

    private fun handleMed(id: String, confirm: Boolean = false) {
        val med = Db.get(this).getMed(id)
        if (med == null || med.archived) {
            render(
                "❓", "تگ ناشناخته", "", "این تگ به هیچ دارویی وصل نیست.\nاز بخش مدیریت تگ را دوباره به دارو وصل کن.",
                BG_GREY, ACCENT_GREY
            )
            primary("باشه") { finish() }
            secondary(null)
            hintV.text = ""
            Speaker.say(this, null, "unknown", "این تگ شناخته نشد.", good = false)
            scheduleClose(30)
            return
        }
        show(ScanEngine.scan(this, med, confirm))
    }

    private fun show(o: ScanOutcome) {
        val med = o.med
        val now = ZonedDateTime.now()
        val dose = doseText(med)
        hintV.text = ""
        when (o) {
            is ScanOutcome.AskToTake -> {
                vibrate(150)
                val sub = StringBuilder()
                sub.append(dose).append(" بخور")
                sub.append("\nنوبت ساعت ").append(Fmt.time(o.slot.due))
                if (o.lateMin > 30) sub.append("  (").append(Fmt.delayText(o.lateMin)).append(")")
                render("💊", "الان وقتشه — بخور", med.name, sub.toString(), BG_GREEN, ACCENT_GREEN)
                hintV.text = "بعد از خوردن، دوباره گوشی رو به جعبه بزن\nیا دکمه «خوردم» رو بزن تا ثبت بشه"
                primary("✅  خوردم") { handleMed(med.id, confirm = true) }
                secondary("بعدا") { finish() }
                Speaker.say(
                    this, med.id, AudioStore.TYPE_DUE,
                    "الان وقت خوردن ${med.name} است. $dose بخور. بعد از خوردن، دوباره گوشی را به جعبه بزن."
                )
                scheduleClose(120)
            }
            is ScanOutcome.Logged -> {
                vibrate(400)
                val sub = StringBuilder()
                if (o.log.status == "late") sub.append("با ").append(Fmt.delayText(o.log.delayMin)).append(" ثبت شد")
                else sub.append("ساعت ").append(Fmt.time(now)).append(" ثبت شد")
                if (o.lowStock) sub.append("\n⚠️ این دارو داره تموم می‌شه (").append(Fmt.num(med.stock)).append(" عدد مونده)")
                val next = com.mediar.app.logic.Schedule(med.scheduleJson).nextDue(now)
                if (next != null) sub.append("\nنوبت بعدی: ").append(Fmt.whenText(next, now))
                val single = Prefs.get(this).singleTap
                if (single) {
                    render("✅", "الان وقتشه — بخور", med.name, "$dose بخور\n$sub", BG_GREEN, ACCENT_GREEN)
                    Speaker.say(this, med.id, AudioStore.TYPE_DUE, "الان وقت خوردن ${med.name} است. $dose بخور. ثبت شد.")
                } else {
                    render("✅", "ثبت شد — نوش جان!", med.name, sub.toString(), BG_GREEN, ACCENT_GREEN)
                    Speaker.say(this, med.id, AudioStore.TYPE_LOGGED, "آفرین. خوردن ${med.name} ثبت شد. نوش جان.")
                }
                primary("باشه") { finish() }
                val logId = o.log.id
                secondary(if (single) "↩ اشتباه شد، نمی‌خورم" else "↩ اشتباه شد، هنوز نخوردم") {
                    ScanEngine.undo(this, logId)
                    handleMed(med.id)
                }
                scheduleClose(45)
            }
            is ScanOutcome.AlreadyTaken -> {
                vibrate(700)
                val sub = StringBuilder("ساعت ").append(Fmt.time(o.takenAt)).append(" خوردی.\nدیگه لازم نیست بخوری.")
                if (o.next != null) sub.append("\n\nنوبت بعدی: ").append(Fmt.whenText(o.next, now))
                render("🛑", "این قرص رو خوردی!", med.name, sub.toString(), BG_ORANGE, ACCENT_ORANGE)
                primary("باشه") { finish() }
                secondary(null)
                Speaker.say(
                    this, med.id, AudioStore.TYPE_TAKEN,
                    "${med.name} را ${Fmt.spokenTime(o.takenAt)} خورده‌ای. دوباره نخور.", good = false
                )
                scheduleClose(40)
            }
            is ScanOutcome.TooSoon -> {
                vibrate(700)
                val sub = "ساعت " + Fmt.time(o.lastTakenAt) + " خوردی.\nالان زوده، صبر کن تا " +
                    Fmt.whenText(o.allowedAt, now)
                render("✋", "الان نخور!", med.name, sub, BG_ORANGE, ACCENT_ORANGE)
                primary("باشه") { finish() }
                secondary(null)
                Speaker.say(
                    this, med.id, AudioStore.TYPE_TAKEN,
                    "همین تازگی ${med.name} را خورده‌ای. الان نخور. ${Fmt.spokenWhen(o.allowedAt, now)} بخور.",
                    good = false
                )
                scheduleClose(40)
            }
            is ScanOutcome.TooEarly -> {
                val sub = StringBuilder("وقتش: ").append(Fmt.whenText(o.next.due, now))
                sub.append("\n(از ساعت ").append(Fmt.time(o.next.start)).append(" می‌تونی بخوری)")
                appendHistory(sub, o.lastTakenAt, o.missed)
                render("⏰", "هنوز زوده", med.name, sub.toString(), BG_YELLOW, ACCENT_YELLOW)
                primary("باشه") { finish() }
                secondary(null)
                Speaker.say(
                    this, med.id, AudioStore.TYPE_EARLY,
                    "هنوز زود است. ${med.name} را ${Fmt.spokenWhen(o.next.due, now)} بخور.", good = false
                )
                scheduleClose(40)
            }
            is ScanOutcome.DoneForToday -> {
                val sub = StringBuilder()
                if (o.lastTakenAt != null) sub.append("امروز ساعت ").append(Fmt.time(o.lastTakenAt)).append(" خوردی.\n")
                sub.append("امروز دیگه نوبتی نداره.")
                if (o.missed != null) sub.append("\n⚠️ نوبت ").append(Fmt.whenText(o.missed.due, now)).append(" جا موند")
                if (o.next != null) sub.append("\n\nنوبت بعدی: ").append(Fmt.whenText(o.next, now))
                val taken = o.lastTakenAt != null
                render(if (taken) "🛑" else "🌙", "امروز دیگه نخور", med.name, sub.toString(), BG_ORANGE, ACCENT_ORANGE)
                primary("باشه") { finish() }
                secondary(null)
                val spokenNext = if (o.next != null) " نوبت بعدی ${Fmt.spokenWhen(o.next, now)} است." else ""
                if (taken) {
                    Speaker.say(this, med.id, AudioStore.TYPE_TAKEN, "${med.name} را امروز خورده‌ای. دوباره نخور.$spokenNext", good = false)
                } else {
                    Speaker.say(this, med.id, AudioStore.TYPE_NOT_TODAY, "امروز دیگر نوبت ${med.name} نیست.$spokenNext", good = false)
                }
                scheduleClose(40)
            }
            is ScanOutcome.NotToday -> {
                val sub = if (o.next != null) "نوبت بعدی: " + Fmt.whenText(o.next, now) else ""
                render("📅", "امروز نوبت این قرص نیست", med.name, sub, BG_BLUE, ACCENT_BLUE)
                primary("باشه") { finish() }
                secondary(null)
                val spokenNext = if (o.next != null) " نوبت بعدی ${Fmt.spokenWhen(o.next, now)} است." else ""
                Speaker.say(this, med.id, AudioStore.TYPE_NOT_TODAY, "امروز نوبت ${med.name} نیست.$spokenNext", good = false)
                scheduleClose(40)
            }
        }
    }

    private fun appendHistory(sb: StringBuilder, lastTakenAt: ZonedDateTime?, missed: Slot?) {
        if (lastTakenAt != null) sb.append("\n\nامروز ساعت ").append(Fmt.time(lastTakenAt)).append(" خوردی ✅")
        if (missed != null) sb.append("\n⚠️ نوبت ").append(Fmt.whenText(missed.due)).append(" جا موند")
    }

    // ---------- آلارم ----------

    private fun showReminder(medId: String) {
        val med = Db.get(this).getMed(medId)
        if (med == null || med.archived) return finishIfEmpty()
        val d = ScanEngine.decide(this, med)
        if (d !is Decision.Due) return finishIfEmpty()
        vibrate(600)
        val dose = doseText(med)
        render(
            "⏰", "وقت قرصه!", med.name,
            dose + " بخور\nنوبت ساعت " + Fmt.time(d.slot.due),
            BG_GREEN, ACCENT_GREEN
        )
        hintV.text = "بعد از خوردن، گوشی رو به جعبه بزن\nیا دکمه «خوردم» رو بزن"
        primary("✅  خوردم") { handleMed(med.id, confirm = true) }
        secondary("بعدا") { finish() }
        // کمی صبر تا صدای نوتیفیکیشن تمام شود
        handler.postDelayed({
            if (!isFinishing) Speaker.say(
                this, med.id, AudioStore.TYPE_DUE,
                "وقت خوردن ${med.name} است. $dose بخور. بعد از خوردن، گوشی را به جعبه بزن."
            )
        }, 1_500)
        scheduleClose(180)
    }

    // ---------- گزارش ----------

    private fun showReport() {
        val now = ZonedDateTime.now()
        val rows = ScanEngine.dosesBetween(this, now.toLocalDate(), now.toLocalDate()).sortedBy { it.dueAt }
        val remaining = rows.filter { it.log == null && it.slot.end.isAfter(now) }
        val missed = rows.filter { it.log == null && !it.slot.end.isAfter(now) }

        val sb = StringBuilder()
        for (r in rows) {
            val mark = when {
                r.log != null -> "✅"
                !r.slot.end.isAfter(now) -> "❌"
                r.slot.contains(now) -> "👉"
                else -> "⬜"
            }
            sb.append(mark).append(" ").append(r.med.name).append(" — ").append(Fmt.time(r.dueAt)).append("\n")
        }
        if (rows.isEmpty()) sb.append("امروز نوبت هیچ قرصی نیست.")

        primary("باشه") { finish() }
        secondary(null)
        hintV.text = ""
        if (remaining.isEmpty()) {
            val title = if (missed.isEmpty()) "همه قرص‌های امروز خورده شده" else "قرص دیگه‌ای برای امروز نمونده"
            render("🎉", title, "", sb.toString().trim(), BG_GREEN, ACCENT_GREEN)
            Speaker.say(this, null, AudioStore.TYPE_REPORT_DONE, "آفرین. قرص دیگری برای امروز نمانده.")
        } else {
            val dueNow = remaining.filter { it.slot.contains(now) }
            render(
                "📋", "امروز " + remaining.size + " قرص مونده", "",
                sb.toString().trim(), BG_YELLOW, ACCENT_YELLOW
            )
            val spoken = if (dueNow.isNotEmpty())
                "الان وقت خوردن " + dueNow.joinToString(" و ") { it.med.name } + " است."
            else
                "امروز هنوز " + remaining.size + " قرص مانده. نوبت بعدی " + remaining.first().med.name +
                    " " + Fmt.spokenTime(remaining.first().dueAt) + " است."
            Speaker.say(this, null, AudioStore.TYPE_REPORT_REMAINING, spoken, good = false)
        }
        scheduleClose(60)
    }

    // ---------- نمایش ----------

    private fun doseText(med: Med): String {
        val n = Fmt.num(med.doseAmount)
        return when (med.doseAmount) {
            0.5 -> "نصف قرص"
            1.0 -> "یک عدد"
            2.0 -> "دو عدد"
            else -> "$n عدد"
        }
    }

    private fun render(emoji: String, title: String, medName: String, sub: String, bg: Int, accent: Int) {
        val d = GradientDrawable()
        d.cornerRadius = 28f * resources.displayMetrics.density
        d.setColor(bg)
        d.setStroke((4 * resources.displayMetrics.density).toInt(), accent)
        card.background = d
        emojiV.text = emoji
        titleV.text = title
        medV.text = medName
        medV.visibility = if (medName.isEmpty()) View.GONE else View.VISIBLE
        subV.text = sub
        btnPrimary.backgroundTintList = ColorStateList.valueOf(accent)
    }

    private fun primary(text: String, action: () -> Unit) {
        btnPrimary.text = text
        btnPrimary.setOnClickListener { action() }
    }

    private fun secondary(text: String?, action: (() -> Unit)? = null) {
        if (text == null) {
            btnSecondary.visibility = View.GONE
            return
        }
        btnSecondary.visibility = View.VISIBLE
        btnSecondary.text = text
        btnSecondary.setOnClickListener { action?.invoke() }
    }

    private fun scheduleClose(seconds: Int) {
        handler.removeCallbacks(autoClose)
        handler.postDelayed(autoClose, seconds * 1000L)
    }

    private fun vibrate(ms: Long) {
        try {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_REMINDER = "com.mediar.app.REMINDER"
        const val ACTION_OPEN_MED = "com.mediar.app.OPEN_MED"
        const val EXTRA_MED_ID = "med_id"
        const val EXTRA_SCAN_ID = "scan_id"

        private val BG_GREEN = Color.parseColor("#E8F5E9")
        private val ACCENT_GREEN = Color.parseColor("#2E7D32")
        private val BG_ORANGE = Color.parseColor("#FFF3E0")
        private val ACCENT_ORANGE = Color.parseColor("#E65100")
        private val BG_YELLOW = Color.parseColor("#FFFDE7")
        private val ACCENT_YELLOW = Color.parseColor("#F9A825")
        private val BG_BLUE = Color.parseColor("#E3F2FD")
        private val ACCENT_BLUE = Color.parseColor("#1565C0")
        private val BG_GREY = Color.parseColor("#F5F5F5")
        private val ACCENT_GREY = Color.parseColor("#616161")

        /** باز کردن پاپ‌آپ از داخل اپ برای یک تگ خوانده‌شده */
        fun forScan(ctx: Context, id: String): Intent =
            Intent(ctx, PopupActivity::class.java).putExtra(EXTRA_SCAN_ID, id)

        /** باز کردن پاپ‌آپ یک دارو (بدون تگ، مثلا با لمس ردیف در صفحه اصلی) */
        fun forMed(ctx: Context, medId: String): Intent =
            Intent(ctx, PopupActivity::class.java).setAction(ACTION_OPEN_MED).putExtra(EXTRA_MED_ID, medId)
    }
}
