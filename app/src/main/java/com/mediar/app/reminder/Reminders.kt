package com.mediar.app.reminder

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.mediar.app.MainActivity
import com.mediar.app.data.Store
import java.time.*

object Reminders {
    private const val CHANNEL="mediar_private"
    private fun alarm(ctx:Context)=ctx.getSystemService(AlarmManager::class.java)
    fun exactAllowed(ctx:Context)=Build.VERSION.SDK_INT<31 || alarm(ctx).canScheduleExactAlarms()
    fun notificationAllowed(ctx:Context)=Build.VERSION.SDK_INT<33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
    fun channel(ctx:Context){val n=ctx.getSystemService(NotificationManager::class.java);n.createNotificationChannel(NotificationChannel(CHANNEL,"یادآوری دارو",NotificationManager.IMPORTANCE_HIGH).apply {description="یادآوری بدون نام دارو روی صفحه قفل";lockscreenVisibility=android.app.Notification.VISIBILITY_PRIVATE})}
    private fun pending(ctx:Context,id:String,attempt:Int):PendingIntent {val intent=Intent(ctx,AlarmReceiver::class.java).setAction("com.mediar.ALARM").setData(android.net.Uri.parse("mediar://alarm/$id/$attempt")).putExtra("id",id).putExtra("attempt",attempt);return PendingIntent.getBroadcast(ctx,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)}
    fun cancel(ctx:Context,id:String){(0..10).forEach { alarm(ctx).cancel(pending(ctx,id,it)) }}
    fun schedule(ctx:Context,id:String,at:Long,attempt:Int=0){val p=pending(ctx,id,attempt);if(exactAllowed(ctx))alarm(ctx).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,p) else alarm(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,p)}
    fun rebuild(ctx:Context) { val db=Store(ctx);try {
        db.materialize(LocalDate.now().minusDays(1),31)
        val now=System.currentTimeMillis();val end=now+31L*86_400_000L
        db.expectations(now-86_400_000L,end).forEach { e -> cancel(ctx,e.id);if(e.due.toInstant().toEpochMilli()>now && db.status(e.id)=="unknown")schedule(ctx,e.id,e.due.toInstant().toEpochMilli()) }
    } finally {db.close()} }
    fun show(ctx:Context,id:String,attempt:Int){ val db=Store(ctx);try {
        if(db.expected(id)==null || db.status(id)!="unknown")return
        channel(ctx)
        if(notificationAllowed(ctx)) {
            val open=PendingIntent.getActivity(ctx,0,Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val note=NotificationCompat.Builder(ctx,CHANNEL).setSmallIcon(com.mediar.app.R.drawable.ic_mediar).setContentTitle("مدیار • زمان بررسی دارو").setContentText("برنامهٔ امروز را باز کنید.").setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
            ctx.getSystemService(NotificationManager::class.java).notify(id.hashCode(),note)
        }
        val limit=db.get("repeat_limit")?.toIntOrNull()?.coerceIn(0,10)?:2
        val interval=db.get("repeat_minutes")?.toLongOrNull()?.coerceIn(5,240)?:15
        if(attempt<limit) schedule(ctx,id,System.currentTimeMillis()+interval*60_000,attempt+1)
    } finally {db.close()} }
}
class AlarmReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){val id=intent.getStringExtra("id")?:return;Reminders.show(context,id,intent.getIntExtra("attempt",0))}}
class RebuildReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){ val pending=goAsync();Thread {try { Reminders.rebuild(context.applicationContext) } finally {pending.finish()} }.start() }}
