package com.mediar.app.data

/** یک دارو */
data class Med(
    val id: String,
    var name: String,
    var doseAmount: Double = 1.0,          // تعداد در هر نوبت
    var stock: Double = 0.0,               // موجودی فعلی
    var lowThreshold: Double = 5.0,        // آستانه هشدار اتمام
    var scheduleJson: String = "{\"type\":\"daily\",\"times\":[\"08:00\"]}",
    var archived: Boolean = false
)

/** یک ثبت خوردن */
data class DoseLog(
    val id: String,
    val medId: String,
    val dueAt: Long?,      // موعد مقرر (epoch millis)، null برای ثبت دستی بدون نوبت
    val takenAt: Long,     // زمان واقعی خوردن
    val delayMin: Long,    // تاخیر به دقیقه (منفی = زودتر)
    val status: String     // early | ontime | late | manual
)
