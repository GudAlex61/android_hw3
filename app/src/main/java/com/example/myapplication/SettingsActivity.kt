package com.example.myapplication

import android.content.ClipData
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.WindowCompat
import android.content.ClipboardManager
import android.widget.Toast
import com.example.myapplication.auth.SessionManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Инициализация сессии
        sessionManager = SessionManager(this)

        // 1. Get the WindowInsetsController
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        // 2. Tell it that the appearance is light (so icons should be dark)
        windowInsetsController.isAppearanceLightStatusBars = true

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Настройки"
        toolbar.setNavigationOnClickListener { finish() }

        val recyclerView = findViewById<RecyclerView>(R.id.settingsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // ✅ Получаем реальный email из сессии
        val userEmail = sessionManager.getUserEmail() ?: "Не авторизован"

        val settings = listOf(
            Setting(getString(R.string.setting_language), getString(R.string.language_russian)),
            Setting(
                getString(R.string.setting_consultation_type),
                getString(R.string.consultation_civil_law)
            ),
            Setting(
                getString(R.string.setting_notifications), getString(R.string.notifications_enabled)
            ),
            Setting(getString(R.string.setting_theme), getString(R.string.theme_light)),
            Setting(getString(R.string.setting_account), userEmail),  // ✅ Реальный email
            Setting(getString(R.string.setting_about), getString(R.string.version))
        )

        recyclerView.adapter = SettingsAdapter(settings)
    }

    data class Setting(val title: String, val subtitle: String)

    class SettingsAdapter(private val items: List<Setting>) :
        RecyclerView.Adapter<SettingsAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.title)
            val subtitle: TextView = view.findViewById(R.id.subtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view =
                LayoutInflater.from(parent.context).inflate(R.layout.item_setting, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.subtitle.text = item.subtitle

            holder.itemView.setOnLongClickListener {
                copyToClipboard(
                    it.context, holder.subtitle.text.toString()
                )
                true
            }
        }

        override fun getItemCount() = items.size
    }
}

fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("settings_value", text)
    clipboard.setPrimaryClip(clip)

    Toast.makeText(context, "Скопировано в буфер обмена", Toast.LENGTH_SHORT).show()
}