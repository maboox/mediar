package com.mediar.app.backup

import com.mediar.app.logic.Snapshot
import java.time.*
import java.time.format.DateTimeFormatter

object Report {
    private fun esc(s: String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    fun html(s: Snapshot,from: LocalDate=LocalDate.now().minusDays(29),to: LocalDate=LocalDate.now()): String {
        val en=s.settings["language"]=="en"
        fun t(fa: String,enText: String)=if(en)enText else fa
        fun time(ms: Long)=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
        val expected=s.expected.filter{!it.obsolete && it.date>=from && it.date<=to}.sortedBy{it.due}
        val events=s.events.filter{Instant.ofEpochMilli(it.takenAt).atZone(ZoneId.systemDefault()).toLocalDate() in from..to}
        val rows=expected.joinToString(""){e->val event=s.event(e.id);"<tr><td>${esc(e.name)}</td><td dir='ltr'>${time(e.due)}</td><td>${e.amount} ${esc(e.unit)}</td><td>${when(s.status(e.id)){"taken"->t("مصرف تأییدشده","Confirmed");"declined"->t("مصرف‌نشده با اعلام صریح","Explicitly not taken");else->t("نامشخص / ثبت‌نشده","Unknown / unrecorded")}}</td><td dir='ltr'>${event?.let{time(it.takenAt)}?:"—"}</td></tr>"}
        val eventRows=events.joinToString(""){e->"<tr><td>${esc(e.name)}</td><td dir='ltr'>${time(e.takenAt)}</td><td>${e.amount} ${esc(e.unit)}</td><td>${when(e.category){"early"->t("زودتر از برنامه","Early");"outside"->t("خارج از برنامه","Outside plan");"late"->t("با تأخیر","Late");else->t("طبق برنامه","Scheduled")}}</td><td>${if(e.corrected)t("اصلاح شده: ","Corrected: ")+esc(e.correction) else esc(e.method)}</td><td dir='ltr'>${time(e.recordedAt)}</td></tr>"}
        return """<!doctype html><html lang='${if(en)"en" else "fa"}' dir='${if(en)"ltr" else "rtl"}'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Mediar</title><style>body{font-family:Tahoma,Arial,sans-serif;line-height:1.8;margin:24px;color:#292339}h1{color:#6950d9}table{border-collapse:collapse;width:100%;font-size:14px}th,td{padding:10px;border:1px solid #ddd;text-align:start}th{background:#eee8ff}.scroll{overflow-x:auto}@media print{body{margin:10px}tr{break-inside:avoid}}</style><h1>${t("گزارش مدیار","Mediar report")}</h1><p dir='ltr'>$from — $to • ${ZoneId.systemDefault().id}</p><p>${t("این گزارش ثبت‌های کاربر را نشان می‌دهد. وضعیت نامشخص به معنی مصرف‌نشدن قطعی نیست.","This report shows user records. Unknown does not mean definitely not taken.")}</p><h2>${t("نوبت‌های برنامه","Planned doses")}</h2><div class='scroll'><table><tr><th>${t("دارو","Medicine")}</th><th>${t("زمان برنامه","Due")}</th><th>${t("مقدار","Amount")}</th><th>${t("وضعیت","Status")}</th><th>${t("مصرف واقعی","Actual time")}</th></tr>$rows</table></div><h2>${t("همهٔ ثبت‌ها و اصلاح‌ها","All records and corrections")}</h2><div class='scroll'><table><tr><th>${t("دارو","Medicine")}</th><th>${t("مصرف واقعی","Taken at")}</th><th>${t("مقدار","Amount")}</th><th>${t("نوع","Category")}</th><th>${t("روش / اصلاح","Method / correction")}</th><th>${t("زمان ثبت","Recorded at")}</th></tr>$eventRows</table></div></html>"""
    }
}
