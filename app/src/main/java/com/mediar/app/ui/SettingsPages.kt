package com.mediar.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mediar.app.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable fun SettingsPage(m: AppModel,a: MainActivity) {
    Text(m.t("تنظیمات","Settings"),style=MaterialTheme.typography.headlineMedium)
    Panel {
        Text(m.t("زبان و ظاهر","Language and appearance"),style=MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(!m.en,{m.settings("language","fa")},label={Text("فارسی")});FilterChip(m.en,{m.settings("language","en")},label={Text("English")})}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("system" to m.t("همراه گوشی","System"),"light" to m.t("روشن","Light"),"dark" to m.t("تیره","Dark")).forEach{(key,label)->FilterChip((m.data.settings["theme"]?:"system")==key,{m.settings("theme",key)},label={Text(label)})}}
    }
    PermissionPanel(m,a)
    Panel {
        Text(m.t("تکرار یادآور هر نوبت","Reminder repeats per dose"),style=MaterialTheme.typography.titleLarge)
        var count by remember(m.data.settings["repeat_limit"]){mutableStateOf(m.data.settings["repeat_limit"]?:"2")}
        var minutes by remember(m.data.settings["repeat_minutes"]){mutableStateOf(m.data.settings["repeat_minutes"]?:"15")}
        Entry(count,{count=ascii(it)},m.t("تعداد تکرار بعد از اعلان اول؛ ۰ تا ۶","Repeats after the first alert; 0–6"),numeric=true)
        Entry(minutes,{minutes=ascii(it)},m.t("فاصله به دقیقه؛ ۵ تا ۱۲۰","Interval in minutes; 5–120"),numeric=true)
        OutlinedButton(onClick={val c=count.toIntOrNull();val n=minutes.toIntOrNull();if(c!=null && c in 0..6 && n!=null && n in 5..120){m.repeatSettings(c,n)}else m.toast=m.t("اعداد در محدودهٔ گفته‌شده وارد کنید","Enter numbers within the stated ranges")}){Text(m.t("ذخیرهٔ تکرار","Save repeat settings"))}
        Text(m.t("پس از ثبت یا اعلام مصرف‌نشدنِ همان نوبت، یادآورش قطع می‌شود. تعویق ۱۵ دقیقه‌ای حداکثر ۳ بار برای همان نوبت است.","Recording consumption or explicitly not taken stops that dose's reminders. A 15-minute snooze is allowed up to 3 times per dose."),style=MaterialTheme.typography.bodySmall)
    }
    Panel {
        Action(m.t("صدا و ضبط جمله‌ها","Speech and recordings"),icon=Icons.Rounded.VolumeUp){m.page="voice"}
        OutlinedButton(onClick={m.page="tags"},modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)){Text(m.t("جعبه‌ها و تگ‌ها","Boxes and tags"))}
        OutlinedButton(onClick={m.page="backup"},modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)){Text(m.t("پشتیبان و گزارش","Backup and report"))}
        TextButton(onClick={m.page="new_pin"}){Text(m.t("تغییر PIN و کد بازیابی","Change PIN and recovery code"))}
        Text("Mediar 3.0.0",style=MaterialTheme.typography.bodySmall)
    }
}
@Composable fun VoicePage(m: AppModel,a: MainActivity) {
    var offline by remember{mutableStateOf(a.speech.offline)}
    var recording by remember{mutableStateOf<String?>(null)}
    var clipRevision by remember{mutableIntStateOf(0)}
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_PAUSE)recording=null};owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer);a.speech.cancelRecording();a.speech.stop()}}
    LaunchedEffect(m.en,m.permissionTick){a.speech.refresh();delay(1200);offline=a.speech.offline}
    LaunchedEffect(recording){if(recording!=null){delay(11_000);if(a.speech.saveRecording())clipRevision++;recording=null}}
    Text(m.t("صدا و جمله‌های شخصی","Speech and personal clips"),style=MaterialTheme.typography.headlineMedium)
    Panel {
        Text(if(offline)m.t("✓ صدای آفلاین این زبان آماده است","✓ Offline voice is available") else m.t("صدای آفلاین این زبان پیدا نشد","No offline voice for this language"),style=MaterialTheme.typography.titleMedium)
        if(!offline)Text(m.t("متن و اعلان‌ها کار می‌کنند. برای گفتار متغیر، صدای آفلاین را در تنظیمات گفتار گوشی نصب کنید؛ دانلود اجباری نیست.","Text and notifications still work. For dynamic speech, optionally install an offline voice in phone speech settings."),style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={runCatching{a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}}){Text(m.t("تنظیمات دسترس‌پذیری گوشی","Phone accessibility settings"))}
        Row(verticalAlignment=Alignment.CenterVertically){Switch(m.data.settings["mute"]=="true",{m.settings("mute",it.toString())});Spacer(Modifier.width(8.dp));Text(m.t("حالت بدون صدا","Mute audio"))}
        var speed by remember(m.data.settings["speed"]){mutableFloatStateOf(m.data.settings["speed"]?.toFloatOrNull()?:.9f)}
        var volume by remember(m.data.settings["volume"]){mutableFloatStateOf(m.data.settings["volume"]?.toFloatOrNull()?:1f)}
        Text(m.t("سرعت گفتار","Speech speed"));Slider(speed,{speed=it},valueRange=.5f..1.5f,onValueChangeFinished={m.settings("speed",speed.toString())})
        Text(m.t("بلندی صدا","Volume"));Slider(volume,{volume=it},valueRange=0f..1f,onValueChangeFinished={m.settings("volume",volume.toString())})
        OutlinedButton(onClick={a.speech.speak(m.t("مدیار آماده است. یک ساعت و بیست دقیقه تا نوبت بعدی مانده.","Mediar is ready. Your next dose is in one hour and twenty minutes."))},modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)){Text(m.t("تست گفتار","Test speech"))}
    }
    Panel {
        Row(verticalAlignment=Alignment.CenterVertically){Switch(m.data.settings["alarm_voice"]=="true",{m.settings("alarm_voice",it.toString())});Spacer(Modifier.width(8.dp));Text(m.t("صدای شخصی هنگام یادآور","Personal voice on reminders"),Modifier.weight(1f))}
        Text(m.t("با آلارم دقیق، جملهٔ ضبط‌شدهٔ یادآور و سپس گفتار عمومی پخش می‌شود. اگر شروع پخش در پس‌زمینه مجاز نباشد، اعلان صوتی سیستم جایگزین می‌شود.","With exact alarms, the reminder clip and a generic spoken message play. If background playback cannot start, an audible system notification is used."),style=MaterialTheme.typography.bodySmall)
    }
    val language=if(m.en)"en" else "fa"
    listOf("due" to m.t("وقت دارو رسیده","Time to check your medicine"),"already" to m.t("این نوبت قبلاً ثبت شده","This dose is already recorded"),"thanks" to m.t("آفرین؛ مصرف ثبت شد","Well done; consumption recorded")).forEach{(type,title)->
        val key="${type}_$language";val tick=clipRevision;val exists=a.speech.file(key).exists()
        Panel {
            Text(title,style=MaterialTheme.typography.titleMedium)
            Text(m.t("یک جملهٔ مستقل و کوتاه ضبط کنید؛ اطلاعات متغیر جداگانه خوانده می‌شود. حداکثر ۱۱ ثانیه.","Record a short complete sentence. Dynamic details are spoken separately. Maximum 11 seconds."),style=MaterialTheme.typography.bodySmall)
            if(recording==key)Action(m.t("پایان ضبط و ذخیره","Stop and save"),icon=Icons.Rounded.Stop){if(a.speech.saveRecording())clipRevision++;else m.toast=m.t("ضبط کوتاه یا ناموفق بود؛ دوباره تلاش کنید","Recording was too short or failed; try again");recording=null}
            else OutlinedButton(onClick={if(a.microphone())runCatching{a.speech.record(key);recording=key}.onFailure{m.toast=m.t("ضبط آغاز نشد","Could not start recording")}},enabled=recording==null,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)){Icon(Icons.Rounded.Mic,null);Spacer(Modifier.width(8.dp));Text(if(exists)m.t("ضبط جایگزین","Record replacement") else m.t("ضبط با صدای خودتان","Record your voice"))}
            if(exists && recording==null)Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){TextButton(onClick={a.speech.play(key)}){Icon(Icons.Rounded.PlayArrow,null);Text(m.t("پخش","Play"))};TextButton(onClick={a.speech.stop();a.speech.file(key).delete();clipRevision++}){Text(m.t("حذف","Delete"))}}
        }
    }
}
@Composable fun TagsPage(m: AppModel,a: MainActivity) {
    var newGroup by rememberSaveable{mutableStateOf("")}
    var kind by rememberSaveable{mutableStateOf("medicine")}
    var target by rememberSaveable{mutableStateOf("")}
    var expanded by remember{mutableStateOf(false)}
    val tick=m.permissionTick
    DisposableEffect(Unit){onDispose{a.cancelWrite()}}
    Text(m.t("جعبه‌ها و تگ‌ها","Boxes and tags"),style=MaterialTheme.typography.headlineMedium)
    Panel {
        Text(m.t("ساخت جعبه / گروه","Create a box / group"),style=MaterialTheme.typography.titleLarge)
        Text(m.t("مثلاً صبح، ظهر یا شب. در برنامهٔ هر دارو، نوبت مربوط را به جعبه وصل کنید.","For example morning, noon or night. Link each intended dose to the box in its medicine plan."))
        Entry(newGroup,{newGroup=it.take(50)},m.t("نام جعبه","Box name"))
        OutlinedButton(onClick={if(newGroup.isNotBlank()){m.group(newGroup);newGroup=""}},enabled=!m.busy && newGroup.isNotBlank()){Text(m.t("ساخت جعبه","Create box"))}
        m.data.groups.forEach{Text("• "+it.name)}
    }
    Panel {
        Text(m.t("نوشتن و اتصال تگ","Write and link a tag"),style=MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("medicine" to m.t("دارو","Medicine"),"group" to m.t("جعبه","Box"),"today" to m.t("امروز","Today")).forEach{(key,label)->FilterChip(kind==key,{kind=key;target="";a.cancelWrite()},label={Text(label)})}}
        val choices=if(kind=="medicine")m.data.medicines.filter{!it.archived}.map{it.id to it.name} else m.data.groups.map{it.id to it.name}
        if(kind!="today")Box {
            OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)){Text(choices.firstOrNull{it.first==target}?.second?:m.t("انتخاب هدف","Select target"))}
            DropdownMenu(expanded,{expanded=false}){choices.forEach{(id,name)->DropdownMenuItem(text={Text(name)},onClick={target=id;expanded=false})}}
        }
        Text(m.t("روی تگ فقط شناسهٔ تصادفی ذخیره می‌شود؛ نام دارو یا اطلاعات پزشکی نوشته نمی‌شود.","Only a random identifier is written on the tag; no medicine name or medical data."),style=MaterialTheme.typography.bodySmall)
        if(a.writeToken!=null){Badge(m.t("منتظر تگ…","Waiting for tag…"));Action(m.t("لغو نوشتن","Cancel writing")){a.cancelWrite()}}
        else Action(m.t("آمادهٔ نوشتن • تگ را نزدیک کنید","Write a tag • hold it near"),enabled=!m.busy && (kind=="today" || target.isNotBlank()),icon=Icons.Rounded.Nfc){a.write(kind,target)}
        if(!a.nfc.enabled())TextButton(onClick={runCatching{a.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))}}){Text(m.t("تنظیمات NFC گوشی","Phone NFC settings"))}
    }
    m.data.tags.filter{it.active}.forEach {tag->Panel {
        Text(when(tag.kind){"medicine"->m.data.medicines.firstOrNull{it.id==tag.target}?.name?:m.t("دارو","Medicine");"group"->m.data.groups.firstOrNull{it.id==tag.target}?.name?:m.t("جعبه","Box");else->m.t("گزارش امروز","Today overview")},style=MaterialTheme.typography.titleMedium)
        Text(m.t("شناسهٔ تگ: ","Tag ID: ")+tag.token.takeLast(8),style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={m.disableTag(tag.token)}){Text(m.t("غیرفعال‌کردن این تگ","Disable this tag"))}
    }}
}
@Composable fun BackupPage(m: AppModel,a: MainActivity) {
    var pass by remember{mutableStateOf("")};var repeated by remember{mutableStateOf("")};var restoreDialog by remember{mutableStateOf(false)}
    Text(m.t("پشتیبان و گزارش","Backup and report"),style=MaterialTheme.typography.headlineMedium)
    Panel {
        Icon(Icons.Rounded.EnhancedEncryption,null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.primary)
        Text(m.t("پشتیبان رمزدار","Encrypted backup"),style=MaterialTheme.typography.titleLarge)
        Text(m.t("داروها، برنامه‌ها، تاریخچه، تگ‌ها، تنظیمات و صداها با هم ذخیره می‌شوند. گذرواژه را خارج از گوشی نگه دارید؛ قابل بازیابی از سرور نیست.","Medicines, plans, history, tags, settings and recordings are saved together. Keep the password outside the phone; there is no server recovery."))
        Entry(pass,{pass=it},m.t("گذرواژه؛ حداقل ۱۰ نویسه","Password; at least 10 characters"),password=true)
        Entry(repeated,{repeated=it},m.t("تکرار گذرواژه برای خروجی","Repeat password for export"),password=true)
        Action(m.t("ذخیرهٔ پشتیبان","Save backup"),enabled=pass.length>=10 && pass==repeated){a.backup(pass,false)}
        OutlinedButton(onClick={restoreDialog=true},enabled=pass.length>=10,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)){Text(m.t("بازیابی فایل با این گذرواژه","Restore a file with this password"))}
    }
    Panel {
        Text(m.t("گزارش برای خانواده یا پزشک","Report for family or clinician"),style=MaterialTheme.typography.titleLarge)
        Text(m.t("فایل HTML خوانا شامل ۳۰ روز گذشته است. آن را با مرورگر باز و از فایل‌منیجر به اشتراک بگذارید؛ پشتیبان رمزدار نیست.","A readable HTML file covers the past 30 days. Open it in a browser and share from your file manager. It is not an encrypted backup."))
        Action(m.t("ذخیرهٔ گزارش ۳۰ روز","Save 30-day report"),icon=Icons.Rounded.IosShare){a.report()}
    }
    if(restoreDialog)AlertDialog(onDismissRequest={restoreDialog=false},title={Text(m.t("جایگزینی با پشتیبان؟","Replace with backup?"))},text={Text(m.t("اطلاعات فعلی با محتوای فایل معتبر جایگزین می‌شود. ابتدا از اطلاعات فعلی پشتیبان بگیرید. فایل یا گذرواژهٔ نامعتبر نباید داده‌های فعلی را تغییر دهد.","Current data will be replaced by a valid backup. Back up current data first. An invalid file or password does not change current data."))},confirmButton={TextButton(onClick={restoreDialog=false;a.backup(pass,true)}){Text(m.t("انتخاب فایل و بازیابی","Choose file and restore"))}},dismissButton={TextButton(onClick={restoreDialog=false}){Text(m.t("لغو","Cancel"))}})
}
