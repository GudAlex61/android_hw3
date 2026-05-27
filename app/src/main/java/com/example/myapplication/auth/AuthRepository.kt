// AuthRepository
package com.example.myapplication.auth

import com.example.myapplication.data.UserDao
import com.example.myapplication.data.UserEntity

class AuthRepository(private val userDao: UserDao) {

    suspend fun register(email: String, password: String): Long {
        if (userDao.getUserByEmail(email) != null) return -1L
        val hashedPassword = hashPassword(password)
        val user = UserEntity(email = email, password = hashedPassword)
        return userDao.insert(user)
    }

    private fun hashPassword(password: String): String {
        return android.util.Base64.encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(password.toByteArray()),
            android.util.Base64.NO_WRAP
        )
    }

    suspend fun login(email: String, password: String): Boolean {
        val hashedPassword = hashPassword(password)
        return userDao.login(email, hashedPassword) != null
    }

    suspend fun getUserByEmail(email: String): UserEntity? {
        return userDao.getUserByEmail(email)
    }
}