package com.mediar.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "mediar.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE meds(" +
                "id TEXT PRIMARY KEY," +
                "name TEXT NOT NULL," +
                "dose_amount REAL NOT NULL DEFAULT 1," +
                "stock REAL NOT NULL DEFAULT 0," +
                "low_threshold REAL NOT NULL DEFAULT 5," +
                "schedule_json TEXT NOT NULL," +
                "archived INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE dose_logs(" +
                "id TEXT PRIMARY KEY," +
                "med_id TEXT NOT NULL," +
                "due_at INTEGER," +
                "taken_at INTEGER NOT NULL," +
                "delay_min INTEGER NOT NULL DEFAULT 0," +
                "status TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_logs_med ON dose_logs(med_id, due_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // نسخه ۱ — فعلا ارتقایی لازم نیست
    }

    // ---------- داروها ----------

    fun upsertMed(m: Med) {
        val cv = ContentValues().apply {
            put("id", m.id)
            put("name", m.name)
            put("dose_amount", m.doseAmount)
            put("stock", m.stock)
            put("low_threshold", m.lowThreshold)
            put("schedule_json", m.scheduleJson)
            put("archived", if (m.archived) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("meds", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun archiveMed(id: String) {
        val cv = ContentValues().apply { put("archived", 1) }
        writableDatabase.update("meds", cv, "id=?", arrayOf(id))
    }

    fun getMed(id: String): Med? {
        readableDatabase.query("meds", null, "id=?", arrayOf(id), null, null, null).use { c ->
            return if (c.moveToFirst()) medFrom(c) else null
        }
    }

    fun activeMeds(): List<Med> {
        val out = mutableListOf<Med>()
        readableDatabase.query("meds", null, "archived=0", null, null, null, "name ASC").use { c ->
            while (c.moveToNext()) out.add(medFrom(c))
        }
        return out
    }

    fun addStock(medId: String, delta: Double) {
        writableDatabase.execSQL(
            "UPDATE meds SET stock = MAX(0, stock + ?) WHERE id = ?",
            arrayOf(delta.toString(), medId)
        )
    }

    fun setStock(medId: String, value: Double) {
        val cv = ContentValues().apply { put("stock", value) }
        writableDatabase.update("meds", cv, "id=?", arrayOf(medId))
    }

    private fun medFrom(c: Cursor): Med = Med(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        doseAmount = c.getDouble(c.getColumnIndexOrThrow("dose_amount")),
        stock = c.getDouble(c.getColumnIndexOrThrow("stock")),
        lowThreshold = c.getDouble(c.getColumnIndexOrThrow("low_threshold")),
        scheduleJson = c.getString(c.getColumnIndexOrThrow("schedule_json")),
        archived = c.getInt(c.getColumnIndexOrThrow("archived")) == 1
    )

    // ---------- ثبت‌ها ----------

    fun addLog(l: DoseLog) {
        val cv = ContentValues().apply {
            put("id", l.id)
            put("med_id", l.medId)
            if (l.dueAt != null) put("due_at", l.dueAt) else putNull("due_at")
            put("taken_at", l.takenAt)
            put("delay_min", l.delayMin)
            put("status", l.status)
        }
        writableDatabase.insertWithOnConflict("dose_logs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteLog(id: String) {
        writableDatabase.delete("dose_logs", "id=?", arrayOf(id))
    }

    fun hasLogFor(medId: String, dueAt: Long): Boolean {
        readableDatabase.query(
            "dose_logs", arrayOf("id"), "med_id=? AND due_at=?",
            arrayOf(medId, dueAt.toString()), null, null, null
        ).use { c -> return c.moveToFirst() }
    }

    fun logFor(medId: String, dueAt: Long): DoseLog? {
        readableDatabase.query(
            "dose_logs", null, "med_id=? AND due_at=?",
            arrayOf(medId, dueAt.toString()), null, null, null
        ).use { c -> return if (c.moveToFirst()) logFrom(c) else null }
    }

    fun logsBetween(fromMillis: Long, toMillis: Long): List<DoseLog> {
        val out = mutableListOf<DoseLog>()
        readableDatabase.query(
            "dose_logs", null, "taken_at>=? AND taken_at<?",
            arrayOf(fromMillis.toString(), toMillis.toString()), null, null, "taken_at DESC"
        ).use { c -> while (c.moveToNext()) out.add(logFrom(c)) }
        return out
    }

    private fun logFrom(c: Cursor): DoseLog = DoseLog(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        medId = c.getString(c.getColumnIndexOrThrow("med_id")),
        dueAt = if (c.isNull(c.getColumnIndexOrThrow("due_at"))) null
                else c.getLong(c.getColumnIndexOrThrow("due_at")),
        takenAt = c.getLong(c.getColumnIndexOrThrow("taken_at")),
        delayMin = c.getLong(c.getColumnIndexOrThrow("delay_min")),
        status = c.getString(c.getColumnIndexOrThrow("status"))
    )

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db =
            inst ?: synchronized(this) {
                inst ?: Db(ctx.applicationContext).also { inst = it }
            }
    }
}
