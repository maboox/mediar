package com.mediar.app.alarm

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mediar.app.App
import com.mediar.app.R
import com.mediar.app.data.Db
import com.mediar.app.data.Med
import com.mediar.app.data.Prefs
import com.mediar.app.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val medId = intent.getStringExtra(Alarms.EXTRA_MED_ID) ?: return
        val dueAt = intent.getLongExtra(Alarms.EXTRA_DUE_AT, 0L)
        val repeat = intent.getIntExtra(Alarms.EXTRA_REPEAT, 0)

        val db = Db.get(ctx)
        val med = db.getMed(medId) ?: return
        if (med.archived) return

        // اگر در این فاصله ثبت شده، فقط آلارم نوبت بعدی را بگذار
        if (dueAt != 0L && db.hasLogFor(medId, dueAt)) {
            Alarms.rescheduleAll(ctx)
            return
        }

        notify(ctx, med, dueAt)

        // تکرار تا وقتی اسکن نشده
        val prefs = Prefs.get(ctx)
        if (repeat < prefs.maxRepeats) {
            Alarms.scheduleAt(
                ctx, medId, dueAt,
                System.currentTimeMillis() + prefs.repeatMin * 60_000L,
                repeat + 1
            )
        }
    }

    private fun notify(ctx: Context, med: Med, dueAt: Long) {
        val timeText = if (dueAt != 0L) {
            DateTimeFormatter.ofPattern("HH:mm")
                .format(Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault()))
        } else ""

        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(ctx, App.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_pill)
            .setContentTitle("وقت قرصه! " + med.name)
            .setContentText("نوبت ساعت " + timeText + " — بعد از خوردن، گوشی را به جعبه بزن")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(open)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(med.id.hashCode(), n)
        } catch (e: SecurityException) {
            // مجوز نوتیفیکیشن داده نشده
        }
    }
}
