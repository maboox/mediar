package com.mediar.app.reminder

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mediar.app.*
import com.mediar.app.audio.Speech
import com.mediar.app.logic.Reminder
import java.time.*
import java.util.concurrent.Executors

object Reminders {
    private const val NORMAL="mediar_reminders_v3"
    private const val VOICE="mediar_voice_v3"
    private val io=Executors.newSingleThreadExecutor()
    fun work(block: ()->Unit){io.execute {runCatching(block)}}
    private fun alarm(c: Context)=c.getSystemService(AlarmManager::class.java)
    fun exact(c: Context)=Build.VERSION.SDK_INT<31 || alarm(c).canScheduleExactAlarms()
    fun allowed(c: Context)=c.getSystemService(NotificationManager::class.java).areNotificationsEnabled() && (Build.VERSION.SDK_INT<33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
    fun channels(c: Context) {
        val manager=c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(NORMAL,"Mediar • reminders",NotificationManager.IMPORTANCE_HIGH).apply {lockscreenVisibility=Notification.VISIBILITY_PRIVATE;description="یادآوری عمومی بدون نام دارو"})
        manager.createNotificationChannel(NotificationChannel(VOICE,"Mediar • voice",NotificationManager.IMPORTANCE_HIGH).apply {setSound(null,null);enableVibration(false);lockscreenVisibility=Notification.VISIBILITY_PRIVATE})
    }
    private fun pending(c: Context,id: String)=PendingIntent.getBroadcast(c,0,Intent(c,AlarmReceiver::class.java).setAction("com.mediar.ALARM").setData(Uri.parse("mediar://alarm/$id")).putExtra("id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancel(c: Context,id: String) {alarm(c).cancel(pending(c,id));c.getSystemService(NotificationManager::class.java).cancel(id.hashCode())}
    private fun schedule(c: Context,id: String,at: Long) {
        val p=pending(c,id);val time=at.coerceAtLeast(System.currentTimeMillis()+1_000)
        try {if(exact(c))alarm(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,p) else alarm(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,p)}
        catch(_:SecurityException){alarm(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,p)}
    }
    @Synchronized fun rebuild(c: Context) {
        channels(c)
        val db=c.store();db.materialize()
        val now=System.currentTimeMillis();val today=LocalDate.now();val states=db.reminders().associateBy{it.id}
        db.expectations().forEach { e ->
            alarm(c).cancel(pending(c,e.id))
            if(e.obsolete || db.medicine(e.medicine)?.archived==true || db.status(e.id)!="unknown" || e.date<today) {cancel(c,e.id);return@forEach}
            if(e.date>today.plusDays(1))return@forEach
            var state=states[e.id]
            if(state==null){state=Reminder(e.id,maxOf(e.due,now+3_000),0,false,0);db.reminder(state)}
            else if(state.attempt==0 && state.snoozes==0 && !state.ended && e.due>now){state=state.copy(next=e.due);db.reminder(state)}
            if(!state.ended) schedule(c,e.id,state.next)
        }
        val tomorrow=today.plusDays(1).atTime(0,5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        schedule(c,"rollover",tomorrow)
    }
    @Synchronized fun fire(c: Context,id: String) {
        if(id=="rollover"){rebuild(c);return}
        val db=c.store();val e=db.expected(id) ?: return
        val state=db.reminders().firstOrNull{it.id==id} ?: return
        if(state.ended || e.obsolete || db.medicine(e.medicine)?.archived==true || db.status(id)!="unknown" || e.date<LocalDate.now()) {cancel(c,id);return}
        // A stale callback after rescheduling cannot notify early or replay an attempt.
        val now=System.currentTimeMillis();if(now+2_000<state.next)return
        val limit=db.get("repeat_limit","2").toIntOrNull()?.coerceIn(0,6)?:2
        val minutes=db.get("repeat_minutes","15").toLongOrNull()?.coerceIn(5,120)?:15
        val next=state.copy(next=now+minutes*60_000,attempt=state.attempt+1,ended=state.attempt>=limit)
        db.reminder(next)
        if(!next.ended)schedule(c,id,next.next)
        channels(c)
        if(!allowed(c))return
        val voice=db.get("alarm_voice","false")=="true" && db.get("mute","false")!="true" && exact(c)
        if(voice){
            val service=Intent(c,AlertService::class.java).putExtra("id",id)
            try {ContextCompat.startForegroundService(c,service);return} catch(_:RuntimeException) { /* audible system fallback */ }
        }
        c.getSystemService(NotificationManager::class.java).notify(id.hashCode(),notification(c,id,false))
    }
    fun notification(c: Context,id: String,speech: Boolean): Notification {
        val en=c.store().get("language","fa")=="en"
        val open=PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java).setAction("com.mediar.OPEN").setData(Uri.parse("mediar://dose/$id")).putExtra("expected",id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snooze=PendingIntent.getBroadcast(c,0,Intent(c,SnoozeReceiver::class.java).setData(Uri.parse("mediar://snooze/$id")).putExtra("id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(c,if(speech || c.store().get("mute","false")=="true")VOICE else NORMAL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(if(en)"Mediar • reminder" else "مدیار • یادآوری")
            .setContentText(if(en)"Open today's medication plan." else "برنامهٔ داروی امروز را بررسی کنید.")
            .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(c,VOICE).setSmallIcon(R.drawable.ic_notification).setContentTitle("Mediar").setContentText(if(en)"You have a reminder" else "یک یادآوری دارید").build())
            .addAction(R.drawable.ic_notification,if(en)"15 min later" else "۱۵ دقیقه بعد",snooze).build()
    }
    @Synchronized fun snooze(c: Context,id: String): Boolean {
        val db=c.store();val e=db.expected(id) ?: return false
        if(db.status(id)!="unknown" || e.obsolete)return false
        val r=db.reminders().firstOrNull{it.id==id} ?: return false
        if(r.snoozes>=3)return false
        val updated=r.copy(next=maxOf(System.currentTimeMillis(),e.due)+15*60_000,ended=false,snoozes=r.snoozes+1)
        db.reminder(updated);cancel(c,id);schedule(c,id,updated.next)
        return true
    }
}
class AlarmReceiver: BroadcastReceiver(){override fun onReceive(c: Context,i: Intent){val p=goAsync();Reminders.work {try {i.getStringExtra("id")?.let{Reminders.fire(c.applicationContext,it)}}finally{p.finish()}}}}
class SnoozeReceiver: BroadcastReceiver(){override fun onReceive(c: Context,i: Intent){val p=goAsync();Reminders.work {try {i.getStringExtra("id")?.let{Reminders.snooze(c.applicationContext,it)}}finally{p.finish()}}}}
class RebuildReceiver: BroadcastReceiver(){
    override fun onReceive(c: Context,i: Intent){
        if(i.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))return
        val p=goAsync();Reminders.work {try{Reminders.rebuild(c.applicationContext)}finally{p.finish()}}
    }
}
class AlertService: Service() {
    private var speech: Speech?=null
    private val main=Handler(Looper.getMainLooper())
    private var last=0L
    override fun onBind(i: Intent?)=null
    override fun onStartCommand(i: Intent?,flags: Int,startId: Int): Int {
        val id=i?.getStringExtra("id") ?: run{stopSelf();return START_NOT_STICKY}
        Reminders.channels(this);startForeground(id.hashCode(),Reminders.notification(this,id,true))
        if(SystemClock.elapsedRealtime()-last<5_000)return START_NOT_STICKY
        last=SystemClock.elapsedRealtime();speech?.release();speech=Speech(this)
        val en=store().get("language","fa")=="en"
        speech?.speak(if(en)"Please open today's medication plan." else "لطفاً برنامهٔ داروی امروز را بررسی کنید.",if(en)"due_en" else "due_fa",onUnavailable={
            getSystemService(NotificationManager::class.java).notify(id.hashCode(),Reminders.notification(this,id,false))
        }) {
            stopForeground(STOP_FOREGROUND_DETACH);stopSelf()
        }
        main.removeCallbacksAndMessages(null);main.postDelayed({stopForeground(STOP_FOREGROUND_DETACH);stopSelf()},25_000)
        return START_NOT_STICKY
    }
    override fun onDestroy(){main.removeCallbacksAndMessages(null);speech?.release();super.onDestroy()}
}

/** Exact-alarm revocation kills the app without a revoke callback. This periodic worker restores inexact fallback. */
class RepairWorker(context: Context, params: androidx.work.WorkerParameters): androidx.work.CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {Reminders.rebuild(applicationContext);Result.success()}catch(_:Exception){Result.retry()}
    }
    companion object {
        fun ensure(context: Context) {
            val work=androidx.work.PeriodicWorkRequestBuilder<RepairWorker>(15,java.util.concurrent.TimeUnit.MINUTES).build()
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork("mediar-repair",androidx.work.ExistingPeriodicWorkPolicy.KEEP,work)
        }
    }
}
