package com.mediar.app
import android.database.sqlite.SQLiteDatabase
object LegacySchema {
 fun create(db: SQLiteDatabase) {

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
}
