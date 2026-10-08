package com.mediar.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * گوینده پیام‌ها برای سالمند:
 *  ۱) اگر صدای ضبط‌شده (اختصاصی دارو یا عمومی) باشد، همان پخش می‌شود
 *  ۲) وگرنه متن با موتور «متن به گفتار» فارسی گوشی خوانده می‌شود
 *  ۳) اگر فارسی پشتیبانی نشود، یک بوق کوتاه پخش می‌شود
 */
object Speaker {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsPersian = false
    private var pendingText: String? = null
    private var pendingBeepOk = true
    private var tone: ToneGenerator? = null

    /** آماده‌سازی زودهنگام موتور گفتار (برای کاهش تأخیر اولین پیام) */
    fun warmUp(ctx: Context) {
        if (tts != null) return
        val app = ctx.applicationContext
        tts = TextToSpeech(app) { status ->
            main.post {
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    val t = tts!!
                    t.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    val r = try { t.setLanguage(Locale("fa", "IR")) } catch (_: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
                    ttsPersian = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                    if (!ttsPersian) {
                        val r2 = try { t.setLanguage(Locale("fa")) } catch (_: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
                        ttsPersian = r2 != TextToSpeech.LANG_MISSING_DATA && r2 != TextToSpeech.LANG_NOT_SUPPORTED
                    }
                    t.setSpeechRate(0.85f)
                }
                val p = pendingText
                pendingText = null
                if (p != null) speakText(p, pendingBeepOk)
            }
        }
    }

    /**
     * گفتن پیام.
     * @param good برای بوق جایگزین: true = بوق تأیید، false = بوق هشدار
     */
    fun say(ctx: Context, medId: String?, type: String, text: String, good: Boolean = true) {
        stop()
        if (AudioStore.play(ctx, medId, type)) return
        if (ttsReady) {
            speakText(text, good)
        } else {
            pendingText = text
            pendingBeepOk = good
            warmUp(ctx)
        }
    }

    private fun speakText(text: String, good: Boolean) {
        val t = tts
        if (t != null && ttsReady && ttsPersian) {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mediar")
        } else {
            beep(good)
        }
    }

    private fun beep(good: Boolean) {
        try {
            tone?.release()
            val g = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            tone = g
            g.startTone(if (good) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, 600)
            main.postDelayed({
                try { g.release() } catch (_: Exception) {}
                if (tone === g) tone = null
            }, 1_000)
        } catch (_: Exception) {}
    }

    fun stop() {
        pendingText = null
        AudioStore.stopPlayback()
        try { tts?.stop() } catch (_: Exception) {}
    }

    /** آیا گوشی گفتار فارسی دارد؟ (null = هنوز معلوم نیست) */
    fun persianAvailable(): Boolean? = if (!ttsReady) null else ttsPersian
}
