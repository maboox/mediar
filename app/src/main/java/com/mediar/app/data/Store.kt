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

data class Medicine(val id:String,val name:String,val note:String,val unit:String,val low:Double,val archived:Boolean,val stock:Double)
data class Dose(val id:String,val medicineId:String,val expectedId:String?,val takenAt:Long,val recordedAt:Long,val quantity:Double,val method:String,val category:String,val corrected:Boolean)
data class TagBinding(val token:String,val kind:String,val target:String,val active:Boolean)
data class Group(val id:String,val title:String)
data class DoseResult(val success:Boolean,val reason:String)

class Store(context: Context):SQLiteOpenHelper(context,"mediar.db",null,2) {
    override fun onConfigure(db:SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE medicines(id TEXT PRIMARY KEY,name TEXT NOT NULL,note TEXT NOT NULL,unit TEXT NOT NULL,low REAL NOT NULL,archived INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE plans(id TEXT PRIMARY KEY,medicine_id TEXT NOT NULL,start TEXT NOT NULL,end TEXT,mode TEXT NOT NULL,weekdays TEXT NOT NULL,every_days INTEGER NOT NULL,on_days INTEGER NOT NULL,off_days INTEGER NOT NULL,paused INTEGER NOT NULL,slots TEXT NOT NULL,created_at INTEGER NOT NULL,FOREIGN KEY(medicine_id) REFERENCES medicines(id))")
        db.execSQL("CREATE TABLE expected(id TEXT PRIMARY KEY,plan_id TEXT NOT NULL,medicine_id TEXT NOT NULL,slot_id TEXT NOT NULL,group_id TEXT NOT NULL,due INTEGER NOT NULL,quantity REAL NOT NULL,FOREIGN KEY(plan_id) REFERENCES plans(id))")
        db.execSQL("CREATE TABLE doses(id TEXT PRIMARY KEY,medicine_id TEXT NOT NULL,expected_id TEXT,taken_at INTEGER NOT NULL,recorded_at INTEGER NOT NULL,quantity REAL NOT NULL,method TEXT NOT NULL,category TEXT NOT NULL,corrected INTEGER NOT NULL DEFAULT 0,FOREIGN KEY(medicine_id) REFERENCES medicines(id),FOREIGN KEY(expected_id) REFERENCES expected(id))")
        db.execSQL("CREATE UNIQUE INDEX one_valid_dose_per_slot ON doses(expected_id) WHERE expected_id IS NOT NULL AND corrected=0")
        db.execSQL("CREATE TABLE corrections(id TEXT PRIMARY KEY,dose_id TEXT NOT NULL,at INTEGER NOT NULL,reason TEXT NOT NULL,FOREIGN KEY(dose_id) REFERENCES doses(id))")
        db.execSQL("CREATE TABLE inventory(id TEXT PRIMARY KEY,medicine_id TEXT NOT NULL,delta REAL NOT NULL,at INTEGER NOT NULL,note TEXT NOT NULL,FOREIGN KEY(medicine_id) REFERENCES medicines(id))")
        db.execSQL("CREATE TABLE med_groups(id TEXT PRIMARY KEY,title TEXT NOT NULL)")
        db.execSQL("CREATE TABLE tags(token TEXT PRIMARY KEY,kind TEXT NOT NULL,target TEXT NOT NULL,active INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }
    override fun onUpgrade(db:SQLiteDatabase, old:Int, newer:Int) { if(old==1 && newer>=2) db.execSQL("CREATE INDEX IF NOT EXISTS expected_due_idx ON expected(due)") }
    private fun SQLiteDatabase.insertRow(table:String, pairs:Map<String,Any?>) { val v=ContentValues(); pairs.forEach { (k,x)->when(x){null->v.putNull(k);is String->v.put(k,x);is Int->v.put(k,x);is Long->v.put(k,x);is Double->v.put(k,x);else->error("bad value") } }; insertOrThrow(table,null,v) }
    private fun rows(table:String, where:String?=null, args:Array<String>?=null):List<Map<String,String?>> {
        val out= mutableListOf<Map<String,String?>>()
        readableDatabase.query(table,null,where,args,null,null,null).use { c->while(c.moveToNext()){val row=linkedMapOf<String,String?>();for(i in 0 until c.columnCount)row[c.getColumnName(i)]=if(c.isNull(i))null else c.getString(i);out+=row} }; return out
    }
    private fun Map<String,String?>.s(key:String)=getValue(key)!!
    private fun Map<String,String?>.d(key:String)=s(key).toDouble()
    private fun Map<String,String?>.b(key:String)=s(key)=="1"
    fun medicines():List<Medicine> = rows("medicines").map { r -> Medicine(r.s("id"),r.s("name"),r.s("note"),r.s("unit"),r.d("low"),r.b("archived"),stock(r.s("id"))) }
    fun medicine(id:String)=medicines().firstOrNull { it.id==id }
    fun addMedicine(name:String,note:String,unit:String,stock:Double,low:Double):String {
        require(name.isNotBlank() && stock>=0 && low>=0)
        val id=UUID.randomUUID().toString(); writableDatabase.beginTransaction();try {
            writableDatabase.insertRow("medicines",mapOf("id" to id,"name" to name.trim(),"note" to note,"unit" to unit,"low" to low,"archived" to 0))
            writableDatabase.insertRow("inventory",mapOf("id" to UUID.randomUUID().toString(),"medicine_id" to id,"delta" to stock,"at" to System.currentTimeMillis(),"note" to "initial"))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }; return id
    }
    fun editMedicine(id:String,name:String,note:String,unit:String,low:Double) {require(name.isNotBlank() && low>=0);writableDatabase.execSQL("UPDATE medicines SET name=?,note=?,unit=?,low=? WHERE id=?",arrayOf(name.trim(),note,unit,low,id))}
    fun archive(id:String) { writableDatabase.execSQL("UPDATE medicines SET archived=1 WHERE id=?",arrayOf(id)) }
    fun stock(id:String):Double {
        val initial=rows("inventory","medicine_id=?",arrayOf(id)).sumOf { it.d("delta") }
        val consumed=rows("doses","medicine_id=? AND corrected=0",arrayOf(id)).sumOf { it.d("quantity") }
        return initial-consumed
    }
    fun adjustStock(id:String,delta:Double,note:String) { require(delta.isFinite()); writableDatabase.insertRow("inventory",mapOf("id" to UUID.randomUUID().toString(),"medicine_id" to id,"delta" to delta,"at" to System.currentTimeMillis(),"note" to note)) }
    fun groups()=rows("med_groups").map { Group(it.s("id"),it.s("title")) }
    fun addGroup(title:String):String { val id=UUID.randomUUID().toString(); writableDatabase.insertRow("med_groups",mapOf("id" to id,"title" to title));return id }
    fun plans():List<Plan> = rows("plans").map { r ->
        val slots=JSONArray(r.s("slots"));Plan(r.s("id"),r.s("medicine_id"),LocalDate.parse(r.s("start")),r["end"]?.let(LocalDate::parse),r.s("mode"),r.s("weekdays").split(',').mapNotNull(String::toIntOrNull).toSet(),r.s("every_days").toInt(),r.s("on_days").toInt(),r.s("off_days").toInt(),r.b("paused"),(0 until slots.length()).map { i->val o=slots.getJSONObject(i);SlotSpec(o.getString("id"),o.getString("time"),o.getDouble("quantity"),o.optString("group")) })
    }
    fun savePlan(p:Plan,replaceId:String?=null) {
        require(p.slots.isNotEmpty() && p.slots.all { it.quantity>0 && it.quantity.isFinite() && runCatching { LocalTime.parse(it.time) }.isSuccess })
        val arr=JSONArray();p.slots.forEach { arr.put(JSONObject().put("id",it.id).put("time",it.time).put("quantity",it.quantity).put("group",it.groupId)) }
        writableDatabase.beginTransaction();try {
            if(replaceId!=null) { writableDatabase.execSQL("DELETE FROM expected WHERE plan_id=? AND due>=? AND id NOT IN (SELECT expected_id FROM doses WHERE expected_id IS NOT NULL)",arrayOf(replaceId,LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()));writableDatabase.execSQL("UPDATE plans SET end=? WHERE id=? AND (end IS NULL OR end>?)",arrayOf(LocalDate.now().minusDays(1).toString(),replaceId,LocalDate.now().minusDays(1).toString())) }
            writableDatabase.insertRow("plans",mapOf("id" to p.id,"medicine_id" to p.medicineId,"start" to p.starts.toString(),"end" to p.ends?.toString(),"mode" to p.mode,"weekdays" to p.weekDays.joinToString(","),"every_days" to p.everyDays,"on_days" to p.onDays,"off_days" to p.offDays,"paused" to if(p.paused)1 else 0,"slots" to arr.toString(),"created_at" to System.currentTimeMillis()))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    @Synchronized fun materialize(from:LocalDate=LocalDate.now(),days:Long=30):List<Expected> {
        val zone=ZoneId.systemDefault();val generated=Schedule.horizon(plans(),from,days,zone)
        writableDatabase.beginTransaction();try {
            generated.forEach { e->writableDatabase.execSQL("INSERT OR IGNORE INTO expected(id,plan_id,medicine_id,slot_id,group_id,due,quantity) VALUES(?,?,?,?,?,?,?)",arrayOf(e.id,e.planId,e.medicineId,e.slotId,e.groupId,e.due.toInstant().toEpochMilli(),e.quantity));writableDatabase.execSQL("UPDATE expected SET due=? WHERE id=? AND id NOT IN (SELECT expected_id FROM doses WHERE expected_id IS NOT NULL)",arrayOf(e.due.toInstant().toEpochMilli(),e.id)) }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() };return generated
    }
    fun expectations(from:Long,to:Long):List<Expected> = rows("expected","due>=? AND due<?",arrayOf(from.toString(),to.toString())).map { r->Expected(r.s("id"),r.s("plan_id"),r.s("medicine_id"),r.s("slot_id"),r.s("group_id"),Instant.ofEpochMilli(r.s("due").toLong()).atZone(ZoneId.systemDefault()),r.d("quantity")) }.sortedBy { it.due.toInstant() }
    fun expected(id:String)=rows("expected","id=?",arrayOf(id)).firstOrNull()?.let { r ->Expected(r.s("id"),r.s("plan_id"),r.s("medicine_id"),r.s("slot_id"),r.s("group_id"),Instant.ofEpochMilli(r.s("due").toLong()).atZone(ZoneId.systemDefault()),r.d("quantity")) }
    fun doses():List<Dose> = rows("doses").map { r->Dose(r.s("id"),r.s("medicine_id"),r["expected_id"],r.s("taken_at").toLong(),r.s("recorded_at").toLong(),r.d("quantity"),r.s("method"),r.s("category"),r.b("corrected")) }.sortedByDescending { it.recordedAt }
    fun doseFor(id:String)=doses().firstOrNull { it.expectedId==id && !it.corrected }
    @Synchronized fun confirm(medicineId:String,expectedId:String?,quantity:Double,takenAt:Long,method:String):DoseResult {
        if(quantity<=0 || !quantity.isFinite() || medicine(medicineId)==null) return DoseResult(false,"invalid")
        val expected=expectedId?.let(::expected)
        if(expectedId!=null && (expected==null || expected.medicineId!=medicineId))return DoseResult(false,"invalid slot")
        val category=if(expected==null)"outside" else Schedule.classification(expected,Instant.ofEpochMilli(takenAt))
        val db=writableDatabase; db.beginTransaction()
        return try {
            if(expectedId!=null && doseFor(expectedId)!=null)DoseResult(false,"already recorded")
            else { db.insertRow("doses",mapOf("id" to UUID.randomUUID().toString(),"medicine_id" to medicineId,"expected_id" to expectedId,"taken_at" to takenAt,"recorded_at" to System.currentTimeMillis(),"quantity" to quantity,"method" to method,"category" to category,"corrected" to 0));db.setTransactionSuccessful();DoseResult(true,category) }
        } catch (_:android.database.sqlite.SQLiteConstraintException) { DoseResult(false,"already recorded") } finally { db.endTransaction() }
    }
    fun markNotTaken(expectedId:String) { if(doseFor(expectedId)==null) set("missed:$expectedId","explicit") }
    fun status(expectedId:String)=if(doseFor(expectedId)!=null)"taken" else if(get("missed:$expectedId")=="explicit")"declined" else "unknown"
    @Synchronized fun correct(doseId:String,reason:String) {
        require(reason.isNotBlank());val db=writableDatabase;db.beginTransaction();try {
            db.execSQL("UPDATE doses SET corrected=1 WHERE id=? AND corrected=0",arrayOf(doseId))
            db.insertRow("corrections",mapOf("id" to UUID.randomUUID().toString(),"dose_id" to doseId,"at" to System.currentTimeMillis(),"reason" to reason))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun corrections()=rows("corrections")
    fun addTag(token:String,kind:String,target:String) { require(kind in listOf("medicine","group","today")); writableDatabase.execSQL("INSERT OR REPLACE INTO tags(token,kind,target,active) VALUES(?,?,?,1)",arrayOf(token,kind,target)) }
    fun tag(token:String)=rows("tags","token=? AND active=1",arrayOf(token)).firstOrNull()?.let { TagBinding(it.s("token"),it.s("kind"),it.s("target"),true) }
    fun tags()=rows("tags").map { TagBinding(it.s("token"),it.s("kind"),it.s("target"),it.b("active")) }
    fun disableTag(token:String){writableDatabase.execSQL("UPDATE tags SET active=0 WHERE token=?",arrayOf(token))}
    fun set(key:String,value:String){writableDatabase.execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)",arrayOf(key,value))}
    fun get(key:String)=rows("settings","key=?",arrayOf(key)).firstOrNull()?.get("value")
    companion object { val backupTables=listOf("medicines","plans","expected","doses","corrections","inventory","med_groups","tags","settings") }
    fun exportJson():JSONObject {
        val o=JSONObject().put("format",2);backupTables.forEach { table->val arr=JSONArray();rows(table).forEach { row->val obj=JSONObject();row.forEach { (k,v)->if(v==null)obj.put(k,JSONObject.NULL) else obj.put(k,v) };arr.put(obj) };o.put(table,arr) };return o
    }
    @Synchronized fun importJson(o:JSONObject) {
        require(o.getInt("format")==2)
        val db=writableDatabase;db.beginTransaction();try {
            backupTables.asReversed().forEach { db.delete(it,null,null) }
            backupTables.forEach { table->val arr=o.getJSONArray(table);require(arr.length()<200_000);for(i in 0 until arr.length()) {val item=arr.getJSONObject(i);val v=ContentValues();item.keys().forEach { key->val value=item.get(key);if(value==JSONObject.NULL)v.putNull(key) else v.put(key,value.toString()) };db.insertOrThrow(table,null,v)} }
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
}
