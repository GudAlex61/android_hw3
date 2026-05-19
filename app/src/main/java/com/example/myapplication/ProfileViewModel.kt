// File: app/src/main/java/com/example/myapplication/ProfileViewModel.kt
package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.auth.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers.IO

// Состояния UI
sealed class ProfileUiState {
    object Loading : ProfileUiState()
    data class Content(val profile: UserProfile) : ProfileUiState()
    data class Error(val message: String) : ProfileUiState()
}

class ProfileViewModel(
    private val repository: ProfileRepository,
    private val sessionManager: SessionManager,
    private val errorLoad: String
) : ViewModel() {

    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val uiState: StateFlow<ProfileUiState> = _uiState

    fun loadProfile() {
        viewModelScope.launch {
            _uiState.value = ProfileUiState.Loading
            try {
                val email = sessionManager.getUserEmail()
                if (email != null) {
                    val profile = repository.getUserByEmail(email)
                    if (profile != null) {
                        _uiState.value = ProfileUiState.Content(profile)
                    } else {
                        _uiState.value = ProfileUiState.Error(errorLoad)
                    }
                } else {
                    _uiState.value = ProfileUiState.Error("Пользователь не авторизован")
                }
            } catch (e: Exception) {
                _uiState.value = ProfileUiState.Error(e.message ?: errorLoad)
            }
        }
    }

    // В ProfileViewModel.kt, метод updateProfile:

    fun updateProfile(
        fullName: String,
        birthDate: String?,          // ← было birthYear: Int?
        passportNumber: String?,     // ← новый параметр
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val currentEmail = sessionManager.getUserEmail()
            if (currentEmail == null) {
                onError("Пользователь не авторизован")
                return@launch
            }

            val currentProfile = repository.getUserByEmail(currentEmail)
            if (currentProfile != null) {
                val updated = currentProfile.copy(
                    fullName = fullName.ifEmpty { currentProfile.fullName },
                    birthDate = birthDate ?: currentProfile.birthDate,      // ← обновлено
                    passportNumber = passportNumber ?: currentProfile.passportNumber  // ← добавлено
                )
                val success = repository.updateUserProfile(updated)
                if (success) {
                    onSuccess()
                    loadProfile()
                } else {
                    onError("Не удалось сохранить изменения")
                }
            } else {
                onError("Профиль не найден")
            }
        }
    }

    // Методы для аватара (оставляем как было)
    fun saveAvatarFromBitmap(context: Context, bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                val file = withContext(IO) {
                    val avatarDir = File(context.filesDir, "avatars")
                    if (!avatarDir.exists()) avatarDir.mkdirs()
                    val file = File(avatarDir, "avatar_${System.currentTimeMillis()}.png")
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                    file
                }
                // Здесь можно сохранить путь в профиль, если нужно
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadSavedAvatar(context: Context): Bitmap? {
        return try {
            val avatarDir = File(context.filesDir, "avatars")
            val files = avatarDir.listFiles()?.filter { it.name.endsWith(".png") }
            if (!files.isNullOrEmpty()) {
                android.graphics.BitmapFactory.decodeFile(files.maxByOrNull { it.lastModified() }?.absolutePath)
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}