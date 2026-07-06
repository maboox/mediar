package com.mediar.app.nfc

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable

object NfcUtil {

    /** MIME اختصاصی اپ — باعث می‌شود اسکن تگ مستقیما همین اپ را باز کند */
    const val MIME = "application/vnd.mediar"

    /** شناسه تگ مخصوص «گزارش امروز» */
    const val REPORT_ID = "__REPORT__"

    /** خواندن شناسه از Intent مربوط به NDEF_DISCOVERED */
    fun readIdFromIntent(intent: Intent): String? {
        if (intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return null
        @Suppress("DEPRECATION")
        val raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return null
        for (m in raw) {
            val id = idFromMessage(m as NdefMessage)
            if (id != null) return id
        }
        return null
    }

    /** گرفتن خود تگ از هر Intent مربوط به NFC (برای حالت نوشتن) */
    fun tagFromIntent(intent: Intent): Tag? {
        val action = intent.action
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED
        ) return null
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
    }

    /** خواندن شناسه مستقیم از تگ (حالت ReaderMode) */
    fun readIdFromTag(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            val msg = ndef.ndefMessage
            if (msg == null) null else idFromMessage(msg)
        } catch (e: Exception) {
            null
        } finally {
            try { ndef.close() } catch (_: Exception) {}
        }
    }

    private fun idFromMessage(msg: NdefMessage): String? {
        for (r in msg.records) {
            if (r.tnf == NdefRecord.TNF_MIME_MEDIA &&
                String(r.type, Charsets.US_ASCII) == MIME
            ) {
                return String(r.payload, Charsets.UTF_8)
            }
        }
        return null
    }

    /**
     * نوشتن شناسه روی تگ (تگ خالی یا قبلا نوشته‌شده).
     * خروجی: null = موفق، در غیر این صورت متن علت خطا.
     * تا ۳ بار پشت‌سرهم تلاش می‌کند تا لرزش دست/تماس ناپایدار جبران شود.
     */
    fun writeTag(tag: Tag, id: String): String? {
        val record = NdefRecord.createMime(MIME, id.toByteArray(Charsets.UTF_8))
        val msg = NdefMessage(arrayOf(record))
        var lastError: String? = null

        repeat(3) { attempt ->
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                try {
                    ndef.connect()
                    if (!ndef.isWritable) {
                        return "این تگ قفل شده و دیگر قابل نوشتن نیست"
                    }
                    if (ndef.maxSize < msg.toByteArray().size) {
                        return "ظرفیت این تگ کم است"
                    }
                    ndef.writeNdefMessage(msg)
                    return null // موفق
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                } finally {
                    try { ndef.close() } catch (_: Exception) {}
                }
            } else {
                val formatable = NdefFormatable.get(tag)
                if (formatable != null) {
                    try {
                        formatable.connect()
                        formatable.format(msg)
                        return null // موفق
                    } catch (e: Exception) {
                        lastError = "فرمت تگ نشد: " + (e.message ?: e.javaClass.simpleName)
                    } finally {
                        try { formatable.close() } catch (_: Exception) {}
                    }
                } else {
                    val techs = tag.techList.joinToString("، ") { it.substringAfterLast('.') }
                    return "این نوع تگ قابل نوشتن NDEF نیست ($techs)"
                }
            }
            // کمی صبر و تلاش دوباره
            try { Thread.sleep(250) } catch (_: InterruptedException) {}
        }
        return lastError ?: "خطای نامشخص"
    }
}
