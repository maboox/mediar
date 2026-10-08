package com.mediar.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import com.mediar.app.R
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

val Violet=Color(0xFF6A4FD8)
val Navy=Color(0xFF382771)
val Mint=Color(0xFFE5F6EF)
val Gold=Color(0xFFFFDB8F)
private val font=FontFamily(Font(R.font.vazirmatn))
@Composable fun MediarTheme(en: Boolean,dark: Boolean,content: @Composable ()->Unit) {
    val scheme=if(dark)darkColorScheme(primary=Color(0xFFC9B9FF),onPrimary=Color(0xFF312163),primaryContainer=Color(0xFF463279),surface=Color(0xFF231E32),background=Color(0xFF181420),surfaceContainer=Color(0xFF2D263D),onSurface=Color(0xFFF0EAF9))
    else lightColorScheme(primary=Violet,onPrimary=Color.White,primaryContainer=Color(0xFFEAE3FF),onPrimaryContainer=Navy,secondary=Color(0xFF397461),surface=Color.White,background=Color(0xFFF7F5FC),surfaceContainer=Color(0xFFF0ECF8),onSurface=Color(0xFF2D2540),outline=Color(0xFF847A94))
    val f=if(en)FontFamily.Default else font
    MaterialTheme(colorScheme=scheme,typography=Typography(
        headlineLarge=androidx.compose.ui.text.TextStyle(fontFamily=f,fontWeight=FontWeight.Bold,fontSize=32.sp,lineHeight=45.sp),
        headlineMedium=androidx.compose.ui.text.TextStyle(fontFamily=f,fontWeight=FontWeight.Bold,fontSize=26.sp,lineHeight=38.sp),
        titleLarge=androidx.compose.ui.text.TextStyle(fontFamily=f,fontWeight=FontWeight.Bold,fontSize=21.sp,lineHeight=32.sp),
        titleMedium=androidx.compose.ui.text.TextStyle(fontFamily=f,fontWeight=FontWeight.Medium,fontSize=18.sp,lineHeight=29.sp),
        bodyLarge=androidx.compose.ui.text.TextStyle(fontFamily=f,fontSize=18.sp,lineHeight=30.sp),
        bodyMedium=androidx.compose.ui.text.TextStyle(fontFamily=f,fontSize=16.sp,lineHeight=26.sp),
        bodySmall=androidx.compose.ui.text.TextStyle(fontFamily=f,fontSize=14.sp,lineHeight=23.sp),
        labelLarge=androidx.compose.ui.text.TextStyle(fontFamily=f,fontWeight=FontWeight.Medium,fontSize=16.sp,lineHeight=26.sp),
        labelMedium=androidx.compose.ui.text.TextStyle(fontFamily=f,fontSize=14.sp,lineHeight=22.sp),
        labelSmall=androidx.compose.ui.text.TextStyle(fontFamily=f,fontSize=12.sp,lineHeight=19.sp)
    ),content=content)
}
@Composable fun Panel(modifier: Modifier=Modifier,color: Color=MaterialTheme.colorScheme.surface,content: @Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(26.dp),color=color,tonalElevation=0.dp) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
@Composable fun Action(text: String,enabled: Boolean=true,icon: ImageVector?=null,onClick: ()->Unit) {
    Button(onClick,Modifier.fillMaxWidth().heightIn(min=60.dp),enabled=enabled,shape=RoundedCornerShape(18.dp)) {
        if(icon!=null){Icon(icon,null,Modifier.size(22.dp));Spacer(Modifier.width(10.dp))}
        Text(text)
    }
}
@Composable fun Entry(value: String,change: (String)->Unit,label: String,numeric: Boolean=false,multi: Boolean=false,password: Boolean=false) {
    OutlinedTextField(value,change,Modifier.fillMaxWidth(),label={Text(label)},singleLine=!multi,
        minLines=if(multi)2 else 1,maxLines=if(multi)4 else 1,shape=RoundedCornerShape(16.dp),
        visualTransformation=if(password)PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions=KeyboardOptions(keyboardType=if(password && numeric)KeyboardType.NumberPassword else if(password)KeyboardType.Password else if(numeric)KeyboardType.Decimal else KeyboardType.Text))
}
@Composable fun Badge(text: String,color: Color=MaterialTheme.colorScheme.primaryContainer) {
    Surface(color=color,shape=RoundedCornerShape(12.dp)){Text(text,Modifier.padding(horizontal=12.dp,vertical=6.dp),style=MaterialTheme.typography.labelMedium)}
}
fun ascii(s: String)=s.map { c->when(c){in '۰'..'۹'->'0'+(c-'۰');in '٠'..'٩'->'0'+(c-'٠');'٫'->'.';else->c} }.joinToString("")
fun digits(s: String,en: Boolean)=if(en)s else s.map{if(it in '0'..'9')'۰'+(it-'0') else it}.joinToString("")
fun qty(n: Double,en: Boolean)=digits(if(n%1==0.0)n.toLong().toString() else n.toString(),en)
fun clock(ms: Long,en: Boolean)=digits(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),en)
fun datetime(ms: Long,en: Boolean)=digits(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy/MM/dd  HH:mm")),en)
fun duration(ms: Long,en: Boolean): String {
    val minutes=(ms/60_000).coerceAtLeast(0)
    val h=minutes/60;val m=minutes%60
    return digits(if(en)if(h>0)"$h h $m min" else "$m min" else if(h>0)"$h ساعت${if(m>0)" و $m دقیقه" else ""}" else "$m دقیقه",en)
}
fun pickTime(context: Context,time: String,done: (String)->Unit) {
    val current=runCatching{LocalTime.parse(time)}.getOrDefault(LocalTime.of(8,0))
    TimePickerDialog(context,{_,h,m->done("%02d:%02d".format(Locale.ROOT,h,m))},current.hour,current.minute,true).show()
}
fun pickDate(context: Context,date: LocalDate,done: (LocalDate)->Unit) {
    DatePickerDialog(context,{_,y,m,d->done(LocalDate.of(y,m+1,d))},date.year,date.monthValue-1,date.dayOfMonth).show()
}
