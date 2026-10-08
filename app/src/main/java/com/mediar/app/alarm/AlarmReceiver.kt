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
import com.mediar.app.logic.DoseLogic
import com.mediar.app.logic.Fmt
import com.mediar.app.logic.Schedule
import com.mediar.app.ui.PopupActivity
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val medId = intent.getStringExtra(Alarms.EXTRA_MED_ID) ?: return
        val dueAt = intent.getLongExtra(Alarms.EXTRA_DUE_AT, 0L)
        val triggerAt = intent.getLongExtra(Alarms.EXTRA_TRIGGER_AT, 0L)

        val db = Db.get(ctx)
        val med = db.getMed(medId) ?: return
        if (med.archived) {
            Alarms.cancelAlarm(ctx, medId)
            return
        }

        val nowMs = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)

        // فقط اگر این نوبت هنوز ثبت نشده و پنجره‌اش باز است، یادآوری کن
        if (dueAt != 0L && db.logFor(medId, dueAt) == null) {
            val prefs = Prefs.get(ctx)
            val due = Instant.ofEpochMilli(dueAt).atZone(zone)
            val slot = DoseLogic.slotFor(Schedule(med.scheduleJson), due, prefs.earlyWindowMin, prefs.lateWindowMin)
            if (slot.contains(now) || now.isBefore(slot.start)) {
                notify(ctx, med, due)
            }
        }

        // آلارم بعدی (تکرار همین نوبت یا نوبت بعدی). زمان را کمی جلو می‌بریم
        // تا اگر آلارم چند ثانیه زودتر زده شد، همین آلارم دوباره تنظیم نشود.
        val base = maxOf(nowMs, triggerAt) + 1_000
        Alarms.rescheduleMed(ctx, med, Instant.ofEpochMilli(base).atZone(zone))
    }

    private fun notify(ctx: Context, med: Med, due: ZonedDateTime) {
        val popupIntent = Intent(ctx, PopupActivity::class.java).apply {
            action = PopupActivity.ACTION_REMINDER
            putExtra(PopupActivity.EXTRA_MED_ID, med.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val open = PendingIntent.getActivity(
            ctx, Alarms.notificationId(med.id), popupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(ctx, App.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_pill)
            .setContentTitle("وقت قرصه! " + med.name)
            .setContentText("نوبت ساعت " + Fmt.time(due) + " — قرص رو بخور و گوشی رو به جعبه بزن")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "نوبت ساعت " + Fmt.time(due) + "\nقرص رو بخور و بعد گوشی رو به جعبه بزن تا ثبت بشه."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(open)
            // صفحه را روشن می‌کند و پاپ‌آپ یادآوری (با پخش صدا) را باز می‌کند
            .setFullScreenIntent(open, true)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(Alarms.notificationId(med.id), n)
        } catch (e: SecurityException) {
            // مجوز نوتیفیکیشن داده نشده
        }
    }
}
