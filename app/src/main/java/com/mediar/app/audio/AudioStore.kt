package com.mediar.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * مدیریت پیام‌های صوتی ضبط‌شده.
 *
 * انواع پیام (type):
 *  - due        : «الان وقتشه این قرص رو بخوری»
 *  - logged     : «ثبت شد، نوش جان» (بعد از تأیید خوردن)
 *  - taken      : «این قرص رو خوردی، دیگه لازم نیست»
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
    const val TYPE_LOGGED = "logged"
    const val TYPE_TAKEN = "taken"
    const val TYPE_EARLY = "early"
    const val TYPE_NOT_TODAY = "not_today"
    const val TYPE_REPORT_DONE = "report_done"
    const val TYPE_REPORT_REMAINING = "report_remaining"

    private var recorder: MediaRecorder? = null
    private var recordingTarget: File? = null
    private var recordingTemp: File? = null
    private var recordingStartedAt = 0L

    /** کلید ردیفی که الان در حال ضبط است (medId + type) */
    var recordingKey: String? = null
        private set

    private var player: MediaPlayer? = null

    fun dir(ctx: Context): File = File(ctx.filesDir, "audio").apply { mkdirs() }

    fun file(ctx: Context, medId: String?, type: String): File {
        val name = if (medId == null) "generic_" + type + ".m4a" else medId + "_" + type + ".m4a"
        return File(dir(ctx), name)
    }

    fun key(medId: String?, type: String): String = (medId ?: "generic") + "_" + type

    fun has(ctx: Context, medId: String?, type: String): Boolean =
        file(ctx, medId, type).let { it.exists() && it.length() > 0 }

    /** آیا برای این دارو (یا به‌صورت عمومی) پیامی از این نوع وجود دارد؟ */
    fun hasAny(ctx: Context, medId: String?, type: String): Boolean =
        (medId != null && has(ctx, medId, type)) || has(ctx, null, type)

    fun isRecording(): Boolean = recorder != null

    fun startRecording(ctx: Context, medId: String?, type: String): Boolean {
        stopAll()
        val target = file(ctx, medId, type)
        val temp = File(dir(ctx), "rec_tmp.m4a")
        temp.delete()
        return try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(temp.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recordingTarget = target
            recordingTemp = temp
            recordingStartedAt = System.currentTimeMillis()
            recordingKey = key(medId, type)
            true
        } catch (e: Exception) {
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            recordingKey = null
            temp.delete()
            false
        }
    }

    /** پایان ضبط. خروجی true یعنی فایل سالم ذخیره شد. */
    fun stopRecording(): Boolean {
        val r = recorder ?: return false
        var ok = true
        try { r.stop() } catch (_: Exception) { ok = false }
        try { r.release() } catch (_: Exception) {}
        recorder = null
        recordingKey = null
        val temp = recordingTemp
        val target = recordingTarget
        recordingTemp = null
        recordingTarget = null
        if (System.currentTimeMillis() - recordingStartedAt < 700) ok = false
        if (temp == null || target == null) return false
        if (ok && temp.exists() && temp.length() > 0) {
            target.delete()
            ok = temp.renameTo(target)
        } else {
            ok = false
        }
        temp.delete()
        return ok
    }

    /**
     * پخش پیام. اول پیام اختصاصی دارو، بعد پیام عمومی.
     * خروجی false یعنی هیچ فایلی وجود نداشت یا پخش نشد.
     * با کانال صدای آلارم پخش می‌شود تا برای سالمند بلند و واضح باشد.
     */
    fun play(ctx: Context, medId: String?, type: String, onDone: (() -> Unit)? = null): Boolean {
        stopPlayback()
        val f = medId?.let { file(ctx, it, type) }?.takeIf { it.exists() && it.length() > 0 }
            ?: file(ctx, null, type).takeIf { it.exists() && it.length() > 0 }
            ?: return false
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener {
                try { it.release() } catch (_: Exception) {}
                if (player === it) player = null
                onDone?.invoke()
            }
            mp.prepare()
            mp.start()
            player = mp
            true
        } catch (e: Exception) {
            stopPlayback()
            false
        }
    }

    fun stopPlayback() {
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    fun stopAll() {
        stopRecording()
        stopPlayback()
    }

    fun delete(ctx: Context, medId: String?, type: String) {
        file(ctx, medId, type).delete()
    }

    /** حذف همه صداهای اختصاصی یک دارو */
    fun deleteAllFor(ctx: Context, medId: String) {
        dir(ctx).listFiles()?.forEach { if (it.name.startsWith(medId + "_")) it.delete() }
    }
}
