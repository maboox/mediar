package com.mediar.app.backup

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mediar.app.data.Db
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** پشتیبان‌گیری و بازیابی کامل (دیتابیس + صداهای ضبط‌شده) */
object Backup {

    fun exportZip(ctx: Context): File {
        val outDir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val out = File(outDir, "mediar-backup-" + LocalDate.now() + ".zip")
        Db.get(ctx).readableDatabase // اطمینان از باز بودن بدون WAL تا همه دیتا داخل فایل اصلی باشد
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            val db = ctx.getDatabasePath(Db.NAME)
            if (db.exists()) addFile(zip, db, Db.NAME)
            val audioDir = File(ctx.filesDir, "audio")
            audioDir.listFiles()?.forEach { f ->
                if (f.isFile) addFile(zip, f, "audio/" + f.name)
            }
        }
        return out
    }

    private fun addFile(zip: ZipOutputStream, file: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    fun share(ctx: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(ctx, "com.mediar.app.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, title))
    }

    /**
     * بازیابی از فایل ZIP پشتیبان. بعد از این کار باید اپ دوباره باز شود.
     * اول همه‌چیز در یک پوشه موقت باز و بررسی می‌شود؛ فقط اگر فایل سالم بود، جایگزین می‌شود.
     */
    fun importZip(ctx: Context, input: InputStream): Boolean {
        val tmp = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            var dbFile: File? = null
            val audio = mutableListOf<File>()
            ZipInputStream(input).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && !name.contains("..")) {
                        val target: File? = when {
                            name == Db.NAME -> File(tmp, Db.NAME).also { dbFile = it }
                            name.startsWith("audio/") && !name.removePrefix("audio/").contains('/') ->
                                File(File(tmp, "audio").apply { mkdirs() }, name.removePrefix("audio/")).also { audio.add(it) }
                            else -> null
                        }
                        if (target != null) FileOutputStream(target).use { zin.copyTo(it) }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            val db = dbFile ?: return false
            // بررسی امضای فایل SQLite
            val header = ByteArray(16)
            FileInputStream(db).use { if (it.read(header) != 16) return false }
            if (!String(header, Charsets.US_ASCII).startsWith("SQLite format 3")) return false

            Db.get(ctx).close()
            val target = ctx.getDatabasePath(Db.NAME)
            target.parentFile?.mkdirs()
            for (suffix in listOf("-wal", "-shm", "-journal")) File(target.path + suffix).delete()
            db.copyTo(target, overwrite = true)

            val audioDir = File(ctx.filesDir, "audio").apply { mkdirs() }
            audioDir.listFiles()?.forEach { it.delete() }
            for (f in audio) f.copyTo(File(audioDir, f.name), overwrite = true)
            return true
        } catch (e: Exception) {
            return false
        } finally {
            tmp.deleteRecursively()
        }
    }
}
