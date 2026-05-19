// SessionManager
package com.example.myapplication.auth

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("app_session", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
    }

    fun saveSession(userId: Long, email: String) {
        prefs.edit()
            .putLong(KEY_USER_ID, userId)
            .putString(KEY_USER_EMAIL, email)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
    }

    fun getUserId(): Long? =
        if (prefs.getBoolean(KEY_IS_LOGGED_IN, false)) prefs.getLong(KEY_USER_ID, -1).takeIf { it != -1L } else null

    fun getUserEmail(): String? =
        if (prefs.getBoolean(KEY_IS_LOGGED_IN, false)) prefs.getString(KEY_USER_EMAIL, null) else null

    fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_IS_LOGGED_IN, false)

    fun logout() {
        prefs.edit().clear().apply()
    }
}