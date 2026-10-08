package com.mediar.app

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mediar.app.data.Pin
import com.mediar.app.logic.*
import com.mediar.app.reminder.Reminders
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*
import java.util.UUID

data class Confirmation(val doses: List<Expected> = emptyList(), val outside: Medicine? = null, val quantity: String="1", val takenAt: Long=System.currentTimeMillis(), val method: String="manual", val request: String=UUID.randomUUID().toString())
class AppModel(app: Application): AndroidViewModel(app) {
    private val db=app.store()
    var data by mutableStateOf(Snapshot()); private set
    var ready by mutableStateOf(false); private set
    private var working by mutableStateOf(false)
    var fileBusy by mutableStateOf(false)
    val busy get()=working || fileBusy
    private val writes=Mutex()
    var page by mutableStateOf("today")
    var admin by mutableStateOf(false); private set
    var medicineId by mutableStateOf<String?>(null)
    var binding by mutableStateOf<Binding?>(null); private set
    var confirmation by mutableStateOf<Confirmation?>(null)
    var recovery by mutableStateOf(""); private set
    var toast by mutableStateOf("")
    var permissionTick by mutableIntStateOf(0)
    val sounds=MutableSharedFlow<Pair<String,String?>>(extraBufferCapacity=8)
    private var background=0L
    private var sessionToken=""
    private var sessionExpected: String?=null
    private var sessionTime=0L
    private var sessionRequest=""
    private val pendingTags=mutableMapOf<String,Pair<String,String>>()
    val en get()=data.settings["language"]=="en"
    fun t(fa: String,en: String)=if(this.en)en else fa
    init{refresh()}
    fun refresh(){viewModelScope.launch {val s=withContext(Dispatchers.IO){db.materialize();db.snapshot()};data=s;ready=true}}
    private fun action(success: String="",block: ()->Unit) {
        working=true
        viewModelScope.launch {
            writes.withLock {
                working=true
                val result=withContext(Dispatchers.IO){runCatching {block();Reminders.rebuild(getApplication());db.snapshot()}}
                result.onSuccess {data=it;if(success.isNotBlank())toast=success}.onFailure {toast=t("انجام نشد؛ داده‌ها یا ورودی را بررسی کنید.","Could not complete. Check the input or data.")}
                working=false
            }
        }
    }
    fun settings(key: String,value: String){if(!admin && key!="language")return;action {db.set(key,value)}}
    fun repeatSettings(count: Int,minutes: Int){if(admin)action {db.set("repeat_limit",count.toString());db.set("repeat_minutes",minutes.toString())}}
    fun requestAdmin(){page=if(admin)"admin" else "pin"}
    fun lock(){admin=false;page="today";confirmation=null;recovery=""}
    fun paused(){background=SystemClock.elapsedRealtime()}
    fun resumed(){if(background>0 && SystemClock.elapsedRealtime()-background>60_000 && admin)lock();background=0;permissionTick++;refresh();Reminders.work{Reminders.rebuild(getApplication())}}
    fun unlock(value: String, recover: Boolean=false){action {
        val ok=Pin.verify(db,value,recover)
        viewModelScope.launch {if(ok){if(recover)page="new_pin" else {admin=true;page="admin"}} else toast=t("کد نادرست است یا باید یک دقیقه صبر کنید.","Incorrect code, or wait one minute after repeated attempts.")}
    }}
    fun createPin(value: String,repeat: String) {
        if(value!=repeat || !value.matches(Regex("[0-9]{4,8}"))){toast=t("دو PIN یکسان با ۴ تا ۸ رقم وارد کنید.","Enter matching PINs with 4–8 digits.");return}
        action {val code=Pin.create(db,value);viewModelScope.launch {recovery=code;admin=true;page="recovery"}}
    }
    fun saveMedicine(id: String?,name: String,note: String,unit: String,stock: Double,low: Double,plan: Plan) {
        if(!admin)return
        action(t("دارو و برنامه ذخیره شد","Medicine and plan saved")) {db.saveMedicine(id,name,note,unit,stock,low,plan);viewModelScope.launch {page="medicines"}}
    }
    fun archive(id: String,archived: Boolean){if(admin)action {db.archive(id,archived)}}
    fun inventory(id: String,value: Double,reason: String){if(admin)action(t("موجودی اصلاح شد","Inventory updated")){db.adjustStock(id,value,reason)}}
    fun group(name: String){if(admin)action {db.addGroup(name)}}
    fun prepareTag(token: String,kind: String,target: String){if(admin)pendingTags[token]=kind to target}
    fun finishTag(token: String,ok: Boolean){
        pendingTags.remove(token) ?: return
        if(!ok){toast=t("تگ نوشته نشد؛ از تگ NDEF قابل نوشتن استفاده کنید.","Write failed. Use a writable NDEF tag.");return}
        refresh();toast=t("تگ نوشته و متصل شد","Tag written and linked")
    }
    fun disableTag(token: String){if(admin)action {db.disableTag(token)}}
    fun review(e: Expected){if(data.status(e.id)=="taken"){toast=t("این نوبت قبلاً ثبت شده است","This dose is already recorded");return};confirmation=Confirmation(doses=listOf(e))}
    fun confirm() {
        val c=confirmation ?: return
        val items=if(c.outside!=null)listOf(c.outside.id to null) else c.doses.map {it.medicine to it.id}
        val quantity=c.quantity.toDoubleOrNull()
        if(c.outside!=null && (quantity==null || quantity<=0 || !quantity.isFinite())){toast=t("مقدار معتبر وارد کنید","Enter a valid amount");return}
        action {
            val ok=db.confirm(items,quantity,c.takenAt,c.method,c.request)
            viewModelScope.launch {
                confirmation=null
                sessionExpected=null;sessionToken=""
                toast=if(ok)t("مصرف ثبت شد","Consumption recorded") else t("این مصرف قبلاً ثبت شده است","This consumption is already recorded")
                sounds.tryEmit(if(ok)t("مصرف ثبت شد.","Consumption recorded.") to if(en)"thanks_en" else "thanks_fa" else t("این نوبت قبلاً ثبت شده است.","This dose is already recorded.") to if(en)"already_en" else "already_fa")
            }
        }
    }
    fun decline(e: Expected){action(t("مصرف‌نشدن با اعلام شما ثبت شد","Recorded as explicitly not taken")){db.decline(e.id);viewModelScope.launch {confirmation=null}}}
    fun undoDecline(id: String){if(admin)action {db.undoDecline(id)}}
    fun correct(id: String,reason: String){if(admin)action(t("ثبت اصلاح شد و موجودی بازگشت","Record corrected; inventory restored")){db.correct(id,reason)}}
    fun snooze(e: Expected){action {
        val ok=Reminders.snooze(getApplication(),e.id)
        viewModelScope.launch {toast=if(ok)t("یادآور ۱۵ دقیقه به تعویق افتاد؛ سقف ۳ بار","Reminder delayed 15 minutes; maximum 3 times") else t("تعویق انجام نشد؛ این نوبت ثبت شده یا سقف تعویق تکمیل شده است.","Could not snooze: dose recorded or snooze limit reached.")}
    }}
    fun scan(token: String,second: Boolean) {
        if(busy)return
        viewModelScope.launch {
            val s=withContext(Dispatchers.IO){db.materialize();db.snapshot()};data=s
            val found=s.tags.firstOrNull{it.token==token && it.active}
            if(found==null){toast=t("تگ ناشناس یا غیرفعال است. مدیر باید آن را متصل کند.","Unknown or disabled tag. Ask the manager to link it.");return@launch}
            if(page=="pin" || page=="new_pin" || page=="recovery")return@launch
            binding=found;page="scan"
            if(found.kind=="today"){page="today";return@launch}
            val now=SystemClock.elapsedRealtime()
            val candidate=if(found.kind=="medicine")Schedule.scanCandidate(s.today(),found.target,s::status) else null
            if(second && token==sessionToken && now-sessionTime<=30_000 && candidate?.id!=null && candidate.id==sessionExpected) {
                confirmation=Confirmation(doses=listOf(candidate),method="NFC",request=sessionRequest)
                confirm();return@launch
            }
            if(!second){sessionToken=token;sessionExpected=candidate?.id;sessionTime=now;sessionRequest=UUID.randomUUID().toString()}
            val last=s.events.firstOrNull{it.medicine==found.target && !it.corrected}
            val speech=if(found.kind=="group")t("وضعیت جعبه نمایش داده شد. موارد مصرف‌شده را انتخاب و تأیید کنید.","Box status is displayed. Select and confirm the medicines you took.")
            else if(candidate!=null){val remaining=((candidate.due-System.currentTimeMillis())/60_000).coerceAtLeast(0)
                val at=Instant.ofEpochMilli(candidate.due).atZone(ZoneId.systemDefault()).toLocalTime()
                val remainingText=if(remaining>=60)"${remaining/60} ساعت${if(remaining%60>0)" و ${remaining%60} دقیقه" else ""}" else "$remaining دقیقه"
                t("${candidate.name}. ${candidate.amount} ${candidate.unit}. نوبت ساعت $at. ${if(remaining>0)"$remainingText تا زمان برنامه مانده." else "این نوبت هنوز ثبت نشده است."}","${candidate.name}. ${candidate.amount} ${candidate.unit}. Due at $at. This dose is not recorded.")
            } else if(last!=null)t("آخرین مصرف ${last.name} در ${Instant.ofEpochMilli(last.takenAt).atZone(ZoneId.systemDefault()).toLocalDateTime()} ثبت شده است. نوبت مورد نظر را روی صفحه بررسی کنید.","Last consumption of ${last.name} was recorded at ${Instant.ofEpochMilli(last.takenAt).atZone(ZoneId.systemDefault()).toLocalDateTime()}. Check the intended dose on screen.")
            else t("وضعیت دارو نمایش داده شد. نوبت مورد نظر را انتخاب کنید.","Medication status is displayed. Select the intended dose.")
            sounds.tryEmit(speech to if(last!=null && candidate==null)if(en)"already_en" else "already_fa" else null)
        }
    }
    fun openExpected(id: String){viewModelScope.launch {val s=withContext(Dispatchers.IO){db.materialize();db.snapshot()};data=s;s.expected.firstOrNull{it.id==id && !it.obsolete}?.let {if(s.status(id)=="unknown")review(it)}}}
}
