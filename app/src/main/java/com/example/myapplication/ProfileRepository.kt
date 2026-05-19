// File: app/src/main/java/com/example/myapplication/ProfileRepository.kt
package com.example.myapplication

import com.example.myapplication.data.UserDao
import com.example.myapplication.data.UserEntity

class ProfileRepository(private val userDao: UserDao) {

    suspend fun getUserByEmail(email: String): UserProfile? {
        val entity = userDao.getUserByEmail(email)
        return entity?.toUserProfile()
    }

    suspend fun updateUserProfile(profile: UserProfile): Boolean {
        return try {
            val entity = profile.toUserEntity()
            userDao.update(entity)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // Конвертеры между Entity и Model
    private fun UserEntity.toUserProfile(): UserProfile = UserProfile(
        id = id,
        email = email,
        password = password,
        fullName = fullName,
        birthDate = birthDate,
        passportNumber = passportNumber,
        avatarUri = avatarUri
    )

    private fun UserProfile.toUserEntity(): UserEntity = UserEntity(
        id = id,
        email = email,
        password = password,
        fullName = fullName,
        birthDate = birthDate,
        passportNumber = passportNumber,
        avatarUri = avatarUri
    )
}