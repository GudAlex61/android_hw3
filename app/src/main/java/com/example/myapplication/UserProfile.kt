// File: app/src/main/java/com/example/myapplication/UserProfile.kt
package com.example.myapplication

data class UserProfile(
    val id: Long = 0,
    val email: String = "",
    val password: String = "",  // можно оставить для будущей авторизации
    val fullName: String = "",
    val birthDate: String? = null,  // ← было birthYear: Int?, стало String? (ДД.ММ.ГГГГ)
    val passportNumber: String? = null,
    val avatarUri: String? = null
)