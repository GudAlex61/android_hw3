package com.example.myapplication

data class Document(
    val id: Int,
    val title: String,
    val type: DocumentType,
    val date: String,
    val content: String,
    val isFavorite: Boolean = false
)

enum class DocumentType(val displayName: String) {
    ALL("Все типы"),
    CONTRACT("Договор"),
    APPLICATION("Заявление"),
    CLAIM("Претензия"),
    COMPLAINT("Жалоба"),
    POWER_OF_ATTORNEY("Доверенность"),
    ACT("Акт"),
    RECEIPT("Расписка")
}

enum class DateFilter(val displayName: String) {
    DEFAULT("Без сортировки"),
    NEW_FIRST("Сначала новые"),
    OLD_FIRST("Сначала старые")
}