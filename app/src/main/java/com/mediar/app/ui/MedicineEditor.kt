package com.mediar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import com.mediar.app.*
import com.mediar.app.logic.*
import java.time.*

private val slotSaver=listSaver<SnapshotStateList<Slot>,String>(
    save={list->list.map{"${it.id}|${it.time}|${it.amount}|${it.group}"}},
    restore={list->list.map{val parts=it.split('|');Slot(parts[0],parts[1],parts[2].toDouble(),parts.getOrElse(3){""})}.toMutableStateList()}
)
@OptIn(ExperimentalLayoutApi::class)
@Composable fun MedicineEditor(m: AppModel,a: MainActivity) {
    val id=m.medicineId
    val med=remember(id){m.data.medicines.firstOrNull{it.id==id}}
    val original=remember(id){m.data.plans.lastOrNull{it.medicine==id && it.effectiveTo==null}}
    var step by rememberSaveable(id){mutableIntStateOf(0)}
    var name by rememberSaveable(id){mutableStateOf(med?.name?:"")}
    var note by rememberSaveable(id){mutableStateOf(med?.note?:"")}
    var unit by rememberSaveable(id){mutableStateOf(med?.unit?:m.t("قرص","tablet"))}
    var initial by rememberSaveable(id){mutableStateOf("30")}
    var low by rememberSaveable(id){mutableStateOf(med?.low?.toString()?:"5")}
    var mode by rememberSaveable(id){mutableStateOf(original?.mode?:"daily")}
    var interval by rememberSaveable(id){mutableStateOf(original?.interval?.toString()?:"2")}
    var on by rememberSaveable(id){mutableStateOf(original?.on?.toString()?:"5")}
    var off by rememberSaveable(id){mutableStateOf(original?.off?.toString()?:"2")}
    var weekdaysText by rememberSaveable(id){mutableStateOf(original?.weekdays?.joinToString(",")?:"1,2,3,4,5,6,7")}
    var start by rememberSaveable(id){mutableStateOf(original?.start?.toString()?:LocalDate.now().toString())}
    var end by rememberSaveable(id){mutableStateOf(original?.end?.toString()?:"")}
    var paused by rememberSaveable(id){mutableStateOf(original?.paused?:false)}
    val slots=rememberSaveable(id,saver=slotSaver){(original?.slots?:listOf(Slot(time="08:00",amount=1.0))).toMutableStateList()}
    fun plan()=Plan(medicine=id?:"new",start=LocalDate.parse(start),end=end.takeIf{it.isNotBlank()}?.let(LocalDate::parse),mode=mode,
        weekdays=weekdaysText.split(',').mapNotNull(String::toIntOrNull).toSet(),interval=interval.toIntOrNull()?:0,on=on.toIntOrNull()?:0,off=off.toIntOrNull()?:0,paused=paused,slots=slots.toList(),effectiveFrom=maxOf(LocalDate.now(),LocalDate.parse(start)))
    fun valid()=runCatching{Schedule.valid(plan())}.getOrDefault(false)
    Text(if(id==null)m.t("افزودن دارو","Add medicine") else m.t("ویرایش دارو","Edit medicine"),style=MaterialTheme.typography.headlineMedium)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        listOf(m.t("مشخصات","Details"),m.t("برنامه","Plan"),m.t("مرور","Review")).forEachIndexed{i,label->
            FilterChip(step==i,{if(i<step)step=i},label={Text(digits((i+1).toString(),m.en)+" "+label)},modifier=Modifier.weight(1f))
        }
    }
    when(step){
        0->Panel {
            Text(m.t("این دارو چیست؟","Which medicine?"),style=MaterialTheme.typography.titleLarge)
            Entry(name,{name=it.take(100)},m.t("نام دارو","Medicine name"))
            Entry(note,{note=it.take(500)},m.t("توضیح اختیاری؛ مثلاً بعد از غذا","Optional note, e.g. after food"),multi=true)
            Entry(unit,{unit=it.take(30)},m.t("واحد مصرف","Unit"))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                (if(m.en)listOf("tablet","capsule","ml","drop") else listOf("قرص","کپسول","میلی‌لیتر","قطره")).forEach{label->SuggestionChip({unit=label},label={Text(label)})}
            }
            if(id==null)Entry(initial,{initial=ascii(it)},m.t("موجودی فعلی","Current stock"),numeric=true)
            Entry(low,{low=ascii(it)},m.t("هشدار کمبود در این مقدار","Low-stock threshold"),numeric=true)
        }
        1->{
            Panel {
                Text(m.t("چه روزهایی؟","Which days?"),style=MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    listOf("daily" to m.t("هر روز","Daily"),"weekly" to m.t("روزهای هفته","Weekdays"),"interval" to m.t("هر چند روز","Every N days"),"cycle" to m.t("مصرف / استراحت","On / off cycle")).forEach{(key,label)->FilterChip(mode==key,{mode=key},label={Text(label)})}
                }
                if(mode=="weekly")FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    val set=weekdaysText.split(',').mapNotNull(String::toIntOrNull).toSet()
                    val days=if(m.en)listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun") else listOf("دوشنبه","سه‌شنبه","چهارشنبه","پنجشنبه","جمعه","شنبه","یکشنبه")
                    days.forEachIndexed{i,label->FilterChip(i+1 in set,{val n=i+1;weekdaysText=(if(n in set)set-n else set+n).sorted().joinToString(",")},label={Text(label)})}
                }
                if(mode=="interval"){Entry(interval,{interval=ascii(it)},m.t("هر چند روز؟","Every how many days?"),numeric=true);Text(m.t("شمارش از تاریخ شروع انجام می‌شود.","Counting begins at the start date."),style=MaterialTheme.typography.bodySmall)}
                if(mode=="cycle"){Entry(on,{on=ascii(it)},m.t("تعداد روزهای مصرف","Days on"),numeric=true);Entry(off,{off=ascii(it)},m.t("تعداد روزهای استراحت","Days off"),numeric=true)}
                OutlinedButton(onClick={pickDate(a,LocalDate.parse(start)){start=it.toString()}},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(m.t("شروع میلادی: $start","Start date: $start"))}
                OutlinedButton(onClick={pickDate(a,if(end.isBlank())LocalDate.parse(start) else LocalDate.parse(end)){end=it.toString()}},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(if(end.isBlank())m.t("افزودن تاریخ پایان • اختیاری","Add end date • optional") else m.t("پایان میلادی: $end","End date: $end"))}
                if(end.isNotBlank())TextButton(onClick={end=""}){Text(m.t("حذف تاریخ پایان","Remove end date"))}
                Row(verticalAlignment=Alignment.CenterVertically){Switch(paused,{paused=it});Spacer(Modifier.width(8.dp));Text(m.t("توقف موقت برنامه","Pause the plan"))}
            }
            Text(m.t("ساعت و مقدار هر نوبت","Time and amount of each dose"),style=MaterialTheme.typography.titleLarge)
            slots.forEachIndexed{i,slot->key(slot.id){Panel {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(m.t("نوبت ${digits((i+1).toString(),m.en)}","Dose ${i+1}"),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);if(slots.size>1)IconButton(onClick={slots.removeAt(i)}){Icon(Icons.Rounded.Close,m.t("حذف این نوبت","Remove this dose"))}}
                OutlinedButton(onClick={pickTime(a,slot.time){slots[i]=slot.copy(time=it)}},modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)){Icon(Icons.Rounded.Schedule,null);Spacer(Modifier.width(8.dp));Text(digits(slot.time,m.en),style=MaterialTheme.typography.titleMedium)}
                var amountText by rememberSaveable(slot.id){mutableStateOf(slot.amount.toString())}
                Entry(amountText,{value->amountText=ascii(value);slots[i]=slot.copy(amount=amountText.toDoubleOrNull()?:Double.NaN)},m.t("مقدار این نوبت ($unit)","Amount for this dose ($unit)"),numeric=true)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(.5,1.0,2.0).forEach{amount->SuggestionChip({amountText=amount.toString();slots[i]=slot.copy(amount=amount)},label={Text(qty(amount,m.en)+" "+unit)})}}
                var expanded by remember{mutableStateOf(false)}
                Box {
                    OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(m.data.groups.firstOrNull{it.id==slot.group}?.name?:m.t("بدون جعبه / گروه","No box / group"))}
                    DropdownMenu(expanded,{expanded=false}){
                        DropdownMenuItem(text={Text(m.t("بدون جعبه","No box"))},onClick={slots[i]=slot.copy(group="");expanded=false})
                        m.data.groups.forEach{g->DropdownMenuItem(text={Text(g.name)},onClick={slots[i]=slot.copy(group=g.id);expanded=false})}
                    }
                }
                if(m.data.groups.isEmpty())Text(m.t("جعبه‌ها را بعداً در «جعبه‌ها و تگ‌ها» می‌توانید بسازید.","Create boxes later under ‘Boxes and tags’."),style=MaterialTheme.typography.bodySmall)
            }}}
            if(slots.size<12)OutlinedButton(onClick={val existing=slots.map{it.time};val time=listOf("20:00","12:00","18:00","06:00","09:00","15:00","22:00").firstOrNull{it !in existing}?:"10:00";slots+=Slot(time=time,amount=1.0)},modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)){Icon(Icons.Rounded.Add,null);Spacer(Modifier.width(8.dp));Text(m.t("افزودن نوبت دیگر","Add another dose"))}
        }
        2->Panel(color=MaterialTheme.colorScheme.primaryContainer){
            Text(name,style=MaterialTheme.typography.headlineMedium)
            if(note.isNotBlank())Text(note)
            Text(planDescription(plan(),m))
            slots.sortedBy{it.time}.forEach{s->Text(digits(s.time,m.en)+" • "+qty(s.amount,m.en)+" "+unit+(m.data.groups.firstOrNull{it.id==s.group}?.let{" • "+it.name}?:""))}
            Text(m.t("شروع میلادی: $start","Starts: $start"))
            if(end.isNotBlank())Text(m.t("پایان میلادی: $end","Ends: $end"))
            if(id!=null)Text(m.t("ویرایش از امروز اعمال می‌شود. تاریخچه و مصرف‌های ثبت‌شدهٔ قبلی حفظ می‌شوند.","Edits apply from today. Previous history and confirmed records are retained."),style=MaterialTheme.typography.bodySmall)
            else Text(m.t("موجودی: ","Stock: ")+digits(initial,m.en)+" "+unit)
        }
    }
    Action(if(step<2)m.t("ادامه","Continue") else m.t("ذخیرهٔ دارو و برنامه","Save medicine and plan"),enabled=!m.busy){
        val detailValid=name.isNotBlank() && unit.isNotBlank() && (initial.toDoubleOrNull()?.let{it.isFinite() && it>=0}==true) && (low.toDoubleOrNull()?.let{it.isFinite() && it>=0}==true)
        if(!detailValid){m.toast=m.t("نام، واحد و مقدار موجودی معتبر وارد کنید.","Enter a name, unit and valid inventory amounts.");step=0}
        else if(step==1 && !valid())m.toast=m.t("برنامه را بررسی کنید: مقدار مثبت، زمان‌های متفاوت، بازهٔ تاریخ و روزهای معتبر.","Check the plan: positive amounts, distinct times, valid dates and days.")
        else if(step<2)step++
        else if(valid())m.saveMedicine(id,name,note,unit,initial.toDouble(),low.toDouble(),plan())
    }
    if(step>0)TextButton(onClick={step--}){Text(m.t("مرحلهٔ قبل","Previous step"))}
}
