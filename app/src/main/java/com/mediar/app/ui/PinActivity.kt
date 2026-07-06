package com.mediar.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mediar.app.R
import com.mediar.app.data.Prefs

/** ورود به بخش مدیریت با PIN؛ بار اول PIN ساخته می‌شود */
class PinActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin)

        val prefs = Prefs.get(this)
        val title = findViewById<TextView>(R.id.pinTitle)
        val pin1 = findViewById<EditText>(R.id.pinInput)
        val pin2 = findViewById<EditText>(R.id.pinConfirm)
        val btn = findViewById<Button>(R.id.pinButton)

        val setupMode = !prefs.hasPin()
        if (setupMode) {
            title.text = "یک رمز عددی برای بخش مدیریت انتخاب کن"
            pin2.visibility = View.VISIBLE
            btn.text = "ذخیره رمز و ورود"
        } else {
            title.text = "رمز بخش مدیریت را وارد کن"
            pin2.visibility = View.GONE
            btn.text = "ورود"
        }

        btn.setOnClickListener {
            val p1 = pin1.text.toString().trim()
            if (p1.length < 4) {
                Toast.makeText(this, "رمز حداقل ۴ رقم باشد", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (setupMode) {
                if (p1 != pin2.text.toString().trim()) {
                    Toast.makeText(this, "تکرار رمز یکسان نیست", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.setPin(p1)
                enter()
            } else if (prefs.checkPin(p1)) {
                enter()
            } else {
                Toast.makeText(this, "رمز اشتباه است", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enter() {
        startActivity(Intent(this, AdminActivity::class.java))
        finish()
    }
}
