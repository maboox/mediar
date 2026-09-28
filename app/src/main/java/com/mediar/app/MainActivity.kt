package com.mediar.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.platform.LocalLayoutDirection
import com.mediar.app.audio.Voice
import com.mediar.app.backup.Backup
import com.mediar.app.data.*
import com.mediar.app.logic.*
import com.mediar.app.nfc.NfcService
import com.mediar.app.reminder.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

private val Violet=Color(0xFF5946D9)
private val Deep=Color(0xFF251C74)
private val Canvas=Color(0xFFF2EFFB)
private val Ink=Color(0xFF231D3E)
private val Gold=Color(0xFFFFD572)
private val Stamp=DateTimeFormatter.ofPattern("HH:mm")
private fun human(ms:Long):String {val minutes=(ms/60000).coerceAtLeast(0);return if(minutes<60)"$minutes دقیقه" else "${minutes/60} ساعت و ${minutes%60} دقیقه"}

class MainActivity:ComponentActivity() {
    private lateinit var db:Store
    private lateinit var voice:Voice
    private lateinit var nfc:NfcService
    private var screen by mutableStateOf("today")
    private var selected by mutableStateOf<TagBinding?>(null)
    private var banner by mutableStateOf("")
    private var revision by mutableIntStateOf(0)
    private var admin by mutableStateOf(false)
    private var pendingToken by mutableStateOf("")
    private var backupPass=""
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {uri->if(uri!=null) lifecycleScope.launch(Dispatchers.IO){runCatching { contentResolver.openOutputStream(uri)?.use {it.write(Backup.create(this@MainActivity,db,backupPass.toCharArray()))} }.onSuccess {withContext(Dispatchers.Main){banner="پشتیبان رمزدار ذخیره شد"}}.onFailure {withContext(Dispatchers.Main){banner="خطا در پشتیبان: ${it.message}"}}} }
    private val importFile=registerForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null) lifecycleScope.launch(Dispatchers.IO){runCatching {val bytes=contentResolver.openInputStream(uri)!!.use {stream->val out=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192);while(true){val n=stream.read(chunk);if(n<0)break;require(out.size()+n<=40_000_000) {"Backup too large"};out.write(chunk,0,n)};out.toByteArray()};Backup.restore(this@MainActivity,db,backupPass.toCharArray(),bytes);Reminders.rebuild(this@MainActivity) }.onSuccess {withContext(Dispatchers.Main){revision++;banner="بازیابی انجام شد"}}.onFailure {withContext(Dispatchers.Main){banner="بازیابی انجام نشد: ${it.message}"}}} }
    override fun onCreate(savedInstanceState:Bundle?) {super.onCreate(savedInstanceState);db=Store(this);voice=Voice(this);Reminders.channel(this);nfc=NfcService(this,::readTag,::writeDone)
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),11)
        lifecycleScope.launch(Dispatchers.IO){Reminders.rebuild(this@MainActivity)}
        setContent { MediarApp() }
        consumeIntent(intent)
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);consumeIntent(intent)}
    private fun consumeIntent(intent:Intent?) {if(intent?.action==NfcAdapter.ACTION_NDEF_DISCOVERED){@Suppress("DEPRECATION") val tag=intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG);if(tag!=null)nfc.process(tag)}}
    override fun onResume(){super.onResume();if(::nfc.isInitialized)nfc.start()}
    override fun onPause(){if(::nfc.isInitialized)nfc.stop();super.onPause()}
    override fun onDestroy(){voice.release();db.close();super.onDestroy()}
    private fun writeDone(result:String){if(result=="ok"){db.addTag(pendingToken,writeKind,writeTarget);banner="تگ نوشته و متصل شد";revision++}else banner=result;pendingToken=""}
    private var writeKind="medicine";private var writeTarget=""
    private fun readTag(token:String,second:Boolean) {
        val binding=db.tag(token)
        if(binding==null){banner="تگ ناشناس یا غیرفعال است";return}
        if(selected?.token!=token)selected=binding
        screen="scan";revision++
        if(binding.kind=="today") {voice.speak("گزارش امروز آماده است");return}
        if(second && binding.kind=="group") {screen="groupConfirm";voice.speak("داروهای مصرف شده را تأیید کنید");return}
        if(second && binding.kind=="medicine") {
            val candidates=candidates(binding.target)
            if(candidates.size==1) {confirm(binding.target,candidates.first(),"NFC");return}
            banner="برای ثبت، نوبت را به‌صورت روشن انتخاب کنید"
        }
        if(!second) voice.speak(scanSpeech(binding),if(binding.kind=="medicine")"scan" else "due")
    }
    private fun scanSpeech(b:TagBinding):String {if(b.kind=="group")return "وضعیت جعبهٔ ${db.groups().firstOrNull{it.id==b.target}?.title?:"دارو"} نمایش داده شد"
        val m=db.medicine(b.target)?:return "دارو یافت نشد";val today=todayExpected().filter{it.medicineId==m.id}
        val last=db.doses().firstOrNull{it.medicineId==m.id && !it.corrected}
        return "${m.name}. ${today.joinToString(". "){ "ساعت ${it.due.format(Stamp)}، ${it.quantity} ${m.unit}، ${statusText(it.id)}" }}. ${last?.let{"آخرین ثبت ${Instant.ofEpochMilli(it.takenAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))}"}?:"مصرفی ثبت نشده"}"
    }
    private fun todayExpected():List<Expected> {val zone=ZoneId.systemDefault();val start=LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli();val end=LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();db.materialize(LocalDate.now().minusDays(1),31);return db.expectations(start,end)}
    private fun candidates(medicineId:String):List<Expected> {val now=System.currentTimeMillis();return todayExpected().filter {it.medicineId==medicineId && db.status(it.id)=="unknown" && it.due.toInstant().toEpochMilli()<=now+2*3_600_000L}}
    private fun statusText(id:String)=when(db.status(id)){"taken"->"مصرف ثبت شده";"declined"->"مصرف نشده با اعلام صریح";else->"نامشخص؛ هنوز ثبت نشده"}
    private fun confirm(medicineId:String,e:Expected?,method:String) {
        val m=db.medicine(medicineId)?:return;val result=db.confirm(m.id,e?.id,e?.quantity?:1.0,System.currentTimeMillis(),method)
        banner=if(result.success)"مصرف ثبت شد${if(result.reason=="early")" • زودتر از برنامه" else if(result.reason=="outside")" • خارج از برنامه" else ""}" else "ثبت نشد: ${result.reason}"
        if(result.success) {e?.let {Reminders.cancel(this,it.id)};voice.speak("مصرف ${m.name} ثبت شد","thanks")};revision++
    }
    private fun pinHash(pin:String,salt:String):String {val hash=MessageDigest.getInstance("SHA-256").digest((salt+pin).toByteArray());return hash.joinToString(""){ "%02x".format(it) }}
    private fun randomSalt()=ByteArray(16).also(SecureRandom()::nextBytes).joinToString(""){"%02x".format(it)}
    @Composable private fun MediarApp(){val en=remember { mutableStateOf(getSharedPreferences("ui",0).getBoolean("english",false)) };val dark=remember { mutableStateOf(getSharedPreferences("ui",0).getBoolean("dark",false)) }
        val scheme=if(dark.value) darkColorScheme(primary=Color(0xFFBAABFF),background=Color(0xFF151124),surface=Color(0xFF251F3C)) else lightColorScheme(primary=Violet,secondary=Deep,background=Canvas,surface=Color.White)
        MaterialTheme(colorScheme=scheme) {CompositionLocalProvider(LocalLayoutDirection provides if(en.value) androidx.compose.ui.unit.LayoutDirection.Ltr else androidx.compose.ui.unit.LayoutDirection.Rtl) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Deep,Violet))).padding(20.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)){Text(if(en.value)"Mediar" else "مدیار",color=Color.White,fontSize=30.sp,fontWeight=FontWeight.Bold);Text(if(en.value)"Medication companion" else "همراه برنامهٔ دارو",color=Color.White.copy(alpha=.85f),fontSize=14.sp)}
                    Text("✚",fontSize=30.sp,color=Gold)
                }
                if(banner.isNotBlank()) Surface(color=Gold.copy(alpha=.42f),modifier=Modifier.fillMaxWidth().clickable {banner=""}){Text(banner,Modifier.padding(14.dp),color=Ink,fontSize=16.sp)}
                Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.TopCenter) {Column(Modifier.widthIn(max=640.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(18.dp)) {
                    when(screen){"today"->TodayPage();"scan"->ScanPage();"groupConfirm"->GroupConfirmPage();"pin"->PinPage();"admin"->AdminPage();"medicines"->MedicinePage();"plans"->PlanPage();"tags"->TagPage();"history"->HistoryPage();"voice"->VoicePage();"settings"->SettingsPage(en,dark);"backup"->BackupPage();else->TodayPage()}
                }}
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp),horizontalArrangement=Arrangement.SpaceEvenly){TextButton(onClick={screen="today"}){Text(if(en.value)"Today" else "امروز")};TextButton(onClick={if(admin)screen="admin" else screen="pin"}){Text(if(en.value)"Manage" else "مدیریت")}}
            }
        }}
    }
    @Composable private fun Panel(title:String,body:@Composable ColumnScope.()->Unit){Card(Modifier.fillMaxWidth().padding(bottom=14.dp),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),elevation=CardDefaults.cardElevation(defaultElevation=2.dp)) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text(title,fontSize=21.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurface);body()} } }
    @Composable private fun Big(label:String,onClick:()->Unit){Button(onClick,Modifier.fillMaxWidth().heightIn(min=60.dp),shape=RoundedCornerShape(17.dp)){Text(label,fontSize=19.sp,textAlign=TextAlign.Center)}}
    @Composable private fun Field(value:String,change:(String)->Unit,label:String,numeric:Boolean=false){OutlinedTextField(value,change,Modifier.fillMaxWidth(),label={Text(label)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=if(numeric)KeyboardType.Decimal else KeyboardType.Text))}
    @Composable private fun TodayPage(){val tick=revision;val list=remember(tick){todayExpected()};val doses=remember(tick){db.doses()};Panel("امروز • ${LocalDate.now()}") {Text("اسکن اول فقط وضعیت را اعلام می‌کند. برای ثبت، دکمهٔ خوردم یا تماس دومِ جداگانه را به کار ببرید.",fontSize=17.sp,lineHeight=27.sp);Text("${list.count{db.status(it.id)=="taken"}} ثبت‌شده  •  ${list.count{db.status(it.id)=="unknown"}} نامشخص  •  ${list.count{db.status(it.id)=="declined"}} اعلام مصرف‌نشدن",fontSize=17.sp,color=Violet)}
        if(list.isEmpty())Panel("هنوز نوبتی برای امروز تعریف نشده") {Text("برنامه را از بخش مدیریت اضافه کنید.")}
        list.forEach {e->val med=db.medicine(e.medicineId)?:return@forEach;Panel("${med.name} • ${e.due.format(Stamp)}") {Text("${e.quantity} ${med.unit}  |  ${statusText(e.id)}",fontSize=18.sp);if(db.status(e.id)=="unknown") {Big("خوردم • تأیید مصرف") {confirm(med.id,e,"manual")};TextButton(onClick={db.markNotTaken(e.id);revision++}){Text("صریحاً مصرف نکردم")}} else if(db.status(e.id)=="taken") Text("آخرین ثبت: ${doses.firstOrNull{it.expectedId==e.id && !it.corrected}?.let{Instant.ofEpochMilli(it.takenAt).atZone(ZoneId.systemDefault()).format(Stamp)}?:"—"}")}}
    }
    @Composable private fun ScanPage(){val binding=selected?:return;val tick=revision
        if(binding.kind=="today"){TodayPage();return}
        if(binding.kind=="group"){val group=db.groups().firstOrNull{it.id==binding.target};Panel("جعبهٔ ${group?.title?:"نامشخص"}"){Text("وضعیت هر دارو • برای انتخاب مصرف، تگ را جدا کرده و دوباره نزدیک کنید.",fontSize=17.sp);val list=remember(tick){todayExpected().filter{it.groupId==binding.target}};list.forEach{e->Text("${db.medicine(e.medicineId)?.name} — ${e.quantity} — ${statusText(e.id)}",fontSize=18.sp)};Big("انتخاب داروهای مصرف‌شده") {screen="groupConfirm"}};return}
        val m=db.medicine(binding.target)?:return;val list=remember(tick){todayExpected().filter{it.medicineId==m.id}};val valid=candidates(m.id);val last=db.doses().firstOrNull{it.medicineId==m.id && !it.corrected}
        Panel("${m.name} • وضعیت دارو") {Text("اسکن فقط اطلاع‌رسانی است",color=Violet,fontWeight=FontWeight.Bold);list.forEach {e->Text("${e.due.format(Stamp)} • ${e.quantity} ${m.unit} • ${statusText(e.id)}",fontSize=20.sp,lineHeight=30.sp)};Text("آخرین مصرف ثبت‌شده: ${last?.let{Instant.ofEpochMilli(it.takenAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))}?:"ندارد"}",fontSize=17.sp);val next=Schedule.horizon(db.plans().filter{it.medicineId==m.id},LocalDate.now(),14,ZoneId.systemDefault()).firstOrNull{it.due.toInstant().toEpochMilli()>System.currentTimeMillis()};Text("تا نوبت بعد: ${next?.let{human(it.due.toInstant().toEpochMilli()-System.currentTimeMillis())}?:"برنامه‌ریزی نشده"}")
            valid.forEach {e->Big("خوردم • نوبت ${e.due.format(Stamp)}") {confirm(m.id,e,"manual")}}
            if(valid.isEmpty()) { var outsideConfirm by remember { mutableStateOf(false) };var outsideQty by remember { mutableStateOf("1") };Text("نوبت قابل تأییدی در بازهٔ دو ساعت آینده وجود ندارد. ثبت خارج از برنامه، نوبت بعدی را تکمیل نمی‌کند.",fontSize=16.sp);Field(outsideQty,{outsideQty=it},"مقدار واقعی مصرف",true);if(!outsideConfirm)Big("درخواست ثبت خارج از برنامه") { outsideConfirm=true } else Big("تأیید روشن • مصرف خارج از برنامه") { val q=outsideQty.toDoubleOrNull();if(q==null || q<=0)banner="مقدار معتبر وارد کنید" else { val result=db.confirm(m.id,null,q,System.currentTimeMillis(),"manual");banner=if(result.success)"مصرف خارج از برنامه ثبت شد" else result.reason;revision++;outsideConfirm=false } } }
        }
    }
    @Composable private fun GroupConfirmPage(){val binding=selected?:return;val list=todayExpected().filter{it.groupId==binding.target && db.status(it.id)=="unknown"};val checked=remember(binding.token,revision){mutableStateMapOf<String,Boolean>()};Panel("تأیید مصرف جعبه") {Text("تماس دوم فقط این صفحه را باز کرده است. هر دارو جداگانه ثبت می‌شود.",fontSize=18.sp);if(list.isEmpty())Text("نوبت ثبت‌نشده‌ای در این گروه نیست.")
        list.forEach {e->val m=db.medicine(e.medicineId);Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked[e.id]==true,{checked[e.id]=it});Text("${m?.name} • ${e.quantity} ${m?.unit} • ${e.due.format(Stamp)}",fontSize=18.sp)}}
        if(list.isNotEmpty()){Big("همه را خوردم • ثبت هر دارو") {list.forEach{confirm(it.medicineId,it,"NFC")};screen="scan"};Big("فقط موارد انتخاب‌شده را ثبت کن") {list.filter{checked[it.id]==true}.forEach{confirm(it.medicineId,it,"manual")};screen="scan"}}
    } }
    @Composable private fun PinPage(){val saved=db.get("pin_hash");var pin by remember{mutableStateOf("")};var confirm by remember{mutableStateOf("")};Panel(if(saved==null)"ساخت PIN مدیریت" else "ورود مدیر") {Text("PIN چهار تا هشت رقمی، فقط جلوی تغییرات اتفاقی را می‌گیرد؛ رمزگذاری داده‌های گوشی نیست.",fontSize=16.sp);OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(8)},label={Text("PIN")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),modifier=Modifier.fillMaxWidth());if(saved==null)OutlinedTextField(confirm,{confirm=it.filter(Char::isDigit).take(8)},label={Text("تکرار PIN")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth());Big(if(saved==null)"ساخت PIN" else "ورود") {if(pin.length<4){banner="PIN باید حداقل چهار رقم باشد";return@Big};if(saved==null){if(pin!=confirm){banner="تکرار PIN یکسان نیست";return@Big};val salt=randomSalt();db.set("pin_salt",salt);db.set("pin_hash",pinHash(pin,salt));admin=true;screen="admin"}else if(pinHash(pin,db.get("pin_salt")?:"")==saved){admin=true;screen="admin"}else banner="PIN نادرست است"};Text("اگر PIN فراموش شود، بازیابی با فایل پشتیبان و گذرواژهٔ آن ممکن است؛ بدون آن داده‌ها قابل بازیابی تضمینی نیستند.",fontSize=14.sp)} }
    @Composable private fun AdminPage(){if(!admin){screen="pin";return};val tick=revision;val list=remember(tick){todayExpected()};Panel("داشبورد مدیر") {Text("امروز: ${list.count{db.status(it.id)=="taken"}} تأیید، ${list.count{db.status(it.id)=="unknown"}} نامشخص، ${list.count{db.status(it.id)=="declined"}} مصرف‌نشده با اعلام صریح",fontSize=17.sp);val outside=db.doses().count{it.category=="outside" && !it.corrected};Text("مصرف خارج از برنامه: $outside • اصلاحات: ${db.corrections().size} • نوبت‌های گذشتهٔ هنوز نامشخص: ${list.count{db.status(it.id)=="unknown" && it.due.toInstant().toEpochMilli()<System.currentTimeMillis()}}");db.medicines().filter{!it.archived}.forEach {m->val perDay=list.filter{it.medicineId==m.id}.sumOf{it.quantity};Text("${m.name}: موجودی ${"%.1f".format(m.stock)} ${m.unit}${if(m.stock<=m.low)" • کمبود" else ""}${if(perDay>0)" • تخمین ${"%.0f".format(m.stock/perDay)} روز (با الگوی امروز)" else ""}")}}
        listOf("مدیریت داروها" to "medicines","برنامه‌های مصرف" to "plans","گروه‌ها و نوشتن NFC" to "tags","تاریخچه و اصلاح" to "history","ضبط صدا" to "voice","تنظیمات و مجوزها" to "settings","پشتیبان رمزدار" to "backup").forEach { (title,dest)->Panel(title) {Big("باز کردن") {screen=dest}}};TextButton(onClick={admin=false;screen="today"}){Text("قفل‌کردن مدیریت")}
    }
    @Composable private fun MedicinePage(){var name by remember{mutableStateOf("")};var note by remember{mutableStateOf("")};var unit by remember{mutableStateOf("عدد")};var stock by remember{mutableStateOf("0")};var low by remember{mutableStateOf("5")};Panel("افزودن دارو") {Field(name,{name=it},"نام دارو");Field(note,{note=it},"توضیح اختیاری");Field(unit,{unit=it},"واحد");Field(stock,{stock=it},"موجودی اولیه",true);Field(low,{low=it},"آستانهٔ کمبود",true);Big("ذخیرهٔ دارو") {runCatching {db.addMedicine(name,note,unit,stock.toDouble(),low.toDouble());revision++;name="";note="";banner="دارو افزوده شد"}.onFailure{banner="مقدار نامعتبر"}}};db.medicines().forEach {m->Panel(m.name){Text("${m.note} • موجودی ${m.stock} ${m.unit}${if(m.archived)" • بایگانی" else ""}");var editing by remember(m.id){mutableStateOf(false)};if(editing){var newName by remember(m.id){mutableStateOf(m.name)};var newNote by remember(m.id){mutableStateOf(m.note)};var newUnit by remember(m.id){mutableStateOf(m.unit)};var newLow by remember(m.id){mutableStateOf(m.low.toString())};Field(newName,{newName=it},"نام");Field(newNote,{newNote=it},"توضیح");Field(newUnit,{newUnit=it},"واحد");Field(newLow,{newLow=it},"آستانه",true);Big("ذخیرهٔ تغییر دارو") {runCatching {db.editMedicine(m.id,newName,newNote,newUnit,newLow.toDouble());editing=false;revision++}.onFailure{banner="مقدار نامعتبر"}}}else TextButton(onClick={editing=true}){Text("ویرایش مشخصات")};var amount by remember(m.id){mutableStateOf("")};Field(amount,{amount=it},"تغییر موجودی؛ + یا −",true);TextButton(onClick={runCatching {db.adjustStock(m.id,amount.toDouble(),"manual");revision++;amount=""}.onFailure{banner="عدد نامعتبر"}}){Text("ثبت تغییر موجودی")};if(!m.archived)TextButton(onClick={db.archive(m.id);revision++}){Text("بایگانی")}}} }
    @Composable private fun PlanPage(){val meds=db.medicines().filter{!it.archived};if(meds.isEmpty()){Panel("ابتدا دارو ثبت کنید"){};return};var med by remember{mutableStateOf(meds.first().id)};var mode by remember{mutableStateOf("daily")};var start by remember{mutableStateOf(LocalDate.now().toString())};var end by remember{mutableStateOf("")};var weekdays by remember{mutableStateOf("1,2,3,4,5,6,7")};var every by remember{mutableStateOf("2")};var on by remember{mutableStateOf("21")};var off by remember{mutableStateOf("7")};var slots by remember{mutableStateOf("08:00=1;20:00=0.5")};var group by remember{mutableStateOf("")};var replace by remember{mutableStateOf("")}
        Panel("برنامهٔ جدید / نسخهٔ تازه") {Text("با ساخت نسخهٔ جدید، نوبت‌ها و ثبت‌های تاریخی حفظ می‌شوند.");meds.forEach {m->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(med==m.id,{med=m.id});Text(m.name)}};Text("الگو:");listOf("daily" to "هر روز","weekly" to "روزهای هفته","interval" to "هر N روز","cycle" to "چرخهٔ مصرف و استراحت").forEach{(value,label)->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(mode==value,{mode=value});Text(label)}};Field(start,{start=it},"شروع YYYY-MM-DD");Field(end,{end=it},"پایان اختیاری YYYY-MM-DD");if(mode=="weekly")Field(weekdays,{weekdays=it},"روزها: 1=دوشنبه، 7=یکشنبه");if(mode=="interval")Field(every,{every=it},"هر چند روز؟",true);if(mode=="cycle"){Field(on,{on=it},"روز مصرف",true);Field(off,{off=it},"روز استراحت",true)};Field(slots,{slots=it},"ساعت=مقدار؛ مثال 08:00=1;20:00=0.5");db.groups().forEach {g->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(group==g.id,{group=g.id});Text("گروه: ${g.title}")}};Text("اگر برنامهٔ قبلی این دارو را جایگزین می‌کنید، نسخهٔ آن را انتخاب کنید:");db.plans().filter{it.medicineId==med}.forEach {p->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(replace==p.id,{replace=p.id});Text("شروع ${p.starts} • ${p.mode}")}};Big("ذخیرهٔ برنامه") {runCatching {val items=slots.split(';').mapIndexed {i,s->val p=s.trim().split('=');require(p.size==2);SlotSpec("slot$i",p[0].trim(),p[1].trim().toDouble(),group)};require(items.isNotEmpty());val p=Plan(UUID.randomUUID().toString(),med,LocalDate.parse(start),end.takeIf{it.isNotBlank()}?.let(LocalDate::parse),mode,weekdays.split(',').map{it.trim().toInt()}.toSet(),every.toInt(),on.toInt(),off.toInt(),false,items);db.savePlan(p,replace.ifBlank{null});Reminders.rebuild(this@MainActivity);revision++;banner="برنامه ذخیره شد"}.onFailure{banner="برنامه نامعتبر: ${it.message}"}}}
        db.plans().forEach {p->Panel("نسخهٔ ${p.starts} • ${db.medicine(p.medicineId)?.name}"){Text("${p.mode} • ${p.slots.joinToString {it.time+" = "+it.quantity}}${if(p.paused)" • متوقف" else ""}");TextButton(onClick={db.savePlan(p.copy(id=UUID.randomUUID().toString(),starts=LocalDate.now(),paused=!p.paused),p.id);Reminders.rebuild(this@MainActivity);revision++}){Text(if(p.paused)"ازسرگیری با نسخهٔ جدید" else "توقف موقت با نسخهٔ جدید")}} }
    }
    @Composable private fun TagPage(){var kind by remember{mutableStateOf("medicine")};var target by remember{mutableStateOf("")};var newGroup by remember{mutableStateOf("")};Panel("گروه‌های جعبه") {Field(newGroup,{newGroup=it},"صبح، ظهر، شب یا نام دلخواه");Big("افزودن گروه") {if(newGroup.isNotBlank()){db.addGroup(newGroup.trim());newGroup="";revision++}};db.groups().forEach {Text(it.title)}}
        Panel("نوشتن و تخصیص تگ") {Text(if(nfc.available())"NFC آماده است" else "NFC خاموش است یا دستگاه NFC ندارد");listOf("medicine" to "دارو","group" to "جعبه/گروه","today" to "گزارش امروز").forEach {(k,label)->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(kind==k,{kind=k;target=""});Text(label)}};val options=when(kind){"medicine"->db.medicines().filter{!it.archived}.map{it.id to it.name};"group"->db.groups().map{it.id to it.title};else->listOf("today" to "امروز")};options.forEach {(id,label)->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(target==id,{target=id});Text(label)}};Big("تگ جدید بنویس • سپس تگ را نزدیک کن") {if(target.isEmpty()){banner="مورد هدف را انتخاب کنید"}else {pendingToken=nfc.newToken();writeKind=kind;writeTarget=target;nfc.writeNext(pendingToken);banner="یک تگ NFC قابل نوشتن را نزدیک کنید"}};Text("روی تگ فقط شناسهٔ نسخه‌دار تصادفی می‌نویسیم؛ نام دارو روی آن نیست.",fontSize=14.sp)}
        db.tags().forEach{t->Panel("${t.kind} • ${t.token.takeLast(8)}") {Text("${if(t.active)"فعال" else "غیرفعال"} • ${t.target}");Row {TextButton(onClick={db.disableTag(t.token);revision++}){Text("غیرفعال")};TextButton(onClick={kind=t.kind;target=t.target;pendingToken=nfc.newToken();writeKind=kind;writeTarget=target;nfc.writeNext(pendingToken);banner="تگ جایگزین را نزدیک کنید"}){Text("نوشتن جایگزین")}};TextButton(onClick={if(target.isBlank()){banner="هدف تازه را از بالا انتخاب کنید"}else {db.addTag(t.token,kind,target);revision++;banner="شناسهٔ تگ دوباره تخصیص یافت"}}){Text("تخصیص دوباره به هدف انتخابی")}}}
    }
    @Composable private fun HistoryPage(){val tick=revision;val all=remember(tick){db.doses()};val meds=db.medicines();Panel("تاریخچه و گزارش") {TextButton(onClick={ val report="مدیار • گزارش\n"+all.joinToString("\n") { d -> "${meds.firstOrNull { it.id==d.medicineId }?.name?:"دارو"} | ${Instant.ofEpochMilli(d.takenAt).atZone(ZoneId.systemDefault())} | ${d.quantity} | ${d.category} | ${if(d.corrected)"اصلاح‌شده" else "تأییدشده"}" };startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report),"اشتراک گزارش")) }){Text("اشتراک گزارش خوانا")};Text("مصرف‌های ثبت‌شده: ${all.count{!it.corrected}} • خارج از برنامه: ${all.count{it.category=="outside" && !it.corrected}} • اصلاح‌شده: ${all.count{it.corrected}}",fontSize=18.sp);Text("نامشخص، مصرف‌نشدهٔ قطعی تلقی نمی‌شود.",fontSize=15.sp)}
        all.take(150).forEach {d->val m=meds.firstOrNull{it.id==d.medicineId};Panel("${m?.name?:"داروی بایگانی‌شده"} • ${Instant.ofEpochMilli(d.takenAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))}"){Text("${d.quantity} ${m?.unit?:""} • ${d.category} • ${d.method}${if(d.corrected)" • اصلاح شده" else ""}");if(!d.corrected)TextButton(onClick={db.correct(d.id,"اصلاح مدیر");revision++;banner="ثبت اصلاح شد و موجودی بازگشت"}){Text("اصلاح ثبت اشتباه")}}}
        var selectedMed by remember{mutableStateOf("")};var amount by remember{mutableStateOf("1")};Panel("ثبت اضافی یا خارج از برنامه") {Text("این مسیر فقط با تأیید روشن مدیر ثبت می‌کند و نوبت آینده را خودکار انجام‌شده نمی‌داند.");meds.filter{!it.archived}.forEach{m->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(selectedMed==m.id,{selectedMed=m.id});Text(m.name)}};Field(amount,{amount=it},"مقدار",true);Big("تأیید مصرف خارج از برنامه") {runCatching {val m=meds.first{it.id==selectedMed};db.confirm(m.id,null,amount.toDouble(),System.currentTimeMillis(),"manual");revision++;banner="مصرف خارج از برنامه ثبت شد"}.onFailure{banner="دارو و مقدار را بررسی کنید"}}}
    }
    @Composable private fun VoicePage(){val keys=listOf("due" to "وقت دارو رسیده","scan" to "دارو را بررسی کنید","already" to "این نوبت قبلاً ثبت شده","thanks" to "آفرین");var recording by remember{mutableStateOf("")};Panel("صداهای کوتاه مدیر") {Text("نام دارو، مقدار و ساعت با گفتار دستگاه خوانده می‌شوند. صدای ضبط‌شده فقط جملهٔ ثابت است.");keys.forEach{(key,title)->Column {Text(title,fontSize=18.sp);Row {TextButton(onClick={if(recording==key){voice.stopRecording();recording=""}else {if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),14)}else runCatching {voice.record(key);recording=key}.onFailure{banner="ضبط ممکن نشد"}}}){Text(if(recording==key)"توقف ضبط" else "ضبط")};TextButton(onClick={voice.play(key)}){Text("پخش")};TextButton(onClick={voice.file(key).delete();banner="حذف شد"}){Text("حذف")}}}};Big("آزمون گفتار") {voice.speak("وقت دارو رسیده. ساعت هشت، یک عدد.","due")};Text(if(voice.offline)"صدای آفلاین در دسترس است" else "صدای آفلاین این زبان یافت نشد؛ پیام متنی و اعلان کار می‌کنند.")}
    }
    @Composable private fun SettingsPage(en:MutableState<Boolean>,dark:MutableState<Boolean>){val prefs=getSharedPreferences("ui",0);var mute by remember{mutableStateOf(prefs.getBoolean("mute",false))};var speed by remember{mutableFloatStateOf(prefs.getFloat("speed",.9f))};var volume by remember{mutableFloatStateOf(prefs.getFloat("volume",1f))};var repeat by remember{mutableStateOf(db.get("repeat_limit")?:"2")};var minutes by remember{mutableStateOf(db.get("repeat_minutes")?:"15")};Panel("زبان و ظاهر") {Row(verticalAlignment=Alignment.CenterVertically){Text("English interface",Modifier.weight(1f));Switch(en.value,{en.value=it;prefs.edit().putBoolean("english",it).putString("lang",if(it)"en" else "fa").apply();voice.refreshLanguage()})};Row(verticalAlignment=Alignment.CenterVertically){Text("حالت تیره",Modifier.weight(1f));Switch(dark.value,{dark.value=it;prefs.edit().putBoolean("dark",it).apply()})}}
        Panel("اعلان و زمان‌بندی") {Text("اعلان: ${if(Reminders.notificationAllowed(this@MainActivity))"مجاز" else "غیرفعال"} • آلارم دقیق: ${if(Reminders.exactAllowed(this@MainActivity))"مجاز" else "غیرفعال"}");Text("بدون مجوز آلارم دقیق، یادآورها ممکن است دیرتر برسند. بدون مجوز اعلان، اعلان نمایش داده نمی‌شود؛ برنامهٔ امروز همچنان در اپ قابل دیدن است.");if(Build.VERSION.SDK_INT>=31)TextButton(onClick={startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(android.net.Uri.parse("package:$packageName")))}){Text("تنظیم مجوز آلارم")};if(Build.VERSION.SDK_INT>=33)TextButton(onClick={requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),11)}){Text("درخواست مجوز اعلان")};Field(repeat,{repeat=it},"تعداد تکرار هر نوبت (۰ تا ۱۰)",true);Field(minutes,{minutes=it},"فاصلهٔ تکرار (۵ تا ۲۴۰ دقیقه)",true);Big("ذخیرهٔ یادآورها") {db.set("repeat_limit",(repeat.toIntOrNull()?:2).coerceIn(0,10).toString());db.set("repeat_minutes",(minutes.toIntOrNull()?:15).coerceIn(5,240).toString());Reminders.rebuild(this@MainActivity);banner="ذخیره شد"}}
        Panel("گفتار") {Row(verticalAlignment=Alignment.CenterVertically){Text("بی‌صدا",Modifier.weight(1f));Switch(mute,{mute=it;prefs.edit().putBoolean("mute",it).apply()})};Text("سرعت گفتار");Slider(speed,{speed=it;prefs.edit().putFloat("speed",it).apply()},valueRange=.6f..1.4f);Text("بلندی صدا");Slider(volume,{volume=it;prefs.edit().putFloat("volume",it).apply()});Big("تست صدا") {voice.speak("مدیار آماده است")}}
        var oldPin by remember{mutableStateOf("")};var newPin by remember{mutableStateOf("")};Panel("تغییر PIN") {Field(oldPin,{oldPin=it},"PIN فعلی");Field(newPin,{newPin=it},"PIN جدید ۴ تا ۸ رقم");Big("تغییر") {if(pinHash(oldPin,db.get("pin_salt")?:"")==db.get("pin_hash") && newPin.length in 4..8 && newPin.all(Char::isDigit)){val salt=randomSalt();db.set("pin_salt",salt);db.set("pin_hash",pinHash(newPin,salt));oldPin="";newPin="";banner="PIN تغییر کرد"}else banner="PIN معتبر نیست"}}
    }
    @Composable private fun BackupPage(){var password by remember{mutableStateOf("")};Panel("پشتیبان امن") {Text("گذرواژهٔ حداقل ۱۰ نویسه. فایل با AES-GCM و کلید مشتق‌شده از گذرواژه محافظت می‌شود و شامل داده‌ها، شناسهٔ تگ‌ها و صداهای ضبط‌شده است.");OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text("گذرواژهٔ پشتیبان")},visualTransformation=PasswordVisualTransformation());Big("ساخت فایل پشتیبان") {if(password.length<10)banner="گذرواژه حداقل ۱۰ نویسه باشد" else {backupPass=password;export.launch("mediar-backup.mediar")}};Big("بازیابی از فایل") {if(password.length<10)banner="گذرواژه را وارد کنید" else {backupPass=password;importFile.launch(arrayOf("application/octet-stream","*/*"))}};Text("پیش از بازیابی، از دادهٔ فعلی هم پشتیبان بگیرید. شناسه‌های تگ از فایل بازیابی می‌شوند.")}}
}
