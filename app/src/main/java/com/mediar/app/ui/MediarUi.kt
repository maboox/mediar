package com.mediar.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.mediar.app.*
import com.mediar.app.logic.*
import com.mediar.app.reminder.Reminders
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MediarUi(m: AppModel,a: MainActivity) {
    val systemDark=isSystemInDarkTheme()
    val dark=when(m.data.settings["theme"]){"dark"->true;"light"->false;else->systemDark}
    val snackbar=remember {SnackbarHostState()}
    LaunchedEffect(m.toast){if(m.toast.isNotBlank()){val msg=m.toast;m.toast="";snackbar.showSnackbar(msg)}}
    MediarTheme(m.en,dark) {
        CompositionLocalProvider(LocalLayoutDirection provides if(m.en)LayoutDirection.Ltr else LayoutDirection.Rtl) {
            if(!m.ready){Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),contentAlignment=Alignment.Center){CircularProgressIndicator()};return@CompositionLocalProvider}
            val hasPin=m.data.settings["pin_v3"]?.isNotEmpty()==true || m.data.settings["pin_hash"]?.isNotEmpty()==true
            val welcome=!hasPin && m.page !in setOf("new_pin","recovery")
            val adminPage=m.admin && m.page !in setOf("today","scan","help")
            BackHandler(enabled=m.page!="today"){a.cancelWrite();m.confirmation=null;m.page=if(adminPage && m.page!="admin")"admin" else "today"}
            Scaffold(
                containerColor=MaterialTheme.colorScheme.background,
                snackbarHost={SnackbarHost(snackbar)},
                topBar={if(!welcome)Column {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                        if(m.page!="today" && m.page!="admin")IconButton(onClick={a.cancelWrite();m.page=if(adminPage)"admin" else "today"}){Icon(Icons.AutoMirrored.Rounded.ArrowBack,m.t("بازگشت","Back"))}
                        Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(15.dp)){Icon(Icons.Rounded.LocalPharmacy,null,Modifier.padding(10.dp).size(24.dp),tint=MaterialTheme.colorScheme.primary)}
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)){Text(m.t("مدیار","Mediar"),style=MaterialTheme.typography.titleLarge);Text(m.t("همراه برنامهٔ دارو","Your medication companion"),style=MaterialTheme.typography.bodySmall)}
                        IconButton(onClick={if(m.admin)m.lock() else m.requestAdmin()}){Icon(if(m.admin)Icons.Rounded.LockOpen else Icons.Rounded.Lock,m.t(if(m.admin)"خروج مدیر" else "ورود مدیر",if(m.admin)"Lock management" else "Manager login"))}
                    }
                    if(m.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                }},
                bottomBar={if(!welcome && m.page !in setOf("new_pin","recovery","pin","edit"))NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {
                    if(adminPage){
                        NavigationBarItem(m.page=="admin",{m.page="admin"},{Icon(Icons.Rounded.Dashboard,null)},label={Text(m.t("نمای کلی","Overview"))})
                        NavigationBarItem(m.page in setOf("medicines","detail"),{m.page="medicines"},{Icon(Icons.Rounded.Medication,null)},label={Text(m.t("داروها","Medicines"))})
                        NavigationBarItem(m.page=="history",{m.page="history"},{Icon(Icons.Rounded.History,null)},label={Text(m.t("تاریخچه","History"))})
                        NavigationBarItem(m.page in setOf("settings","voice","backup","tags"),{m.page="settings"},{Icon(Icons.Rounded.Settings,null)},label={Text(m.t("تنظیمات","Settings"))})
                    } else {
                        NavigationBarItem(m.page=="today",{m.page="today"},{Icon(Icons.Rounded.Today,null)},label={Text(m.t("امروز","Today"))})
                        NavigationBarItem(m.page=="scan",{m.page="scan"},{Icon(Icons.Rounded.Nfc,null)},label={Text(m.t("اسکن دارو","Scan medicine"))})
                        NavigationBarItem(m.page=="help",{m.page="help"},{Icon(Icons.Rounded.HelpOutline,null)},label={Text(m.t("راهنما","Help"))})
                    }
                }}
            ) {padding->
                Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter) {
                    Column(Modifier.widthIn(max=720.dp).fillMaxHeight().verticalScroll(rememberScrollState()).imePadding().padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        if(welcome)Welcome(m)
                        else when(m.page){
                            "today"->Today(m)
                            "scan"->Scan(m,a)
                            "help"->Help(m)
                            "pin","new_pin"->PinPage(m)
                            "recovery"->RecoveryPage(m,a)
                            "admin"->if(m.admin)Overview(m,a) else PinPage(m)
                            "medicines"->if(m.admin)Medicines(m) else PinPage(m)
                            "detail"->if(m.admin)MedicineDetail(m) else PinPage(m)
                            "edit"->if(m.admin)MedicineEditor(m,a) else PinPage(m)
                            "history"->if(m.admin)History(m,a) else PinPage(m)
                            "settings"->if(m.admin)SettingsPage(m,a) else PinPage(m)
                            "voice"->if(m.admin)VoicePage(m,a) else PinPage(m)
                            "tags"->if(m.admin)TagsPage(m,a) else PinPage(m)
                            "backup"->if(m.admin)BackupPage(m,a) else PinPage(m)
                            else->Today(m)
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                m.confirmation?.let {ConfirmDialog(m,a,it)}
            }
        }
    }
}
@Composable private fun Welcome(m: AppModel) {
    Spacer(Modifier.height(32.dp))
    Panel(color=MaterialTheme.colorScheme.primaryContainer){
        Icon(Icons.Rounded.LocalPharmacy,null,Modifier.size(64.dp),tint=MaterialTheme.colorScheme.primary)
        Text(m.t("مدیار؛ یک همراه آرام","Mediar, a calm companion"),style=MaterialTheme.typography.headlineLarge)
        Text(m.t("برنامه را یک بار تنظیم کنید. هر روز، زمان دارو را ببینید و مصرف را آگاهانه ثبت کنید.","Set the plan once. See medication times each day and intentionally record consumption."))
    }
    Panel {
        Text(m.t("سه کار ساده","Three simple steps"),style=MaterialTheme.typography.titleLarge)
        Text(m.t("۱. مدیر دارو و ساعت‌ها را تعریف می‌کند.\n۲. مصرف‌کننده گوشی را به جعبه می‌زند؛ پنجره‌ای باز می‌شود و وضعیت را با صدا می‌گوید.\n۳. بعد از خوردن، با تماس دوم یا «خوردم» مصرف ثبت می‌شود.","1. The manager adds medicines and times.\n2. Scan a tag or open a dose.\n3. Record with ‘I took it’ or a separate second tap."))
        Action(m.t("شروع راه‌اندازی","Set up Mediar"),icon=Icons.Rounded.ArrowForward){m.page="new_pin"}
        Text(m.t("کاملاً محلی • بدون حساب و اینترنت","Local • No account or internet"),style=MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick={m.settings("language",if(m.en)"fa" else "en")}){Text(if(m.en)"فارسی" else "English")}
}
@Composable private fun Today(m: AppModel) {
    var now by remember{mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit){while(true){delay(30_000);now=System.currentTimeMillis();if(LocalDate.now()!=m.data.today().firstOrNull()?.date)m.refresh()}}
    val list=m.data.today();val taken=list.count{m.data.status(it.id)=="taken"}
    Surface(shape=RoundedCornerShape(30.dp),color=Color.Transparent) {
        Row(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Navy,Violet))).padding(24.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(m.t("برنامهٔ امروز","Today's plan"),style=MaterialTheme.typography.headlineMedium,color=Color.White)
                Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE، d MMMM",if(m.en)Locale.ENGLISH else Locale("fa"))),color=Color.White.copy(alpha=.85f),style=MaterialTheme.typography.bodySmall)
                Text(digits("$taken / ${list.size}",m.en)+m.t(" نوبت ثبت شده"," doses recorded"),color=Gold,style=MaterialTheme.typography.titleMedium)
            }
            Box(Modifier.size(72.dp),contentAlignment=Alignment.Center){
                CircularProgressIndicator(progress={if(list.isEmpty())0f else taken.toFloat()/list.size},modifier=Modifier.fillMaxSize(),color=Gold,trackColor=Color.White.copy(alpha=.2f),strokeWidth=6.dp)
                Icon(Icons.Rounded.Favorite,null,Modifier.size(28.dp),tint=Color.White)
            }
        }
    }
    val next=list.firstOrNull{m.data.status(it.id)=="unknown" && it.due>=now} ?: list.firstOrNull{m.data.status(it.id)=="unknown"}
    if(next!=null)Panel(color=MaterialTheme.colorScheme.primaryContainer){
        Text(m.t(if(next.due>now)"نوبت پیش رو" else "این نوبت هنوز ثبت نشده",if(next.due>now)"Coming up" else "This dose is unrecorded"),style=MaterialTheme.typography.labelLarge)
        Text(next.name,style=MaterialTheme.typography.headlineMedium)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(qty(next.amount,m.en)+" "+next.unit);Text(clock(next.due,m.en),fontWeight=FontWeight.Bold)}
        Text(if(next.due>now)m.t("${duration(next.due-now,m.en)} تا زمان برنامه","Due in ${duration(next.due-now,m.en)}") else m.t("زمان برنامه ${duration(now-next.due,m.en)} گذشته؛ وضعیت هنوز نامشخص است.","Due ${duration(now-next.due,m.en)} ago; status is unknown."),style=MaterialTheme.typography.bodySmall)
        Action(m.t("بررسی و ثبت مصرف","Review and record"),enabled=!m.busy,icon=Icons.Rounded.CheckCircleOutline){m.review(next)}
    }
    if(list.isEmpty())Panel {
        Icon(Icons.Rounded.EventAvailable,null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.primary)
        Text(m.t("امروز نوبتی تعریف نشده","No doses planned today"),style=MaterialTheme.typography.titleLarge)
        Text(m.t("اگر دارویی باید اضافه شود، از مدیر بخواهید برنامه را تنظیم کند.","Ask the manager to add a plan if needed."))
        OutlinedButton(onClick=m::requestAdmin,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(m.t("ورود مدیر","Manager login"))}
    } else {
        Text(m.t("همهٔ نوبت‌های امروز","All today's doses"),style=MaterialTheme.typography.titleLarge)
        list.forEach{DoseCard(m,it)}
    }
}
@Composable fun DoseCard(m: AppModel,e: Expected) {
    val state=m.data.status(e.id)
    Panel {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.primaryContainer){Icon(if(state=="taken")Icons.Rounded.CheckCircle else if(state=="declined")Icons.Rounded.RemoveCircleOutline else Icons.Rounded.Medication,null,Modifier.padding(12.dp).size(26.dp),tint=MaterialTheme.colorScheme.primary)}
            Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(e.name,style=MaterialTheme.typography.titleMedium);Text(qty(e.amount,m.en)+" "+e.unit,style=MaterialTheme.typography.bodyMedium)}
            Text(clock(e.due,m.en),style=MaterialTheme.typography.titleMedium)
        }
        Badge(when(state){"taken"->m.t("مصرف ثبت شده","Recorded");"declined"->m.t("مصرف‌نشدن اعلام شده","Explicitly not taken");else->m.t("هنوز ثبت نشده • نامشخص","Unrecorded • unknown")})
        when(state){
            "taken"->Text(m.t("زمان واقعی مصرف: ","Actual consumption: ")+m.data.event(e.id)?.let{datetime(it.takenAt,m.en)}.orEmpty(),style=MaterialTheme.typography.bodySmall)
            "unknown"->OutlinedButton(onClick={m.review(e)},enabled=!m.busy,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),shape=RoundedCornerShape(16.dp)){Text(m.t("بررسی / خوردم","Review / I took it"))}
            "declined"->if(m.admin)TextButton(onClick={m.undoDecline(e.id)}){Text(m.t("برگرداندن به نامشخص","Restore unknown status"))}
        }
    }
}
@Composable private fun Scan(m: AppModel,a: MainActivity) {
    val tick=m.permissionTick
    val b=m.binding
    Panel(color=MaterialTheme.colorScheme.primaryContainer){
        Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Rounded.Nfc,null,Modifier.size(38.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(12.dp));Text(m.t("تگ را نزدیک گوشی بگیرید","Bring the tag near your phone"),style=MaterialTheme.typography.titleLarge)}
        Text(m.t("لازم نیست برنامه باز باشد. تماس اول می‌گوید وقت دارو هست یا نه. بعد از خوردن، تا ۱۵ دقیقه دوباره گوشی را به همان تگ بزنید یا دکمهٔ «خوردم» را بزنید.","The app does not need to be open. The first tap tells you whether it is time. After taking it, tap the same tag again within 15 minutes, or press ‘I took it’."))
        if(!a.nfc.exists())Text(m.t("این گوشی NFC ندارد؛ از صفحهٔ امروز استفاده کنید.","This phone has no NFC. Use the Today page."))
        else if(!a.nfc.enabled())OutlinedButton(onClick={a.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))}){Text(m.t("روشن‌کردن NFC","Enable NFC"))}
    }
    if(b==null)return
    if(b.kind=="medicine") {
        val med=m.data.medicines.firstOrNull{it.id==b.target}?:return
        val doses=m.data.today().filter{it.medicine==med.id}
        val last=m.data.events.firstOrNull{it.medicine==med.id && !it.corrected}
        Panel {
            Text(med.name,style=MaterialTheme.typography.headlineMedium)
            if(med.archived)Badge(m.t("دارو بایگانی شده","Archived medicine"))
            Text(m.t("آخرین مصرف ثبت‌شده","Last recorded consumption"),style=MaterialTheme.typography.labelLarge)
            Text(last?.let{datetime(it.takenAt,m.en)}?:m.t("هنوز مصرفی ثبت نشده","No consumption recorded yet"))
            if(doses.size>1)Text(m.t("این دارو چند نوبت دارد؛ با اسکن تگ، نوبتی که الان وقتش است انتخاب می‌شود.","This medicine has multiple doses; scanning the tag picks the dose that is due now."),style=MaterialTheme.typography.bodySmall)
            if(doses.isEmpty())Text(m.t("برای امروز نوبتی تعریف نشده. ثبت خارج از برنامه، نوبت بعد را تکمیل نمی‌کند.","No dose is planned today. Outside-plan records do not complete the next dose."))
        }
        doses.forEach{DoseCard(m,it)}
        if(!med.archived)OutlinedButton(onClick={m.confirmation=Confirmation(outside=med)},modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)){Text(m.t("ثبت مصرف خارج از برنامه / اضافه","Record outside-plan / extra consumption"))}
    } else if(b.kind=="group")GroupScan(m,b.target)
}
@Composable private fun GroupScan(m: AppModel,id: String) {
    val doses=m.data.today().filter{it.group==id}
    val chosen=remember(id){mutableStateMapOf<String,Boolean>()}
    Panel {
        Text(m.data.groups.firstOrNull{it.id==id}?.name.orEmpty(),style=MaterialTheme.typography.headlineMedium)
        Text(m.t("فقط داروهایی را که مصرف کرده‌اید انتخاب کنید. ثبت هر دارو جدا نگه داشته می‌شود.","Select only medicines you took. Each medicine is recorded separately."))
        if(doses.isEmpty())Text(m.t("نوبتی برای این جعبه در امروز نیست","No doses for this box today"))
        doses.forEach{e->val pending=m.data.status(e.id)=="unknown"
            Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clickable(enabled=pending){chosen[e.id]=chosen[e.id]!=true},verticalAlignment=Alignment.CenterVertically){
                Checkbox(chosen[e.id]==true && pending,{chosen[e.id]=it},enabled=pending)
                Column(Modifier.weight(1f)){Text(e.name,style=MaterialTheme.typography.titleMedium);Text(qty(e.amount,m.en)+" "+e.unit+" • "+clock(e.due,m.en),style=MaterialTheme.typography.bodySmall)}
                if(!pending)Icon(if(m.data.status(e.id)=="taken")Icons.Rounded.CheckCircle else Icons.Rounded.RemoveCircleOutline,m.t("قبلاً ثبت شده","Previously recorded"),tint=MaterialTheme.colorScheme.primary)
            }
        }
        TextButton(onClick={doses.filter{m.data.status(it.id)=="unknown"}.forEach{chosen[it.id]=true}}){Text(m.t("انتخاب همهٔ نوبت‌های ثبت‌نشده","Select all unrecorded doses"))}
        val selected=doses.filter{chosen[it.id]==true && m.data.status(it.id)=="unknown"}
        Action(m.t("تأیید موارد انتخاب‌شده","Confirm selected medicines"),enabled=selected.isNotEmpty() && !m.busy){m.confirmation=Confirmation(doses=selected)}
    }
}
@Composable private fun Help(m: AppModel) {
    Panel {Text(m.t("چطور استفاده کنم؟","How to use Mediar"),style=MaterialTheme.typography.headlineMedium)
        Text(m.t("۱. گوشی را به جعبهٔ دارو بزنید؛ لازم نیست برنامه باز باشد. پنجره‌ای باز می‌شود و می‌گوید الان وقتش هست، زود است یا قبلاً خورده‌اید.\n۲. بعد از خوردن، دوباره گوشی را به همان جعبه بزنید (تا ۱۵ دقیقه) یا «خوردم» را بزنید.\n۳. اگر اشتباه ثبت شد، «اشتباه شد» را بزنید.","1. Tap the phone on the medicine box; the app does not need to be open. A popup says whether it is time, too early, or already taken.\n2. After taking it, tap the same box again (within 15 minutes) or press ‘I took it’.\n3. If it was recorded by mistake, press ‘Mistake’."))
        Text(m.t("برای تگ جعبهٔ چنددارویی، داروهای مصرف‌شده را روی صفحه انتخاب کنید.","For a multi-medicine box tag, select the medicines you took on screen."))
    }
    Panel {Text(m.t("معنی وضعیت‌ها","What statuses mean"),style=MaterialTheme.typography.titleLarge)
        Text(m.t("✓ ثبت شده: مصرف توسط شما تأیید شده.\n؟ نامشخص: برنامه نمی‌داند دارو مصرف شده یا نه.\n− مصرف‌نشده: خودتان صریحاً اعلام کرده‌اید.","✓ Recorded: you confirmed consumption.\n? Unknown: the app does not know whether it was taken.\n− Not taken: you explicitly said so."))
        Text(m.t("مدیار زمان‌بندی تعریف‌شده و ثبت‌های شما را نشان می‌دهد. دربارهٔ تغییر مقدار یا زمان دارو با پزشک یا داروساز صحبت کنید.","Mediar shows your defined schedule and records. Discuss changes to medicine amount or timing with a clinician or pharmacist."),style=MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun PinPage(m: AppModel) {
    val create=m.page=="new_pin"
    var value by rememberSaveable(m.page){mutableStateOf("")};var repeated by rememberSaveable(m.page){mutableStateOf("")};var recovering by rememberSaveable{mutableStateOf(false)}
    Panel {
        Icon(Icons.Rounded.Shield,null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.primary)
        Text(if(create)m.t("PIN مدیریت را بسازید","Create a manager PIN") else m.t("ورود مدیر","Manager login"),style=MaterialTheme.typography.headlineMedium)
        Text(m.t("تنظیم دارو و اصلاح ثبت‌ها فقط در بخش مدیریت انجام می‌شود.","Medicine setup and record corrections are in management."))
        Entry(value,{value=ascii(it).take(if(recovering)40 else 8)},if(recovering)m.t("کد بازیابی","Recovery code") else "PIN",password=!recovering,numeric=!recovering)
        if(create)Entry(repeated,{repeated=ascii(it).filter(Char::isDigit).take(8)},m.t("تکرار PIN","Repeat PIN"),password=true,numeric=true)
        Action(if(create)m.t("ساخت PIN","Create PIN") else m.t("ورود","Continue"),enabled=!m.busy){if(create)m.createPin(value,repeated) else m.unlock(value,recovering)}
        if(!create)TextButton(onClick={recovering=!recovering;value=""}){Text(if(recovering)m.t("ورود با PIN","Use PIN") else m.t("PIN را فراموش کرده‌ام","I forgot my PIN"))}
        Text(m.t("PIN جلوی تغییرات اتفاقی را می‌گیرد؛ جای قفل و رمزگذاری گوشی نیست.","A PIN prevents accidental changes. It does not replace your phone's lock and encryption."),style=MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun RecoveryPage(m: AppModel,a: MainActivity) {
    Panel(color=MaterialTheme.colorScheme.primaryContainer){Text(m.t("کد بازیابی را نگه دارید","Keep your recovery code"),style=MaterialTheme.typography.headlineMedium)
        Text(m.t("اگر PIN فراموش شود، با این کد می‌توانید PIN تازه بسازید. کد را خارج از این گوشی در جای امن نگه دارید.","Use this code to set a new PIN if you forget it. Keep it safely outside this phone."))
        Text(m.recovery,style=MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick={a.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Mediar recovery",m.recovery))}){Text(m.t("کپی کد","Copy code"))}
        Action(m.t("کد را نگه داشتم • ادامه","I've saved the code • continue")){m.page="admin"}
    }
}

@Composable private fun ConfirmDialog(m: AppModel,a: MainActivity,c: Confirmation) {
    var declined by remember(c.request){mutableStateOf(false)}
    AlertDialog(onDismissRequest={if(!m.busy)m.confirmation=null},icon={Icon(if(c.outside!=null)Icons.Rounded.EditNote else Icons.Rounded.Medication,null)},
        title={Text(if(declined)m.t("مصرف نکردم","I did not take it") else if(c.outside!=null)m.t("مصرف خارج از برنامه","Outside-plan consumption") else m.t("تأیید مصرف","Confirm consumption"))},
        text={Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            if(declined)Text(m.t("فقط اگر واقعاً این نوبت را مصرف نکرده‌اید تأیید کنید. صرفاً نبودِ ثبت، وضعیت را نامشخص نگه می‌دارد.","Confirm only if you actually did not take this dose. An absent record simply stays unknown."))
            else {
                c.outside?.let {med->
                    Text(med.name,style=MaterialTheme.typography.titleLarge)
                    Text(m.t("این ثبت به هیچ نوبتی وصل نمی‌شود و برنامهٔ دارو را تغییر نمی‌دهد.","This record is not linked to a planned dose and does not change the plan."))
                    Entry(c.quantity,{m.confirmation=c.copy(quantity=ascii(it))},m.t("مقدار واقعی (${med.unit})","Actual amount (${med.unit})"),numeric=true)
                    m.data.events.firstOrNull{it.medicine==med.id && !it.corrected}?.let {Text(m.t("آخرین مصرف: ","Last consumption: ")+datetime(it.takenAt,m.en),style=MaterialTheme.typography.bodySmall)}
                }
                c.doses.forEach {e->
                    Text(e.name,style=MaterialTheme.typography.titleMedium)
                    Text(qty(e.amount,m.en)+" "+e.unit+" • "+m.t("نوبت ","Due ")+datetime(e.due,m.en))
                    if(c.takenAt<e.due)Badge(m.t("زودتر از برنامه • ${duration(e.due-c.takenAt,m.en)} مانده","Early • due in ${duration(e.due-c.takenAt,m.en)}"))
                    else if(c.takenAt>e.due+30*60_000)Badge(m.t("ثبت با تأخیر","Late record"))
                }
                Text(m.t("فقط پس از مصرف واقعی تأیید کنید.","Confirm only after actual consumption."),fontWeight=FontWeight.Bold)
                Text(m.t("زمان مصرف: ","Consumption time: ")+datetime(c.takenAt,m.en),style=MaterialTheme.typography.bodySmall)
                if(m.admin) {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={pickDate(a,Instant.ofEpochMilli(c.takenAt).atZone(ZoneId.systemDefault()).toLocalDate()){date->val local=Instant.ofEpochMilli(c.takenAt).atZone(ZoneId.systemDefault());m.confirmation=c.copy(takenAt=date.atTime(local.toLocalTime()).atZone(local.zone).toInstant().toEpochMilli())}}){Text(m.t("تاریخ میلادی","Date"))}
                        OutlinedButton(onClick={pickTime(a,Instant.ofEpochMilli(c.takenAt).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))){time->val local=Instant.ofEpochMilli(c.takenAt).atZone(ZoneId.systemDefault());m.confirmation=c.copy(takenAt=local.toLocalDate().atTime(LocalTime.parse(time)).atZone(local.zone).toInstant().toEpochMilli())}}){Text(m.t("ساعت","Time"))}
                    }
                }
                if(c.doses.size==1){
                    TextButton(onClick={declined=true},enabled=!m.busy){Text(m.t("این نوبت را مصرف نکردم","I did not take this dose"))}
                    if(m.data.status(c.doses.first().id)=="unknown")TextButton(onClick={m.snooze(c.doses.first());m.confirmation=null},enabled=!m.busy){Text(m.t("یادآور ۱۵ دقیقه بعد","Remind me in 15 minutes"))}
                }
            }
        }},
        confirmButton={Button(onClick={if(declined)m.decline(c.doses.first()) else m.confirm()},enabled=!m.busy){Text(if(declined)m.t("تأیید مصرف‌نشدن","Confirm not taken") else m.t("خوردم • ثبت مصرف","I took it • record"))}},
        dismissButton={TextButton(onClick={m.confirmation=null},enabled=!m.busy){Text(m.t("بستن","Close"))}})
}
