package com.mediar.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.mediar.app.logic.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.util.UUID

/** All writes use transactions; uniqueness is also enforced by SQLite. No destructive migrations. */
class Store(context: Context, databaseName: String = "mediar.db") : SQLiteOpenHelper(context.applicationContext, databaseName, null, 3) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE medicines(id TEXT PRIMARY KEY,name TEXT NOT NULL,note TEXT NOT NULL,unit TEXT NOT NULL,low REAL NOT NULL,archived INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE plans(id TEXT PRIMARY KEY,medicine_id TEXT NOT NULL REFERENCES medicines(id),start TEXT NOT NULL,end TEXT,mode TEXT NOT NULL,weekdays TEXT NOT NULL,every_days INTEGER NOT NULL,on_days INTEGER NOT NULL,off_days INTEGER NOT NULL,paused INTEGER NOT NULL,slots TEXT NOT NULL,created_at INTEGER NOT NULL,effective_from TEXT NOT NULL DEFAULT '',effective_to TEXT)")
        db.execSQL("CREATE TABLE expected(id TEXT PRIMARY KEY,plan_id TEXT NOT NULL REFERENCES plans(id),medicine_id TEXT NOT NULL REFERENCES medicines(id),slot_id TEXT NOT NULL,group_id TEXT NOT NULL,due INTEGER NOT NULL,quantity REAL NOT NULL,local_date TEXT NOT NULL DEFAULT '',zone TEXT NOT NULL DEFAULT '',med_name TEXT NOT NULL DEFAULT '',unit TEXT NOT NULL DEFAULT '',obsolete INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE doses(id TEXT PRIMARY KEY,medicine_id TEXT NOT NULL REFERENCES medicines(id),expected_id TEXT REFERENCES expected(id),taken_at INTEGER NOT NULL,recorded_at INTEGER NOT NULL,quantity REAL NOT NULL,method TEXT NOT NULL,category TEXT NOT NULL,corrected INTEGER NOT NULL DEFAULT 0,request_key TEXT NOT NULL DEFAULT '',med_name TEXT NOT NULL DEFAULT '',unit TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE UNIQUE INDEX one_valid_dose_per_slot ON doses(expected_id) WHERE expected_id IS NOT NULL AND corrected=0")
        db.execSQL("CREATE TABLE corrections(id TEXT PRIMARY KEY,dose_id TEXT NOT NULL REFERENCES doses(id),at INTEGER NOT NULL,reason TEXT NOT NULL)")
        db.execSQL("CREATE TABLE inventory(id TEXT PRIMARY KEY,medicine_id TEXT NOT NULL REFERENCES medicines(id),delta REAL NOT NULL,at INTEGER NOT NULL,note TEXT NOT NULL)")
        db.execSQL("CREATE TABLE med_groups(id TEXT PRIMARY KEY,title TEXT NOT NULL)")
        db.execSQL("CREATE TABLE tags(token TEXT PRIMARY KEY,kind TEXT NOT NULL,target TEXT NOT NULL,active INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        extra(db)
    }
    private fun extra(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS expected_due_idx ON expected(due)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS request_once ON doses(request_key) WHERE request_key<>''")
        db.execSQL("CREATE TABLE IF NOT EXISTS decisions(expected_id TEXT PRIMARY KEY REFERENCES expected(id),status TEXT NOT NULL,at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS reminder_state(expected_id TEXT PRIMARY KEY REFERENCES expected(id),next_at INTEGER NOT NULL,attempt INTEGER NOT NULL,ended INTEGER NOT NULL,snoozes INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 3) {
            db.execSQL("ALTER TABLE plans ADD COLUMN effective_from TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE plans ADD COLUMN effective_to TEXT")
            listOf("local_date", "zone", "med_name", "unit").forEach { db.execSQL("ALTER TABLE expected ADD COLUMN $it TEXT NOT NULL DEFAULT ''") }
            db.execSQL("ALTER TABLE expected ADD COLUMN obsolete INTEGER NOT NULL DEFAULT 0")
            listOf("request_key", "med_name", "unit").forEach { db.execSQL("ALTER TABLE doses ADD COLUMN $it TEXT NOT NULL DEFAULT ''") }
            extra(db)
            fillLegacy(db)
            db.execSQL("INSERT OR IGNORE INTO decisions(expected_id,status,at) SELECT substr(key,8),'declined',0 FROM settings WHERE key LIKE 'missed:%' AND substr(key,8) IN (SELECT id FROM expected)")
        }
    }
    private fun fillLegacy(db: SQLiteDatabase) {
        db.execSQL("UPDATE plans SET effective_from=start WHERE effective_from=''")
        db.execSQL("UPDATE expected SET med_name=COALESCE((SELECT name FROM medicines WHERE id=medicine_id),''),unit=COALESCE((SELECT unit FROM medicines WHERE id=medicine_id),'') WHERE med_name=''")
        db.execSQL("UPDATE doses SET med_name=COALESCE((SELECT name FROM medicines WHERE id=medicine_id),''),unit=COALESCE((SELECT unit FROM medicines WHERE id=medicine_id),'') WHERE med_name=''")
        db.rawQuery("SELECT id,due FROM expected WHERE local_date=''", null).use { c ->
            val zone = ZoneId.systemDefault()
            while (c.moveToNext()) db.execSQL("UPDATE expected SET local_date=?,zone=? WHERE id=?", arrayOf(Instant.ofEpochMilli(c.getLong(1)).atZone(zone).toLocalDate().toString(), zone.id, c.getString(0)))
        }
    }
    private fun SQLiteDatabase.put(table: String, values: Map<String, Any?>) {
        val cv = ContentValues()
        values.forEach { (k, v) -> when(v) { null -> cv.putNull(k); is String -> cv.put(k,v); is Int -> cv.put(k,v); is Long -> cv.put(k,v); is Double -> cv.put(k,v); else -> error("Unsupported value") } }
        insertOrThrow(table, null, cv)
    }
    private fun rows(table: String, where: String? = null, args: Array<String>? = null): List<Map<String, String?>> {
        val result = mutableListOf<Map<String, String?>>()
        readableDatabase.query(table, null, where, args, null, null, null).use { c -> while(c.moveToNext()) {
            result += (0 until c.columnCount).associate { c.getColumnName(it) to if(c.isNull(it)) null else c.getString(it) }
        } }
        return result
    }
    private fun Map<String, String?>.s(k: String) = getValue(k)!!
    private fun Map<String, String?>.n(k: String) = s(k).toDouble()
    private fun Map<String, String?>.flag(k: String) = s(k) == "1"
    private fun <T> tx(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { val result = block(db); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    @Synchronized fun settings() = rows("settings").associate { it.s("key") to it.s("value") }
    @Synchronized fun get(key: String, fallback: String = "") = rows("settings", "key=?", arrayOf(key)).firstOrNull()?.get("value") ?: fallback
    @Synchronized fun set(key: String, value: String) { writableDatabase.execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)", arrayOf(key, value)) }
    @Synchronized fun stock(id: String): Double = rows("inventory", "medicine_id=?", arrayOf(id)).sumOf { it.n("delta") } - rows("doses", "medicine_id=? AND corrected=0", arrayOf(id)).sumOf { it.n("quantity") }
    @Synchronized fun medicines() = rows("medicines").map { Medicine(it.s("id"), it.s("name"), it.s("note"), it.s("unit"), it.n("low"), it.flag("archived"), stock(it.s("id"))) }
    @Synchronized fun medicine(id: String) = medicines().firstOrNull { it.id == id }
    @Synchronized fun saveMedicine(id: String?, name: String, note: String, unit: String, initial: Double, low: Double, plan: Plan): String = tx { db ->
        require(name.isNotBlank() && unit.isNotBlank() && name.length <= 100 && initial.isFinite() && initial >= 0 && low.isFinite() && low >= 0)
        val target = id ?: UUID.randomUUID().toString()
        if(id == null) {
            db.put("medicines", mapOf("id" to target, "name" to name.trim(), "note" to note, "unit" to unit, "low" to low, "archived" to 0))
            db.put("inventory", mapOf("id" to UUID.randomUUID().toString(), "medicine_id" to target, "delta" to initial, "at" to System.currentTimeMillis(), "note" to "initial"))
        } else db.execSQL("UPDATE medicines SET name=?,note=?,unit=?,low=? WHERE id=?", arrayOf(name.trim(), note, unit, low, target))
        savePlan(plan.copy(medicine = target), replaceMedicine = target)
        target
    }
    @Synchronized fun adjustStock(id: String, absolute: Double, reason: String) {
        require(absolute.isFinite() && absolute >= 0 && reason.isNotBlank() && medicine(id) != null)
        tx { db -> db.put("inventory", mapOf("id" to UUID.randomUUID().toString(), "medicine_id" to id, "delta" to absolute - stock(id), "at" to System.currentTimeMillis(), "note" to reason)) }
    }
    @Synchronized fun archive(id: String, archived: Boolean) = tx { db ->
        db.execSQL("UPDATE medicines SET archived=? WHERE id=?", arrayOf(if(archived) 1 else 0, id))
        if(archived) db.execSQL("UPDATE expected SET obsolete=1 WHERE medicine_id=? AND due>=? AND id NOT IN (SELECT expected_id FROM doses WHERE expected_id IS NOT NULL) AND id NOT IN (SELECT expected_id FROM decisions)", arrayOf(id, System.currentTimeMillis()))
    }
    @Synchronized fun groups() = rows("med_groups").map { Group(it.s("id"), it.s("title")) }
    @Synchronized fun addGroup(name: String) { require(name.isNotBlank()); writableDatabase.put("med_groups", mapOf("id" to UUID.randomUUID().toString(), "title" to name.trim())) }
    @Synchronized fun plans() = rows("plans").map { r ->
        val a = JSONArray(r.s("slots"))
        Plan(r.s("id"), r.s("medicine_id"), LocalDate.parse(r.s("start")), r["end"]?.let(LocalDate::parse), r.s("mode"), r.s("weekdays").split(',').mapNotNull(String::toIntOrNull).toSet(), r.s("every_days").toInt(), r.s("on_days").toInt(), r.s("off_days").toInt(), r.flag("paused"), (0 until a.length()).map { val s = a.getJSONObject(it); Slot(s.getString("id"),s.getString("time"),s.getDouble("quantity"),s.optString("group")) }, LocalDate.parse(r.s("effective_from")), r["effective_to"]?.let(LocalDate::parse))
    }
    @Synchronized fun savePlan(p: Plan, replaceMedicine: String? = null, day: LocalDate = LocalDate.now()) = tx { db ->
        require(Schedule.valid(p))
        if(replaceMedicine != null) {
            // Keep historical snapshots and completed/declined slots, invalidate only unrecorded slots from this day.
            db.execSQL("UPDATE plans SET effective_to=? WHERE medicine_id=? AND (effective_to IS NULL OR effective_to>=?)", arrayOf(day.minusDays(1).toString(),replaceMedicine,day.toString()))
            db.execSQL("UPDATE expected SET obsolete=1 WHERE medicine_id=? AND local_date>=? AND id NOT IN (SELECT expected_id FROM doses WHERE expected_id IS NOT NULL AND corrected=0) AND id NOT IN (SELECT expected_id FROM decisions)", arrayOf(replaceMedicine, day.toString()))
        }
        val a = JSONArray(); p.slots.forEach { a.put(JSONObject().put("id",it.id).put("time",it.time).put("quantity",it.amount).put("group",it.group)) }
        db.put("plans", mapOf("id" to p.id,"medicine_id" to p.medicine,"start" to p.start.toString(),"end" to p.end?.toString(),"mode" to p.mode,"weekdays" to p.weekdays.joinToString(","),"every_days" to p.interval,"on_days" to p.on,"off_days" to p.off,"paused" to if(p.paused)1 else 0,"slots" to a.toString(),"created_at" to System.currentTimeMillis(),"effective_from" to p.effectiveFrom.toString(),"effective_to" to p.effectiveTo?.toString()))
    }
    @Synchronized fun materialize(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()) = tx { db ->
        val last = runCatching { LocalDate.parse(get("materialized_day")) }.getOrDefault(today)
        val from = minOf(last, today).coerceAtLeast(today.minusDays(730))
        val meds = medicines().associateBy { it.id }
        val plans = plans()
        val occupied = expectations().filter { it.date==today && status(it.id)!="unknown" }
            .map { it.medicine to Instant.ofEpochMilli(it.due).atZone(zone).toLocalTime() }.toSet()
        for(n in 0..java.time.temporal.ChronoUnit.DAYS.between(from, today.plusDays(31))) {
            val day = from.plusDays(n)
            plans.forEach { p -> val med = meds[p.medicine] ?: return@forEach
                if(med.archived) return@forEach
                Schedule.on(p,day,zone,med.name,med.unit).forEach slots@{ e ->
                    // A new version does not recreate today's already recorded wall-clock slot.
                    if(day==today && (e.medicine to Instant.ofEpochMilli(e.due).atZone(zone).toLocalTime()) in occupied && expected(e.id)==null) return@slots
                    db.execSQL("INSERT OR IGNORE INTO expected(id,plan_id,medicine_id,slot_id,group_id,due,quantity,local_date,zone,med_name,unit,obsolete) VALUES(?,?,?,?,?,?,?,?,?,?,?,0)",arrayOf(e.id,e.plan,e.medicine,e.slot,e.group,e.due,e.amount,e.date.toString(),zone.id,e.name,e.unit))
                    // Timezone changes affect future unrecorded wall-clock slots only.
                    if(day >= today) db.execSQL("UPDATE expected SET due=?,zone=?,obsolete=0 WHERE id=? AND id NOT IN (SELECT expected_id FROM doses WHERE expected_id IS NOT NULL) AND id NOT IN (SELECT expected_id FROM decisions)",arrayOf(e.due,zone.id,e.id))
                }
            }
        }
        set("materialized_day",today.toString())
    }
    @Synchronized fun expectations() = rows("expected").map { r -> Expected(r.s("id"),r.s("plan_id"),r.s("medicine_id"),r.s("slot_id"),r.s("group_id"),LocalDate.parse(r.s("local_date")),r.s("due").toLong(),r.n("quantity"),r.s("med_name"),r.s("unit"),r.flag("obsolete")) }
    @Synchronized fun expected(id: String) = expectations().firstOrNull { it.id == id }
    @Synchronized fun events(): List<Event> {
        val corrections = rows("corrections").groupBy { it.s("dose_id") }
        return rows("doses").map { r -> Event(r.s("id"),r.s("medicine_id"),r["expected_id"],r.s("taken_at").toLong(),r.s("recorded_at").toLong(),r.n("quantity"),r.s("method"),r.s("category"),r.flag("corrected"),r.s("request_key"),r.s("med_name"),r.s("unit"),corrections[r.s("id")]?.joinToString(" • ") { it.s("reason") } ?: "") }.sortedByDescending { it.takenAt }
    }
    @Synchronized fun status(id: String) = when {
        rows("doses","expected_id=? AND corrected=0",arrayOf(id)).isNotEmpty() -> "taken"
        rows("decisions","expected_id=?",arrayOf(id)).isNotEmpty() -> "declined"
        else -> "unknown"
    }
    /** Returns false for an already recorded request or any occupied slot. Batch is all-or-nothing. */
    @Synchronized fun confirm(items: List<Pair<String, String?>>, amount: Double?, at: Long, method: String, request: String): Boolean = tx { db ->
        require(items.isNotEmpty() && items.distinct().size == items.size && request.isNotBlank() && method in setOf("NFC","manual"))
        require(at > 0 && at <= System.currentTimeMillis()+60_000)
        if(rows("doses","request_key=? OR request_key LIKE ?",arrayOf(request,"$request:%")).isNotEmpty()) return@tx false
        val resolved = items.map { (medicine, id) ->
            val med = medicine(medicine) ?: error("Missing medicine")
            val e = id?.let(::expected)
            require(id == null || (e != null && e.medicine == medicine && !e.obsolete))
            val q = e?.amount ?: amount ?: error("Missing amount")
            require(q.isFinite() && q > 0)
            Triple(med,e,q)
        }
        if(resolved.any { it.second?.let { e -> status(e.id) == "taken" } == true }) return@tx false
        resolved.forEachIndexed { i,(m,e,q) ->
            db.put("doses",mapOf("id" to UUID.randomUUID().toString(),"medicine_id" to m.id,"expected_id" to e?.id,"taken_at" to at,"recorded_at" to System.currentTimeMillis(),"quantity" to q,"method" to method,"category" to Schedule.category(e,at),"corrected" to 0,"request_key" to if(items.size==1)request else "$request:$i","med_name" to (e?.name ?: m.name),"unit" to (e?.unit ?: m.unit)))
            if(e!=null) db.delete("decisions","expected_id=?",arrayOf(e.id))
        }
        true
    }
    @Synchronized fun decline(id: String) = tx { db ->
        require(expected(id)?.obsolete == false)
        if(status(id) != "taken") db.execSQL("INSERT OR REPLACE INTO decisions(expected_id,status,at) VALUES(?,'declined',?)",arrayOf(id,System.currentTimeMillis()))
    }
    @Synchronized fun undoDecline(id: String) { writableDatabase.delete("decisions","expected_id=?",arrayOf(id)); writableDatabase.delete("reminder_state","expected_id=?",arrayOf(id)) }
    @Synchronized fun correct(id: String, reason: String) = tx { db ->
        require(reason.isNotBlank())
        if(rows("doses","id=? AND corrected=0",arrayOf(id)).isEmpty()) return@tx
        db.execSQL("UPDATE doses SET corrected=1 WHERE id=?",arrayOf(id))
        db.put("corrections", mapOf("id" to UUID.randomUUID().toString(),"dose_id" to id,"at" to System.currentTimeMillis(),"reason" to reason.trim()))
        val e = events().firstOrNull { it.id == id }?.expected
        if(e!=null) db.delete("reminder_state","expected_id=?",arrayOf(e))
    }
    @Synchronized fun tags() = rows("tags").map { Binding(it.s("token"),it.s("kind"),it.s("target"),it.flag("active")) }
    @Synchronized fun bind(token: String, kind: String, target: String) {
        require(token.matches(Regex("v1:[0-9a-f]{32}")) && kind in setOf("medicine","group","today"))
        require(kind=="today" || (kind=="medicine" && medicine(target)!=null) || (kind=="group" && groups().any { it.id==target }))
        writableDatabase.execSQL("INSERT OR REPLACE INTO tags(token,kind,target,active) VALUES(?,?,?,1)",arrayOf(token,kind,target))
    }
    @Synchronized fun disableTag(token: String) { writableDatabase.execSQL("UPDATE tags SET active=0 WHERE token=?",arrayOf(token)) }
    @Synchronized fun reminders() = rows("reminder_state").map { Reminder(it.s("expected_id"),it.s("next_at").toLong(),it.s("attempt").toInt(),it.flag("ended"),it.s("snoozes").toInt()) }
    @Synchronized fun reminder(r: Reminder) { writableDatabase.execSQL("INSERT OR REPLACE INTO reminder_state(expected_id,next_at,attempt,ended,snoozes) VALUES(?,?,?,?,?)",arrayOf(r.id,r.next,r.attempt,if(r.ended)1 else 0,r.snoozes)) }
    @Synchronized fun snapshot() = Snapshot(medicines(),plans(),expectations(),events(),rows("decisions").map { it.s("expected_id") }.toSet(),groups(),tags(),settings())
    @Synchronized fun exportJson(): JSONObject = tx {
        val o=JSONObject().put("format",3)
        tables.forEach { table -> val a=JSONArray(); rows(table).forEach { r -> val item=JSONObject();r.forEach { (k,v) -> item.put(k,v ?: JSONObject.NULL) };a.put(item) };o.put(table,a) }
        o
    }
    @Synchronized fun importJson(o: JSONObject) = tx { db ->
        require(o.getInt("format") in 2..3)
        tables.asReversed().forEach { db.delete(it,null,null) }
        tables.forEach { table ->
            val columns = db.rawQuery("PRAGMA table_info($table)",null).use { c -> buildSet { while(c.moveToNext()) add(c.getString(1)) } }
            val a=o.optJSONArray(table) ?: if(o.getInt("format")==2 && table in setOf("decisions","reminder_state")) JSONArray() else error("Incomplete backup")
            require(a.length()<=100_000)
            for(i in 0 until a.length()) {
                val item=a.getJSONObject(i); val cv=ContentValues()
                item.keys().forEach { k -> require(k in columns);if(item.isNull(k))cv.putNull(k) else cv.put(k,item.get(k).toString()) }
                db.insertOrThrow(table,null,cv)
            }
        }
        fillLegacy(db)
        if(o.getInt("format")==2) db.execSQL("INSERT OR IGNORE INTO decisions(expected_id,status,at) SELECT substr(key,8),'declined',0 FROM settings WHERE key LIKE 'missed:%' AND substr(key,8) IN (SELECT id FROM expected)")
        db.rawQuery("PRAGMA foreign_key_check",null).use { require(!it.moveToFirst()) }
        require(plans().all(Schedule::valid))
        require(events().all { it.amount.isFinite() && it.amount>0 && it.method in setOf("NFC","manual") })
        require(expectations().all { it.amount.isFinite() && it.amount>0 })
        require(medicines().all { it.name.isNotBlank() && it.low.isFinite() && it.low>=0 })
        require(tags().all { it.token.matches(Regex("v1:[0-9a-f]{32}")) && it.kind in setOf("medicine","group","today") })
    }
    companion object { val tables=listOf("medicines","plans","expected","doses","corrections","inventory","med_groups","tags","settings","decisions","reminder_state") }
}
