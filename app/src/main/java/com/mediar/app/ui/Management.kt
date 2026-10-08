package com.mediar.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.mediar.app.*
import com.mediar.app.logic.*
import com.mediar.app.reminder.Reminders
import java.time.*
import java.util.UUID

@Composable fun Overview(m: AppModel,a: MainActivity) {
    Text(m.t("نمای کلی مدیر","Manager overview"),style=MaterialTheme.typography.headlineMedium)
    val doses=m.data.today()
    Panel(color=MaterialTheme.colorScheme.primaryContainer){
        Text(m.t("وضعیت امروز","Today's status"),style=MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
            Stat(qty(doses.count{m.data.status(it.id)=="taken"}.toDouble(),m.en),m.t("ثبت شده","Recorded"))
            Stat(qty(doses.count{m.data.status(it.id)=="unknown"}.toDouble(),m.en),m.t("نامشخص","Unknown"))
            Stat(qty(doses.count{m.data.status(it.id)=="declined"}.toDouble(),m.en),m.t("مصرف‌نشده","Not taken"))
        }
        Text(m.t("نامشخص با مصرف‌نشدهٔ اعلام‌شده فرق دارد.","Unknown and explicitly not taken are different."),style=MaterialTheme.typography.bodySmall)
    }
    PermissionPanel(m,a)
    if(m.data.medicines.none{!it.archived})Panel {
        Text(m.t("اولین دارو را اضافه کنید","Add the first medicine"),style=MaterialTheme.typography.titleLarge)
        Text(m.t("نام، مقدار هر نوبت و ساعت‌ها را مرحله‌به‌مرحله تعریف می‌کنیم.","Set the name, per-dose amounts and times step by step."))
        Action(m.t("افزودن دارو","Add medicine"),icon=Icons.Rounded.Add){m.medicineId=null;m.page="edit"}
    }
    val low=m.data.medicines.filter{!it.archived && it.stock<=it.low}
    if(low.isNotEmpty())Panel {
        Text(m.t("موجودی نیاز به بررسی دارد","Inventory needs attention"),style=MaterialTheme.typography.titleLarge)
        low.forEach{med->TextButton(onClick={m.medicineId=med.id;m.page="detail"}){Text(med.name+" • "+qty(med.stock,m.en)+" "+med.unit)}}
    }
    Panel {
        Text(m.t("دسترسی سریع","Quick access"),style=MaterialTheme.typography.titleLarge)
        Action(m.t("داروها و برنامه‌ها","Medicines and plans"),icon=Icons.Rounded.Medication){m.page="medicines"}
        OutlinedButton(onClick={m.page="tags"},modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)){Icon(Icons.Rounded.Nfc,null);Spacer(Modifier.width(8.dp));Text(m.t("جعبه‌ها و اتصال تگ","Boxes and tag linking"))}
        OutlinedButton(onClick={m.page="voice"},modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)){Icon(Icons.Rounded.VolumeUp,null);Spacer(Modifier.width(8.dp));Text(m.t("صدا و جمله‌های شخصی","Speech and personal clips"))}
        TextButton(onClick={m.page="today"}){Text(m.t("نمای مصرف‌کننده","Consumer view"))}
    }
    val events=m.data.events
    Panel {Text(m.t("ثبت‌های قابل بررسی","Records to review"),style=MaterialTheme.typography.titleLarge)
        Text(m.t("${events.count{it.category=="outside" && !it.corrected}} مصرف خارج از برنامه • ${events.count{it.category=="late" && !it.corrected}} ثبت دیرهنگام • ${events.count{it.corrected}} اصلاح", "${events.count{it.category=="outside" && !it.corrected}} outside-plan • ${events.count{it.category=="late" && !it.corrected}} late • ${events.count{it.corrected}} corrected"))
        TextButton(onClick={m.page="history"}){Text(m.t("دیدن تاریخچه","Open history"))}
    }
}
@Composable private fun Stat(value: String,label: String){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(value,style=MaterialTheme.typography.headlineMedium);Text(label,style=MaterialTheme.typography.bodySmall)}}
@Composable fun PermissionPanel(m: AppModel,a: MainActivity) {
    val tick=m.permissionTick
    val notifications=Reminders.allowed(a);val exact=Reminders.exact(a)
    Panel {
        Text(m.t("آمادگی یادآورها","Reminder readiness"),style=MaterialTheme.typography.titleLarge)
        Text((if(notifications)"✓ " else "! ")+m.t(if(notifications)"اعلان مجاز است" else "اعلان مجاز نیست",if(notifications)"Notifications allowed" else "Notifications are blocked"))
        Text((if(exact)"✓ " else "! ")+m.t(if(exact)"آلارم دقیق مجاز است" else "آلارم دقیق مجاز نیست",if(exact)"Exact alarms allowed" else "Exact alarms unavailable"))
        if(!notifications){Text(m.t("بدون این مجوز، اعلان نمایش داده نمی‌شود.","Without permission, notifications cannot appear."),style=MaterialTheme.typography.bodySmall);OutlinedButton(onClick=a::notifications){Text(m.t("مجوز اعلان","Notification permission"))}}
        if(!exact){Text(m.t("یادآور جایگزین ممکن است با تأخیر برسد. برای زمان دقیق، مجوز آلارم را فعال کنید.","Fallback reminders may be delayed. Enable exact alarms for precise timing."),style=MaterialTheme.typography.bodySmall);OutlinedButton(onClick=a::exactAlarms){Text(m.t("مجوز آلارم دقیق","Exact alarm permission"))}}
        Text(m.t("در تنظیمات باتری گوشی، مدیار را محدود نکنید. خاموش‌کردن اجباری اپ، آلارم‌ها را تا بازشدن بعدی متوقف می‌کند.","Avoid restricting Mediar in battery settings. Force-stopping the app stops alarms until it is opened again."),style=MaterialTheme.typography.bodySmall)
    }
}
@Composable fun Medicines(m: AppModel) {
    var showArchived by rememberSaveable{mutableStateOf(false)}
    Text(m.t("داروها","Medicines"),style=MaterialTheme.typography.headlineMedium)
    Action(m.t("افزودن داروی جدید","Add medicine"),icon=Icons.Rounded.Add){m.medicineId=null;m.page="edit"}
    Row(verticalAlignment=Alignment.CenterVertically){Switch(showArchived,{showArchived=it});Spacer(Modifier.width(8.dp));Text(m.t("نمایش بایگانی","Show archived"))}
    val meds=m.data.medicines.filter{showArchived || !it.archived}
    if(meds.isEmpty())Panel{Text(m.t("هنوز دارویی اضافه نشده","No medicines added yet"))}
    meds.forEach {med->Panel {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Rounded.Medication,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(12.dp));Text(med.name,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);if(med.archived)Badge(m.t("بایگانی","Archived"))}
        Text(qty(med.stock,m.en)+" "+med.unit+" • "+m.t("موجودی","in stock"))
        val plan=m.data.plans.lastOrNull{it.medicine==med.id && it.effectiveTo==null}
        if(plan!=null)Text(planDescription(plan,m),style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={m.medicineId=med.id;m.page="detail"},modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)){Text(m.t("جزئیات و برنامه","Details and plan"))}
    }}
}
fun planDescription(p: Plan,m: AppModel): String {
    val mode=when(p.mode){"weekly"->m.t("روزهای منتخب هفته","Selected weekdays");"interval"->m.t("هر ${digits(p.interval.toString(),m.en)} روز","Every ${p.interval} days");"cycle"->m.t("${digits(p.on.toString(),m.en)} روز مصرف، ${digits(p.off.toString(),m.en)} روز استراحت","${p.on} days on, ${p.off} days off");else->m.t("هر روز","Daily")}
    return (if(p.paused)m.t("متوقف • ","Paused • ") else "")+mode+" • "+p.slots.joinToString("، "){digits(it.time,m.en)}
}
@Composable fun MedicineDetail(m: AppModel) {
    val med=m.data.medicines.firstOrNull{it.id==m.medicineId}?:return
    var stockDialog by remember{mutableStateOf(false)};var archiveDialog by remember{mutableStateOf(false)}
    var value by remember(med.stock){mutableStateOf(med.stock.coerceAtLeast(0.0).toString())};var reason by remember{mutableStateOf("")}
    val p=m.data.plans.lastOrNull{it.medicine==med.id && it.effectiveTo==null}
    Panel(color=MaterialTheme.colorScheme.primaryContainer){Text(med.name,style=MaterialTheme.typography.headlineMedium);if(med.note.isNotBlank())Text(med.note)
        Text(m.t("موجودی فعلی","Current stock"),style=MaterialTheme.typography.labelLarge);Text(qty(med.stock,m.en)+" "+med.unit,style=MaterialTheme.typography.headlineMedium)
        if(med.stock<=med.low)Badge(m.t("موجودی کم است","Low stock"))
        if(p!=null){val days=Schedule.daysOfStock(med.stock,p,LocalDate.now(),ZoneId.systemDefault());Text(days?.let{m.t("بر اساس برنامه، حدود ${digits(it.toString(),m.en)} روز باقی است.","About $it days remain based on the plan.")}?:m.t("تخمین موجودی برای برنامهٔ متوقف یا طولانی در دسترس نیست.","Estimate unavailable for paused or long plans."),style=MaterialTheme.typography.bodySmall)}
        OutlinedButton(onClick={stockDialog=true}){Text(m.t("اصلاح / افزایش موجودی","Adjust / refill stock"))}
    }
    if(p!=null)Panel {
        Text(m.t("برنامهٔ مصرف","Consumption plan"),style=MaterialTheme.typography.titleLarge);Text(planDescription(p,m))
        p.slots.forEach{s->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(digits(s.time,m.en));Text(qty(s.amount,m.en)+" "+med.unit)}}
        Text(m.t("شروع میلادی: ${p.start}${p.end?.let{" • پایان: $it"}?:""}","Starts: ${p.start}${p.end?.let{" • ends: $it"}?:""}"),style=MaterialTheme.typography.bodySmall)
    }
    Action(m.t("ویرایش دارو و برنامه","Edit medicine and plan"),enabled=!med.archived,icon=Icons.Rounded.Edit){m.page="edit"}
    OutlinedButton(onClick={m.confirmation=Confirmation(outside=med)},enabled=!med.archived,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)){Text(m.t("ثبت دستی خارج از برنامه","Manual outside-plan record"))}
    TextButton(onClick={archiveDialog=true}){Text(if(med.archived)m.t("بازگرداندن از بایگانی","Restore from archive") else m.t("بایگانی دارو","Archive medicine"))}
    if(stockDialog)AlertDialog(onDismissRequest={stockDialog=false},title={Text(m.t("موجودی واقعی را وارد کنید","Enter actual stock"))},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Entry(value,{value=ascii(it)},m.t("مقدار فعلی (${med.unit})","Current amount (${med.unit})"),numeric=true);Entry(reason,{reason=it},m.t("علت؛ مثلاً خرید بستهٔ تازه","Reason, e.g. a refill"))}},confirmButton={TextButton(onClick={val n=value.toDoubleOrNull();if(n!=null && n>=0 && reason.isNotBlank()){m.inventory(med.id,n,reason);stockDialog=false}else m.toast=m.t("مقدار و علت معتبر وارد کنید","Enter a valid amount and reason")}){Text(m.t("ذخیره","Save"))}},dismissButton={TextButton(onClick={stockDialog=false}){Text(m.t("لغو","Cancel"))}})
    if(archiveDialog)AlertDialog(onDismissRequest={archiveDialog=false},title={Text(if(med.archived)m.t("بازگرداندن دارو؟","Restore medicine?") else m.t("بایگانی دارو؟","Archive medicine?"))},text={Text(m.t("تاریخچه حفظ می‌شود. داروی بایگانی‌شده یادآور جدید نمی‌گیرد.","History is kept. Archived medicines do not receive new reminders."))},confirmButton={TextButton(onClick={m.archive(med.id,!med.archived);archiveDialog=false;m.page="medicines"}){Text(m.t("تأیید","Confirm"))}},dismissButton={TextButton(onClick={archiveDialog=false}){Text(m.t("لغو","Cancel"))}})
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun History(m: AppModel,a: MainActivity) {
    var days by rememberSaveable{mutableIntStateOf(7)};var kind by rememberSaveable{mutableStateOf("all")};var limit by rememberSaveable{mutableIntStateOf(40)}
    var correcting by remember{mutableStateOf<Event?>(null)};var reason by remember{mutableStateOf("")}
    Text(m.t("تاریخچه و اصلاح","History and corrections"),style=MaterialTheme.typography.headlineMedium)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(1,7,30).forEach{n->FilterChip(days==n,{days=n},label={Text(m.t("${digits(n.toString(),m.en)} روز","$n days"))})}}
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("all" to m.t("همه","All"),"outside" to m.t("خارج از برنامه","Outside plan"),"corrected" to m.t("اصلاح‌شده","Corrected"),"unknown" to m.t("نامشخص","Unknown")).forEach{(id,label)->FilterChip(kind==id,{kind=id;limit=40},label={Text(label)})}}
    val from=LocalDate.now().minusDays((days-1).toLong())
    val events=m.data.events.filter{Instant.ofEpochMilli(it.takenAt).atZone(ZoneId.systemDefault()).toLocalDate()>=from && (kind=="all" || (kind=="corrected" && it.corrected) || (kind=="outside" && it.category=="outside"))}
    val expected=m.data.expected.filter{!it.obsolete && it.date>=from && it.date<=LocalDate.now() && m.data.status(it.id)!="taken"}
    if(kind in setOf("unknown","all")) {
        if(expected.isNotEmpty())Text(m.t("نوبت‌های بدون ثبت مصرف","Doses without a consumption record"),style=MaterialTheme.typography.titleLarge)
        expected.filter{kind!="unknown" || m.data.status(it.id)=="unknown"}.take(limit).forEach{e->Panel {
            Text(e.name,style=MaterialTheme.typography.titleMedium);Text(datetime(e.due,m.en));Badge(if(m.data.status(e.id)=="declined")m.t("مصرف‌نشدهٔ اعلام‌شده","Explicitly not taken") else m.t("نامشخص؛ مصرف‌نشدن قطعی نیست","Unknown; not definitely missed"))
            if(m.data.status(e.id)=="declined")TextButton(onClick={m.undoDecline(e.id)}){Text(m.t("برگرداندن به نامشخص","Restore unknown"))}
            else TextButton(onClick={m.review(e)}){Text(m.t("ثبت دستی این نوبت","Manually record this dose"))}
        }}
    }
    if(kind!="unknown")events.take(limit).forEach{event->Panel {
        Text(event.name,style=MaterialTheme.typography.titleMedium);Text(datetime(event.takenAt,m.en));Text(qty(event.amount,m.en)+" "+event.unit)
        Badge(if(event.corrected)m.t("ثبت اصلاح‌شده","Corrected record") else when(event.category){"early"->m.t("زودتر از برنامه","Early");"late"->m.t("با تأخیر","Late");"outside"->m.t("خارج از برنامه","Outside plan");else->m.t("طبق برنامه","Scheduled")})
        Text(m.t("روش: ${event.method} • ثبت در اپ: ","Method: ${event.method} • recorded: ")+datetime(event.recordedAt,m.en),style=MaterialTheme.typography.bodySmall)
        if(event.corrected)Text(event.correction,style=MaterialTheme.typography.bodySmall)
        else TextButton(onClick={correcting=event;reason=""}){Text(m.t("این ثبت اشتباه بوده • اصلاح","This record was incorrect • correct"))}
    }}
    if(events.size>limit || expected.size>limit)TextButton(onClick={limit+=40}){Text(m.t("نمایش بیشتر","Show more"))}
    OutlinedButton(onClick=a::report,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)){Icon(Icons.Rounded.IosShare,null);Spacer(Modifier.width(8.dp));Text(m.t("گزارش خوانای ۳۰ روز • ذخیره و اشتراک","Readable 30-day report • save and share"))}
    if(correcting!=null)AlertDialog(onDismissRequest={correcting=null},title={Text(m.t("اصلاح ثبت اشتباه","Correct an incorrect record"))},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text(m.t("ثبت اصلی در تاریخچه می‌ماند و موجودی برمی‌گردد. سپس می‌توانید ثبت درست را جداگانه وارد کنید.","The original remains in history and inventory is restored. Then add a correct record separately."));Entry(reason,{reason=it},m.t("علت اصلاح","Reason for correction"),multi=true)}},confirmButton={TextButton(onClick={if(reason.isNotBlank()){m.correct(correcting!!.id,reason);correcting=null}},enabled=!m.busy && reason.isNotBlank()){Text(m.t("تأیید اصلاح","Confirm correction"))}},dismissButton={TextButton(onClick={correcting=null}){Text(m.t("لغو","Cancel"))}})
}
