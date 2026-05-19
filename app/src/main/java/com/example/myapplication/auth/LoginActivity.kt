// LoginActivity
package com.example.myapplication.auth

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.MainActivity
import com.example.myapplication.data.AppDatabase
import com.example.myapplication.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var repository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val dao = AppDatabase.getDatabase(this).userDao()
        repository = AuthRepository(dao)

        binding.btnLogin.setOnClickListener {

            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            lifecycleScope.launch {
                val success = repository.login(email, password)

                // В LoginActivity.kt, внутри lifecycleScope.launch после успешного login:

                if (success) {
                    // 🔐 Получаем пользователя из БД, чтобы взять его ID
                    val user = repository.getUserByEmail(email) // ← нужно добавить этот метод в AuthRepository

                    if (user != null) {
                        val session = SessionManager(this@LoginActivity)
                        session.saveSession(user.id, user.email)
                    }

                    // Переход с очисткой стека
                    val intent = Intent(this@LoginActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                } else {
                    binding.tvError.text = "Неверный логин или пароль"
                }
            }
        }

        binding.btnRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }
}