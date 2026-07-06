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
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            val db = ctx.getDatabasePath("mediar.db")
            if (db.exists()) addFile(zip, db, "mediar.db")
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

    /** بازیابی از فایل ZIP پشتیبان. بعد از این کار باید اپ دوباره باز شود */
    fun importZip(ctx: Context, input: InputStream): Boolean {
        return try {
            Db.get(ctx).close()
            ZipInputStream(input).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val target: File? = when {
                        name == "mediar.db" -> ctx.getDatabasePath("mediar.db")
                        name.startsWith("audio/") && !name.contains("..") ->
                            File(File(ctx.filesDir, "audio").apply { mkdirs() }, name.removePrefix("audio/"))
                        else -> null
                    }
                    if (target != null && !entry.isDirectory) {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { zin.copyTo(it) }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
