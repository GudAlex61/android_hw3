package com.example.myapplication

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Message(
    val text: String,
    val isUser: Boolean,
    val time: String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
    val attachmentName: String? = null,
    val attachmentMimeType: String? = null,
    val id: String = UUID.randomUUID().toString(),
    val generatedDocxFileName: String? = null,
    val generatedDocxContent: String? = null
) {
    fun hasGeneratedDocx(): Boolean {
        return !generatedDocxFileName.isNullOrBlank() && !generatedDocxContent.isNullOrBlank()
    }
}

data class Chat(
    val id: String = UUID.randomUUID().toString(),
    val messages: MutableList<Message> = mutableListOf(),
    val createdAt: String = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date())
) {
    fun getTitle(): String {
        val lastUserMessage = messages.lastOrNull { it.isUser }
        return if (lastUserMessage != null) {
            val text = lastUserMessage.attachmentName ?: lastUserMessage.text
            if (text.length > 40) text.substring(0, 40) + "..." else text
        } else {
            "Новый чат"
        }
    }

    fun getLastMessageTime(): String {
        return messages.lastOrNull()?.time ?: createdAt
    }

    fun getShortPreview(): String {
        val first = messages.firstOrNull() ?: return "Пустой чат"
        return when {
            first.generatedDocxFileName != null -> "Документ: ${first.generatedDocxFileName}".take(60)
            first.attachmentName != null -> "Файл: ${first.attachmentName}".take(60)
            else -> first.text.take(60)
        }
    }

    fun isUnread(): Boolean {
        return messages.size == 1 && messages[0].isUser
    }
}

data class DocxExportRequest(
    val messageId: String,
    val fileName: String,
    val content: String
)
