package com.mediar.app.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.nfc.NfcAdapter
import android.nfc.NdefMessage
import android.nfc.Tag
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediar.app.MainActivity
import com.mediar.app.audio.Speech
import com.mediar.app.logic.*
import com.mediar.app.nfc.NfcController
import com.mediar.app.reminder.Reminders
import com.mediar.app.store
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/** What the popup currently shows. */
data class TagCard(
    val tone: Color, val icon: ImageVector, val title: String, val name: String = "", val detail: String = "",
    val hint: String = "", val primary: Pair<String, () -> Unit>? = null, val secondary: Pair<String, () -> Unit>? = null
)

/**
 * Popup opened directly by Android when a Mediar tag touches the phone — the app does not
 * need to be open. It shows and speaks the medicine status. The first tap in a dose window
 * says "take it now"; after taking it, a second tap of the same tag (or the big button) records it.
 */
class TagActivity : ComponentActivity() {
    private val db by lazy { store() }
    private lateinit var speech: Speech
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val close = Runnable { if (!isFinishing) finish() }
    private var card by mutableStateOf<TagCard?>(null)
    private var en by mutableStateOf(false)
    private var lastPhysical = ""
    private var lastReadAt = 0L

    private fun t(fa: String, enText: String) = if (en) enText else fa

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        speech = Speech(this)
        setContent { TagScreen() }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // While the popup is open it reads tags itself, so the second tap lands here directly.
        runCatching {
            NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(this, { tag -> onTag(tag, null) },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, null)
        }
    }

    override fun onPause() {
        runCatching { NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this) }
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        speech.release()
        io.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun handle(intent: Intent?) {
        intent ?: return
        when {
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
                val msg = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.firstOrNull() as? NdefMessage
                if (tag != null) onTag(tag, NfcController.tokenFrom(msg)) else if (card == null) finish()
            }
            intent.hasExtra(EXTRA_TOKEN) -> io.execute {
                onRead(intent.getStringExtra(EXTRA_TOKEN)!!, intent.getStringExtra(EXTRA_PHYSICAL).orEmpty(), null)
            }
            else -> if (card == null) finish()
        }
    }

    /** A tag was seen (system dispatch or reader mode). Runs the I/O off the main thread. */
    private fun onTag(tag: Tag, known: String?) {
        io.execute {
            val token = known ?: NfcController.readToken(tag)
            if (token == null) {
                main.post { show(TagCard(Grey, Icons.Rounded.HelpOutline, t("تگ مدیار نیست", "Not a Mediar tag"), primary = t("بستن", "Close") to { finish() }), 20) }
                return@execute
            }
            onRead(token, NfcController.physical(tag), tag)
        }
    }

    /** Called on the I/O thread. */
    private fun onRead(token: String, physical: String, tag: Tag?) {
        val now = System.currentTimeMillis()
        // The same touch reported twice (system dispatch + reader mode on resume) is ignored.
        val elapsed = SystemClock.elapsedRealtime()
        if (physical == lastPhysical && elapsed - lastReadAt < 1_500) return
        lastPhysical = physical; lastReadAt = elapsed
        if (tag != null) watchRemoval(tag, physical)

        db.materialize()
        val s = db.snapshot()
        en = s.settings["language"] == "en"
        val binding = s.tags.firstOrNull { it.token == token && it.active }
        when {
            binding == null -> main.post {
                show(TagCard(Grey, Icons.Rounded.HelpOutline, t("تگ ناشناس", "Unknown tag"),
                    detail = t("این تگ به دارویی وصل نیست. مدیر باید آن را در «جعبه‌ها و تگ‌ها» وصل کند.", "This tag is not linked. Ask the manager to link it."),
                    primary = t("بستن", "Close") to { finish() }), 30)
                say(t("این تگ شناخته نشد.", "This tag is not recognized."), null, good = false)
            }
            binding.kind == "today" -> main.post { showToday(s) }
            binding.kind == "group" -> main.post {
                startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCAN_TOKEN, token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
            else -> medicine(s, token, physical, binding.target, now)
        }
    }

    private fun watchRemoval(tag: Tag, physical: String) {
        runCatching {
            NfcAdapter.getDefaultAdapter(this)?.ignore(tag, 500, { Arms.detached(this, physical) }, main)
        }
    }

    /** Called on the I/O thread. */
    private fun medicine(s: Snapshot, token: String, physical: String, id: String, now: Long) {
        val med = s.medicines.firstOrNull { it.id == id }
        if (med == null || med.archived) {
            main.post { show(TagCard(Grey, Icons.Rounded.Inventory2, t("دارو فعال نیست", "Medicine is not active"), med?.name.orEmpty(), primary = t("بستن", "Close") to { finish() }), 30) }
            return
        }
        val verdict = Tap.decide(s, med.id, now)
        if (verdict is TapVerdict.Due) {
            val arm = Arms.get(this)
            val single = s.settings["single_tap"] == "true"
            if (single || Tap.confirms(arm, token, physical, verdict.dose.id, now)) {
                record(med, verdict.dose, "NFC")
                return
            }
            // Keep an existing pending tap for the same dose so an extra quick tap does not restart the timer.
            val same = arm != null && arm.token == token && arm.physical == physical && arm.expected == verdict.dose.id && now - arm.at <= Tap.CONFIRM_WINDOW
            if (!same) Arms.set(this, TapArm(token, physical, verdict.dose.id, now))
        }
        main.post { render(med, verdict, now) }
    }

    /** Records the dose. Runs on the I/O thread. */
    private fun record(med: Medicine, dose: Expected, method: String) {
        val request = UUID.randomUUID().toString()
        val ok = runCatching { db.confirm(listOf(med.id to dose.id), null, System.currentTimeMillis(), method, request) }.getOrDefault(false)
        Arms.clear(this)
        runCatching { Reminders.rebuild(this) }
        val s = db.snapshot()
        val event = s.events.firstOrNull { it.request == request }
        main.post {
            if (!ok || event == null) {
                render(med, Tap.decide(s, med.id, System.currentTimeMillis()), System.currentTimeMillis())
                return@post
            }
            vibrate(400)
            val stock = s.medicines.firstOrNull { it.id == med.id }?.stock
            val low = stock != null && stock <= med.low
            val next = s.expected.filter { it.medicine == med.id && !it.obsolete && it.due > dose.due }.minByOrNull { it.due }
            show(TagCard(Green, Icons.Rounded.CheckCircle, t("ثبت شد — نوش جان!", "Recorded — well done!"), med.name,
                detail = listOfNotNull(
                    t("زمان مصرف: ", "Taken at ") + clock(event.takenAt, en),
                    if (low) t("⚠️ موجودی کم است: ", "⚠️ Low stock: ") + qty(stock!!, en) + " " + med.unit else null,
                    next?.let { t("نوبت بعدی: ", "Next dose: ") + whenText(it.due) }
                ).joinToString("\n"),
                primary = t("باشه", "OK") to { finish() },
                secondary = t("اشتباه شد، هنوز نخوردم", "Mistake, not taken yet") to { undo(med, event.id) }), 45)
            say(t("آفرین. مصرف ${med.name} ثبت شد.", "Well done. ${med.name} is recorded."), clip("thanks"))
        }
    }

    private fun undo(med: Medicine, eventId: String) {
        io.execute {
            runCatching { db.correct(eventId, "Undone from tag popup / لغو از پاپ‌آپ تگ") }
            runCatching { Reminders.rebuild(this) }
            Arms.clear(this)
            val s = db.snapshot()
            main.post { render(med, Tap.decide(s, med.id, System.currentTimeMillis()), System.currentTimeMillis()) }
        }
    }

    private fun render(med: Medicine, v: TapVerdict, now: Long) {
        val close = t("بستن", "Close") to { finish() }
        when (v) {
            is TapVerdict.Due -> {
                vibrate(150)
                val late = now - v.dose.due
                val detail = qty(v.dose.amount, en) + " " + v.dose.unit + "\n" + t("نوبت ساعت ", "Dose at ") + clock(v.dose.due, en) +
                    if (late > 30 * 60_000) "  (" + t("${duration(late, en)} دیر", "${duration(late, en)} late") + ")" else ""
                show(TagCard(Green, Icons.Rounded.Medication, t("الان وقتشه — بخور", "Time to take it"), med.name, detail,
                    hint = t("بعد از خوردن، دوباره گوشی را به همین جعبه بزن\nیا دکمهٔ «خوردم» را بزن", "After taking it, tap the box again\nor press ‘I took it’"),
                    primary = t("✅  خوردم", "✅  I took it") to { io.execute { record(med, v.dose, "manual") } },
                    secondary = t("بعداً", "Later") to { finish() }), 180)
                say(t("الان وقت خوردن ${med.name} است. ${spokenQty(v.dose.amount)} ${v.dose.unit} بخور. بعد از خوردن، دوباره گوشی را به جعبه بزن.",
                    "It is time to take ${med.name}. Take ${qty(v.dose.amount, true)} ${v.dose.unit}. After taking it, tap the box again."), clip("due"))
            }
            is TapVerdict.Taken -> {
                vibrate(700)
                show(TagCard(Orange, Icons.Rounded.Block, t("این نوبت را خوردی!", "Already taken!"), med.name,
                    t("ساعت ", "Taken at ") + clock(v.event.takenAt, en) + t(" خوردی. دوباره نخور.", ". Do not take it again.") +
                        (v.next?.let { "\n\n" + t("نوبت بعدی: ", "Next dose: ") + whenText(it.due) } ?: ""),
                    primary = close), 40)
                say(t("${med.name} را ساعت ${clock(v.event.takenAt, false)} خورده‌ای. دوباره نخور.", "You already took ${med.name}. Do not take it again."), clip("already"), good = false)
            }
            is TapVerdict.TooSoon -> {
                vibrate(700)
                show(TagCard(Orange, Icons.Rounded.PanTool, t("الان نخور!", "Not now!"), med.name,
                    t("ساعت ", "You took it at ") + clock(v.last.takenAt, en) + t(" خوردی.\nصبر کن تا ", ".\nWait until ") + whenText(v.allowedAt),
                    primary = close), 40)
                say(t("همین تازگی ${med.name} را خورده‌ای. الان نخور. ${whenText(v.allowedAt, spoken = true)} بخور.", "You took ${med.name} recently. Not now."), clip("already"), good = false)
            }
            is TapVerdict.Declined -> {
                show(TagCard(Orange, Icons.Rounded.RemoveCircleOutline, t("این نوبت «مصرف‌نشده» ثبت شده", "Recorded as not taken"), med.name,
                    v.next?.let { t("نوبت بعدی: ", "Next dose: ") + whenText(it.due) }.orEmpty(), primary = close), 40)
                say(t("این نوبت ${med.name} به عنوان مصرف‌نشده ثبت شده است.", "This dose of ${med.name} is recorded as not taken."), null, good = false)
            }
            is TapVerdict.TooEarly -> {
                val lines = mutableListOf(t("وقتش: ", "Due: ") + whenText(v.next.dose.due), t("(از ساعت ", "(from ") + clock(v.next.start, en) + t(" می‌توانی بخوری)", ")"))
                v.lastToday?.let { lines += t("امروز ساعت ", "Today taken at ") + clock(it.takenAt, en) + t(" خوردی ✅", " ✅") }
                v.missed?.let { lines += t("⚠️ نوبت ", "⚠️ Missed: ") + whenText(it.due) + t(" جا ماند", "") }
                show(TagCard(Yellow, Icons.Rounded.Schedule, t("هنوز زوده", "Too early"), med.name, lines.joinToString("\n"), primary = close), 40)
                say(t("هنوز زود است. ${med.name} را ${whenText(v.next.dose.due, spoken = true)} بخور.", "Too early. Take ${med.name} at ${clock(v.next.dose.due, true)}."), clip("early"), good = false)
            }
            is TapVerdict.Done -> {
                val lines = mutableListOf<String>()
                v.lastToday?.let { lines += t("امروز ساعت ", "Today taken at ") + clock(it.takenAt, en) + t(" خوردی.", ".") }
                lines += t("امروز دیگر نوبتی ندارد.", "No more doses today.")
                v.missed?.let { lines += t("⚠️ نوبت ", "⚠️ Missed: ") + whenText(it.due) + t(" جا ماند", "") }
                v.next?.let { lines += "\n" + t("نوبت بعدی: ", "Next dose: ") + whenText(it.due) }
                show(TagCard(Orange, Icons.Rounded.Bedtime, t("امروز دیگر نخور", "Done for today"), med.name, lines.joinToString("\n"), primary = close), 40)
                val next = v.next?.let { t(" نوبت بعدی ${whenText(it.due, spoken = true)} است.", "") }.orEmpty()
                say(if (v.lastToday != null) t("${med.name} را امروز خورده‌ای. دوباره نخور.$next", "You already took ${med.name} today.")
                    else t("امروز دیگر نوبت ${med.name} نیست.$next", "No more ${med.name} today."), clip(if (v.lastToday != null) "already" else "early"), good = false)
            }
            is TapVerdict.NotToday -> {
                show(TagCard(Blue, Icons.Rounded.EventBusy, t("امروز نوبت این دارو نیست", "Not scheduled today"), med.name,
                    v.next?.let { t("نوبت بعدی: ", "Next dose: ") + whenText(it.due) }.orEmpty(), primary = close), 40)
                val next = v.next?.let { t(" نوبت بعدی ${whenText(it.due, spoken = true)} است.", "") }.orEmpty()
                say(t("امروز نوبت ${med.name} نیست.$next", "${med.name} is not scheduled today."), clip("early"), good = false)
            }
        }
    }

    private fun showToday(s: Snapshot) {
        val now = System.currentTimeMillis()
        val list = s.today()
        val lines = list.joinToString("\n") { e ->
            val mark = when (s.status(e.id)) { "taken" -> "✅"; "declined" -> "➖"; else -> if (e.due <= now) "❗" else "⏳" }
            "$mark ${e.name} — ${clock(e.due, en)}"
        }.ifEmpty { t("امروز نوبتی تعریف نشده.", "No doses planned today.") }
        val left = list.filter { s.status(it.id) == "unknown" }
        show(TagCard(if (left.isEmpty()) Green else Yellow, Icons.Rounded.Today,
            if (left.isEmpty()) t("همهٔ نوبت‌های امروز ثبت شده", "All of today's doses are recorded") else t("امروز ${digits(left.size.toString(), en)} نوبت ثبت‌نشده مانده", "${left.size} doses still unrecorded today"),
            detail = lines, primary = t("بستن", "Close") to { finish() },
            secondary = t("باز کردن برنامه", "Open app") to { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish() }), 60)
        say(if (left.isEmpty()) t("آفرین. همهٔ نوبت‌های امروز ثبت شده است.", "Well done. All doses are recorded.")
            else t("امروز هنوز ${left.size} نوبت ثبت نشده است.", "${left.size} doses are still unrecorded today."), if (left.isEmpty()) clip("thanks") else null, good = left.isEmpty())
    }

    private fun show(c: TagCard, seconds: Int) {
        card = c
        main.removeCallbacks(close)
        main.postDelayed(close, seconds * 1000L)
    }

    private fun clip(type: String) = type + "_" + if (en) "en" else "fa"

    private fun spokenQty(n: Double) = when (n) { 0.5 -> "نصف"; 1.0 -> "یک"; 2.0 -> "دو"; 3.0 -> "سه"; else -> qty(n, true) }

    /** "today 08:00", "tomorrow 08:00", or a date. Spoken form avoids Persian digits for TTS. */
    private fun whenText(ms: Long, spoken: Boolean = false): String {
        val d = Tap.today(ms)
        val today = Tap.today(System.currentTimeMillis())
        val time = clock(ms, en || spoken)
        return when (d) {
            today -> t("امروز ساعت $time", "today $time")
            today.plusDays(1) -> t("فردا ساعت $time", "tomorrow $time")
            else -> datetime(ms, en || spoken)
        }
    }

    /** Speaks via recorded clip / offline TTS; falls back to a short tone when neither is available. */
    private fun say(text: String, clip: String?, good: Boolean = true) {
        speech.speak(text, clip, onUnavailable = { beep(good) })
    }

    private fun beep(good: Boolean) {
        if (db.get("mute", "false") == "true") return
        runCatching {
            val g = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            g.startTone(if (good) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, 700)
            main.postDelayed({ g.release() }, 1_200)
        }
    }

    private fun vibrate(ms: Long) {
        runCatching {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    @Composable private fun TagScreen() {
        val c = card
        MediarTheme(en, dark = false) {
            CompositionLocalProvider(LocalLayoutDirection provides if (en) LayoutDirection.Ltr else LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)).padding(16.dp), contentAlignment = Alignment.Center) {
                    if (c == null) CircularProgressIndicator(color = Color.White)
                    else Surface(Modifier.fillMaxWidth().widthIn(max = 560.dp), shape = RoundedCornerShape(30.dp), color = Color.White, tonalElevation = 0.dp) {
                        Column(Modifier.background(c.tone.copy(alpha = .14f)).verticalScroll(rememberScrollState()).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Icon(c.icon, null, Modifier.size(76.dp), tint = c.tone)
                            Text(c.title, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, lineHeight = 42.sp), textAlign = TextAlign.Center, color = Color(0xFF1F1A2E))
                            if (c.name.isNotEmpty()) Text(c.name, style = MaterialTheme.typography.headlineMedium, color = Navy, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                            if (c.detail.isNotEmpty()) Text(c.detail, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 21.sp, lineHeight = 34.sp), textAlign = TextAlign.Center, color = Color(0xFF2D2540))
                            c.primary?.let { (label, action) ->
                                Button(onClick = action, Modifier.fillMaxWidth().heightIn(min = 72.dp), shape = RoundedCornerShape(20.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = c.tone, contentColor = Color.White)) {
                                    Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            c.secondary?.let { (label, action) ->
                                TextButton(onClick = action, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(label, fontSize = 19.sp, color = Color(0xFF424242)) }
                            }
                            if (c.hint.isNotEmpty()) Text(c.hint, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = Color(0xFF5F5670))
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_TOKEN = "tag_token"
        const val EXTRA_PHYSICAL = "tag_physical"
        private val Green = Color(0xFF2E7D32)
        private val Orange = Color(0xFFE65100)
        private val Yellow = Color(0xFFB7791F)
        private val Blue = Color(0xFF1565C0)
        private val Grey = Color(0xFF616161)

        fun intent(c: Context, token: String, physical: String): Intent =
            Intent(c, TagActivity::class.java).putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_PHYSICAL, physical)
    }
}

/** The pending first tap survives the popup closing (and process death) so a later second tap still confirms. */
object Arms {
    private fun prefs(c: Context) = c.getSharedPreferences("mediar_tap", Context.MODE_PRIVATE)
    @Synchronized fun get(c: Context): TapArm? = runCatching {
        val o = JSONObject(prefs(c).getString("arm", null) ?: return null)
        TapArm(o.getString("token"), o.getString("physical"), o.getString("expected"), o.getLong("at"), o.optBoolean("detached"))
    }.getOrNull()
    @Synchronized fun set(c: Context, a: TapArm) {
        prefs(c).edit().putString("arm", JSONObject().put("token", a.token).put("physical", a.physical).put("expected", a.expected)
            .put("at", a.at).put("detached", a.detached).toString()).apply()
    }
    @Synchronized fun detached(c: Context, physical: String) { get(c)?.takeIf { it.physical == physical }?.let { set(c, it.copy(detached = true)) } }
    @Synchronized fun clear(c: Context) { prefs(c).edit().remove("arm").apply() }
}
