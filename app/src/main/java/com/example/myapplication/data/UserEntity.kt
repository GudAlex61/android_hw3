// File: app/src/main/java/com/example/myapplication/data/UserEntity.kt
package com.example.myapplication.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val email: String,
    val password: String,
    val fullName: String = "",        // ← новое поле
    val birthDate: String? = null,       // ← новое поле
    val passportNumber: String? = null,
    val avatarUri: String? = null
)