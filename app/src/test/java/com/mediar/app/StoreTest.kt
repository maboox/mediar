package com.mediar.app

import com.mediar.app.backup.Backup
import com.mediar.app.data.*
import com.mediar.app.logic.*
import com.mediar.app.reminder.Reminders
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows
import java.time.*
import java.util.UUID
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=MediarApplication::class)
class StoreTest {
    private lateinit var app: MediarApplication
    private lateinit var db: Store
    private val day get()=LocalDate.now()
    @Before fun setup(){app=RuntimeEnvironment.getApplication() as MediarApplication;db=app.store}
    private fun add(slots: List<Slot> = listOf(Slot(time="08:00",amount=1.0))): String {
        val id=db.saveMedicine(null,"داروی آزمایشی","note","tablet",10.0,2.0,Plan(medicine="new",start=day,slots=slots));db.materialize();return id
    }
    private fun first(id: String)=db.expectations().filter{it.medicine==id && it.date==day && !it.obsolete}.minBy{it.due}
    @Test fun repeatedSlotIsPreventedAndStockReducedOnce(){val id=add();val e=first(id);assertTrue(db.confirm(listOf(id to e.id),null,System.currentTimeMillis(),"NFC","first"));assertFalse(db.confirm(listOf(id to e.id),null,System.currentTimeMillis(),"NFC","second"));assertEquals(9.0,db.stock(id),.001)}
    @Test fun outsideRequestIsIdempotent(){val id=add();assertTrue(db.confirm(listOf(id to null),.5,System.currentTimeMillis(),"manual","same"));assertFalse(db.confirm(listOf(id to null),.5,System.currentTimeMillis(),"manual","same"));assertEquals(9.5,db.stock(id),.001);assertEquals("unknown",db.status(first(id).id))}
    @Test fun sameSlotAcrossTwoHelpersHasOnlyOneEvent(){val id=add();val e=first(id);val second=Store(app);val pool=Executors.newFixedThreadPool(2)
        val a=pool.submit<Boolean>{db.confirm(listOf(id to e.id),null,System.currentTimeMillis(),"NFC",UUID.randomUUID().toString())}
        val b=pool.submit<Boolean>{second.confirm(listOf(id to e.id),null,System.currentTimeMillis(),"manual",UUID.randomUUID().toString())}
        assertEquals(1,listOf(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)).count{it});pool.shutdown();second.close();assertEquals(1,db.events().size)
    }
    @Test fun groupBatchIsAtomic(){val a=add();val b=add();val ea=first(a);val eb=first(b);db.confirm(listOf(a to ea.id),null,System.currentTimeMillis(),"manual","used");assertFalse(db.confirm(listOf(a to ea.id,b to eb.id),null,System.currentTimeMillis(),"manual","batch"));assertEquals("unknown",db.status(eb.id));assertEquals(10.0,db.stock(b),.001)}
    @Test fun groupRequestReplayDoesNotDuplicateEvents(){val a=add();val b=add();val pairs=listOf(a to first(a).id,b to first(b).id);assertTrue(db.confirm(pairs,null,System.currentTimeMillis(),"NFC","box"));assertFalse(db.confirm(pairs,null,System.currentTimeMillis(),"NFC","box"));assertEquals(2,db.events().size)}
    @Test fun earlyRecordKeepsActualTimeAndSchedule(){val id=add();val e=first(id);val early=minOf(e.due-3_600_000,System.currentTimeMillis());db.confirm(listOf(id to e.id),null,early,"manual","early");assertEquals("early",db.events().first().category);assertEquals(early,db.events().first().takenAt);assertEquals(e.due,db.expected(e.id)!!.due)}
    @Test fun correctionPreservesAuditAndRestoresInventory(){val id=add();val e=first(id);db.confirm(listOf(id to e.id),null,System.currentTimeMillis(),"manual","one");val event=db.events().first();db.correct(event.id,"wrong record");db.correct(event.id,"retry");assertEquals(10.0,db.stock(id),.001);assertTrue(db.events().first().corrected);assertEquals("wrong record",db.events().first().correction);assertEquals("unknown",db.status(e.id));assertTrue(db.confirm(listOf(id to e.id),null,System.currentTimeMillis(),"manual","replacement"))}
    @Test fun declineIsDifferentFromUnknownAndUsesNoStock(){val id=add(listOf(Slot(time="08:00",amount=1.0),Slot(time="20:00",amount=.5)));val e=first(id);db.decline(e.id);assertEquals("declined",db.status(e.id));assertEquals(10.0,db.stock(id),.001);assertEquals(1,db.snapshot().today().count{db.status(it.id)=="unknown"});db.undoDecline(e.id);assertEquals("unknown",db.status(e.id))}
    @Test fun editPreservesYesterdaySnapshotAndAvoidsTodayDuplicate(){val id=add();val p=db.plans().first();val yesterday=day.minusDays(1)
        // Materialize a historical version without changing its original slot snapshot.
        db.savePlan(p.copy(id="historic",start=yesterday,effectiveFrom=yesterday,effectiveTo=yesterday));db.set("materialized_day",yesterday.toString());db.materialize()
        val old=db.expectations().first{it.date==yesterday};val today=first(id)
        db.confirm(listOf(id to today.id),null,System.currentTimeMillis(),"manual","taken")
        db.saveMedicine(id,"new name","","tablet",10.0,2.0,p.copy(id="new-version",slots=listOf(Slot(time="08:00",amount=.5)),effectiveFrom=day))
        db.materialize();assertEquals("داروی آزمایشی",db.expected(old.id)!!.name);assertEquals(1.0,db.expected(old.id)!!.amount,.001)
        assertEquals(1,db.snapshot().today().count{it.medicine==id});assertEquals("taken",db.status(today.id));assertEquals(.5,db.expectations().first{it.date==day.plusDays(1) && it.plan=="new-version"}.amount,.001)
    }
    @Test fun timezoneDoesNotRewriteCompletedSlot(){val id=add();val e=first(id);db.confirm(listOf(id to e.id),null,System.currentTimeMillis(),"manual","taken");db.materialize(day,ZoneId.of("Pacific/Auckland"));assertEquals(e.due,db.expected(e.id)!!.due)}
    @Test fun archiveRetainsHistoryAndDisablesFuture(){val id=add();val e=first(id);db.confirm(listOf(id to e.id),null,System.currentTimeMillis(),"manual","done");db.archive(id,true);db.materialize();assertEquals(1,db.events().size);assertTrue(db.medicine(id)!!.archived);assertTrue(db.expectations().filter{it.medicine==id && it.date>day}.all{it.obsolete})}
    @Test fun refillIsAbsoluteAndCorrectionStillAddsBackDose(){val id=add();val e=first(id);db.confirm(listOf(id to e.id),null,System.currentTimeMillis(),"manual","done");db.adjustStock(id,20.0,"refill");assertEquals(20.0,db.stock(id),.001);db.correct(db.events().first().id,"wrong");assertEquals(21.0,db.stock(id),.001)}
    @Test fun passwordAndTamperedBackupsDoNotChangeData(){val id=add();val password="strong-password".toCharArray();val bytes=Backup.create(app,db,password);db.adjustStock(id,25.0,"later")
        assertTrue(runCatching{Backup.restore(app,db,"wrong-password".toCharArray(),bytes)}.isFailure);assertEquals(25.0,db.stock(id),.001)
        val tampered=bytes.copyOf();tampered[tampered.lastIndex]=(tampered.last().toInt() xor 1).toByte();assertTrue(runCatching{Backup.restore(app,db,password,tampered)}.isFailure);assertEquals(25.0,db.stock(id),.001)
        Backup.restore(app,db,password,bytes);assertEquals(10.0,db.stock(id),.001)
    }
    @Test fun invalidBackupRollsBackDatabase(){val id=add();val backup=db.exportJson();backup.getJSONArray("doses").put(org.json.JSONObject().put("id","bad").put("medicine_id","missing").put("expected_id",org.json.JSONObject.NULL).put("taken_at",1).put("recorded_at",1).put("quantity",1).put("method","manual").put("category","outside").put("corrected",0));assertTrue(runCatching{db.importJson(backup)}.isFailure);assertEquals(10.0,db.stock(id),.001);assertEquals(1,db.medicines().size)}
    @Test fun pinRecoveryAndThrottling(){val recovery=Pin.create(db,"1234");assertTrue(Pin.verify(db,"1234"));assertTrue(Pin.verify(db,recovery,true));repeat(5){assertFalse(Pin.verify(db,"9999"))};assertFalse(Pin.verify(db,"1234"));db.set("pin_block","0");assertTrue(Pin.verify(db,"1234"))}
    @Test fun rebuildKeepsRetryBudgetAndFutureAlarms(){val id=add(listOf(Slot(time="08:00",amount=1.0),Slot(time="20:00",amount=1.0)));Reminders.rebuild(app);val e=first(id);val state=db.reminders().first{it.id==e.id};db.reminder(state.copy(attempt=3,ended=true));Reminders.rebuild(app);assertTrue(db.reminders().first{it.id==e.id}.ended);assertTrue(db.reminders().any{it.id!=e.id && !it.ended});assertTrue(Shadows.shadowOf(app.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.isNotEmpty())}
    @Test fun privateNotificationContainsNoMedicineName(){add();val note=Reminders.notification(app,"fake",false);assertFalse(note.extras.toString().contains("داروی آزمایشی"))}
    @Test fun realV2MigrationKeepsTagsHistoryAndInventory(){
        val file=app.getDatabasePath("legacy.db");file.parentFile!!.mkdirs()
        val old=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null)
        LegacySchema.create(old)
        old.execSQL("INSERT INTO medicines VALUES('m','old medicine','','tablet',2,0)")
        old.execSQL("INSERT INTO plans VALUES('p','m',?,NULL,'daily','1,2,3,4,5,6,7',1,1,1,0,?,1)",arrayOf(day.toString(),"[{\"id\":\"s\",\"time\":\"08:00\",\"quantity\":1,\"group\":\"\"}]"))
        old.execSQL("INSERT INTO expected VALUES('e','p','m','s','',?,1)",arrayOf(day.atTime(8,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
        old.execSQL("INSERT INTO doses VALUES('d','m','e',1,1,1,'manual','scheduled',0)")
        old.execSQL("INSERT INTO inventory VALUES('i','m',10,1,'initial')")
        old.execSQL("INSERT INTO tags VALUES('v1:0123456789abcdef0123456789abcdef','medicine','m',1)")
        old.version=2;old.close()
        val migrated=Store(app,"legacy.db")
        assertEquals(9.0,migrated.stock("m"),.001)
        assertEquals("taken",migrated.status("e"))
        assertEquals(day,migrated.expected("e")!!.date)
        assertEquals("old medicine",migrated.events().first().name)
        assertEquals(1,migrated.tags().size)
        migrated.close()
    }

    @Test fun snoozeCannotMoveFutureReminderEarlierAndHasIndependentLimit(){
        val id=add();Reminders.rebuild(app)
        val e=db.expectations().first{it.medicine==id && it.date==day.plusDays(1)}
        repeat(3){assertTrue(Reminders.snooze(app,e.id))}
        assertFalse(Reminders.snooze(app,e.id))
        assertTrue(db.reminders().first{it.id==e.id}.next>=e.due+15*60_000)
        assertTrue(db.reminders().first{it.id==first(id).id}.snoozes==0)
    }

    @Test fun archiveSuppressesPendingPastAlertsAndConsumerCards(){
        val id=add();Reminders.rebuild(app);val e=first(id)
        val r=db.reminders().first{it.id==e.id};db.reminder(r.copy(next=System.currentTimeMillis()-1))
        db.archive(id,true);Reminders.fire(app,e.id)
        assertTrue(db.snapshot().today().none{it.medicine==id})
        assertTrue(Shadows.shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).allNotifications.isEmpty())
        assertNotNull(db.expected(e.id))
    }

}
