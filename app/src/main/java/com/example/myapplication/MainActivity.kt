package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import com.example.myapplication.auth.LoginActivity
import com.example.myapplication.auth.SessionManager
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var session: SessionManager

    private var chatFragment: ChatFragment? = null
    private var documentsFragment: DocumentsFragment? = null
    private var profileFragment: ProfileFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        session = SessionManager(this)
        if (!session.isLoggedIn()) {
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = true

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        if (savedInstanceState == null) {
            chatFragment = ChatFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, chatFragment!!, "chat")
                .commit()
            bottomNavigationView.selectedItemId = R.id.navigation_chat
        } else {
            chatFragment = supportFragmentManager.findFragmentByTag("chat") as? ChatFragment
            documentsFragment = supportFragmentManager.findFragmentByTag("documents") as? DocumentsFragment
            profileFragment = supportFragmentManager.findFragmentByTag("profile") as? ProfileFragment
        }

        bottomNavigationView.setOnNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_documents -> {
                    showFragment("documents")
                    true
                }
                R.id.navigation_chat -> {
                    showFragment("chat")
                    true
                }
                R.id.navigation_profile -> {
                    showFragment("profile")
                    true
                }
                else -> false
            }
        }
    }

    private fun showFragment(tag: String) {
        val transaction = supportFragmentManager.beginTransaction()

        supportFragmentManager.fragments.forEach { fragment ->
            if (!fragment.isHidden) {
                transaction.hide(fragment)
            }
        }

        val existing = supportFragmentManager.findFragmentByTag(tag)
        if (existing != null) {
            transaction.show(existing)
        } else {
            val newFragment: Fragment = when (tag) {
                "chat" -> ChatFragment().also { chatFragment = it }
                "documents" -> DocumentsFragment().also { documentsFragment = it }
                "profile" -> ProfileFragment().also { profileFragment = it }
                else -> return
            }
            transaction.add(R.id.fragment_container, newFragment, tag)
        }

        transaction.commit()
    }
}