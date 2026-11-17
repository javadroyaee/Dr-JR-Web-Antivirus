package ir.jrweb.drjrweb

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import ir.jrweb.drjrweb.databinding.ActivityRegisterBinding

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("DrJRWebPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("is_registered", false)) {
            goToMain()
            return
        }

        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnActivate.setOnClickListener {
            val name = binding.etUserName.text.toString().trim()
            val mobile = binding.etMobile.text.toString().trim()
            val serial = binding.etSerial.text.toString().trim()

            if (name.length < 2) {
                showError("لطفاً نام خود را به درستی وارد نمایید.")
                return@setOnClickListener
            }

            if (mobile.length != 11 || !mobile.startsWith("09")) {
                showError("لطفاً شماره موبایل معتبر ۱۱ رقمی (با شروع 09) وارد کنید.")
                return@setOnClickListener
            }

            if (!isValidSerial(serial)) {
                showError("شماره سریال نامعتبر است! سریال باید ۱۲ رقمی بوده و شامل ترکیب مجاز باشد.")
                return@setOnClickListener
            }

            prefs.edit()
                .putBoolean("is_registered", true)
                .putString("user_name", name)
                .putString("user_mobile", mobile)
                .putString("user_serial", serial)
                .apply()

            goToMain()
        }
    }

    private fun isValidSerial(serial: String): Boolean {
        if (serial.length != 12 || !serial.all { it.isDigit() }) {
            return false
        }
        val validPatterns = listOf("157", "175", "517", "571", "715", "751", "100")
        return validPatterns.any { serial.contains(it) }
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.visibility = View.VISIBLE
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}