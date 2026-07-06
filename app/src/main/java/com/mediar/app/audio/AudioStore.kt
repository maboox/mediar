package com.mediar.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.File

/**
 * مدیریت پیام‌های صوتی ضبط‌شده.
 *
 * انواع پیام (type):
 *  - due        : «الان وقتشه این قرص رو بخوری»
 *  - taken      : «این قرص رو امروز خوردی، دیگه لازم نیست»
 *  - early      : «هنوز زوده، بعدا بخور»
 *  - not_today  : «امروز نوبت این قرص نیست»
 *  - report_done      : «آفرین، همه قرص‌های امروز رو خوردی» (عمومی)
 *  - report_remaining : «هنوز قرص مونده که باید بخوری» (عمومی)
 *
 * فایل‌ها: files/audio/{medId}_{type}.m4a یا generic_{type}.m4a
 * اگر پیام اختصاصی دارو موجود نباشد، پیام عمومی پخش می‌شود.
 */
object AudioStore {

    const val TYPE_DUE = "due"
    const val TYPE_TAKEN = "taken"
    const val TYPE_EARLY = "early"
    const val TYPE_NOT_TODAY = "not_today"
    const val TYPE_REPORT_DONE = "report_done"
    const val TYPE_REPORT_REMAINING = "report_remaining"

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null

    fun dir(ctx: Context): File = File(ctx.filesDir, "audio").apply { mkdirs() }

    fun file(ctx: Context, medId: String?, type: String): File {
        val name = if (medId == null) "generic_" + type + ".m4a" else medId + "_" + type + ".m4a"
        return File(dir(ctx), name)
    }

    fun has(ctx: Context, medId: String?, type: String): Boolean = file(ctx, medId, type).exists()

    /** آیا برای این دارو (یا به‌صورت عمومی) پیامی از این نوع وجود دارد؟ */
    fun hasAny(ctx: Context, medId: String?, type: String): Boolean =
        (medId != null && has(ctx, medId, type)) || has(ctx, null, type)

    fun isRecording(): Boolean = recorder != null

    fun startRecording(ctx: Context, medId: String?, type: String): Boolean {
        stopAll()
        return try {
            val f = file(ctx, medId, type)
            @Suppress("DEPRECATION")
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(f.absolutePath)
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            false
        }
    }

    fun stopRecording() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
    }

    /**
     * پخش پیام (داخل اپ). اول پیام اختصاصی دارو، بعد پیام عمومی.
     * خروجی false یعنی هیچ فایلی وجود نداشت.
     */
    fun play(ctx: Context, medId: String?, type: String, onDone: (() -> Unit)? = null): Boolean =
        playInternal(ctx, medId, type, asAlarm = false, onDone = onDone)

    /**
     * پخش با بلندگوی آلارم (برای زمان یادآوری) — حتی اگر گوشی سایلنت باشد،
     * روی کانال صدای آلارم پخش می‌شود.
     */
    fun playAlarm(ctx: Context, medId: String?, type: String, onDone: (() -> Unit)? = null): Boolean =
        playInternal(ctx, medId, type, asAlarm = true, onDone = onDone)

    private fun playInternal(
        ctx: Context,
        medId: String?,
        type: String,
        asAlarm: Boolean,
        onDone: (() -> Unit)?,
    ): Boolean {
        stopAll()
        val f = medId?.let { file(ctx, it, type) }?.takeIf { it.exists() }
            ?: file(ctx, null, type).takeIf { it.exists() }
            ?: return false
        return try {
            player = MediaPlayer().apply {
                if (asAlarm) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }
                setDataSource(f.absolutePath)
                setOnCompletionListener { mp ->
                    try { mp.release() } catch (_: Exception) {}
                    if (player === mp) player = null
                    onDone?.invoke()
                }
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            try { player?.release() } catch (_: Exception) {}
            player = null
            false
        }
    }

    fun stopAll() {
        stopRecording()
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    fun delete(ctx: Context, medId: String?, type: String) {
        file(ctx, medId, type).delete()
    }
}
