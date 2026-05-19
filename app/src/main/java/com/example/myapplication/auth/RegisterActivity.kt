// // RegisterActivity
package com.example.myapplication.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope  // ← 1. Импортируем lifecycleScope
import com.example.myapplication.MainActivity  // ← 2. Импортируем MainActivity
import com.example.myapplication.R
import com.example.myapplication.data.AppDatabase  // ← 3. Для инициализации DAO
import kotlinx.coroutines.launch  // ← 4. Импортируем launch

class RegisterActivity : AppCompatActivity() {

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var btnRegister: Button
    private lateinit var btnLogin: Button
    private lateinit var tvError: TextView
    private lateinit var repository: AuthRepository  // ← 5. Объявляем repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // ✅ Инициализация DAO и Repository (как в LoginActivity)
        val dao = AppDatabase.getDatabase(this).userDao()
        repository = AuthRepository(dao)

        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)
        btnRegister = findViewById(R.id.btnRegister)
        btnLogin = findViewById(R.id.btnLogin)
        tvError = findViewById(R.id.tvError)

        btnLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        btnRegister.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString()
            val confirmPassword = etConfirmPassword.text.toString()

            when {
                email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                    tvError.text = "Введите корректный email"
                }
                password.length < 6 -> {
                    tvError.text = "Пароль должен содержать минимум 6 символов"
                }
                password != confirmPassword -> {
                    tvError.text = "Пароли не совпадают"
                }
                else -> {
                    tvError.text = ""

                    // ✅ 6. Теперь lifecycleScope.launch работает
                    lifecycleScope.launch {
                        val userId = repository.register(email, password)

                        if (userId != -1L) {
                            // Сохраняем сессию
                            val session = SessionManager(this@RegisterActivity)
                            session.saveSession(userId, email)

                            Toast.makeText(this@RegisterActivity, "Регистрация успешна!", Toast.LENGTH_SHORT).show()

                            // ✅ 7. Переход с флагами
                            val intent = Intent(this@RegisterActivity, MainActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(intent)
                            finish()
                        } else {
                            tvError.text = "Пользователь с таким email уже существует"
                        }
                    }
                }
            }
        }
    }
}