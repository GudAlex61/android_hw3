package com.example.myapplication

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.myapplication.auth.SessionManager
import com.example.myapplication.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = getApplication()
    private fun getUserChatPrefs(): SharedPreferences {
        val userId = sessionManager.getUserId()
        val prefsName = userId?.let { "chat_local_storage_user_$it" } ?: "chat_local_storage"
        return app.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }
    private val sessionManager = SessionManager(app)

    private val _chatHistory = MutableLiveData<MutableList<Chat>>(mutableListOf(Chat()))
    val chatHistory: LiveData<MutableList<Chat>> = _chatHistory

    private val _currentChatIndex = MutableLiveData(0)
    val currentChatIndex: LiveData<Int> = _currentChatIndex

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _docxExportRequest = MutableLiveData<DocxExportRequest?>()
    val docxExportRequest: LiveData<DocxExportRequest?> = _docxExportRequest

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    init {
        val savedChats = loadChatsFromStorage()
        _chatHistory.value = savedChats.ifEmpty { mutableListOf(Chat()) }
        _currentChatIndex.value = loadCurrentChatIndex(_chatHistory.value?.size ?: 1)
    }

    fun getCurrentChat(): Chat {
        val chats = _chatHistory.value ?: mutableListOf(Chat()).also { _chatHistory.value = it }
        val index = (_currentChatIndex.value ?: 0).coerceIn(0, chats.lastIndex.coerceAtLeast(0))
        if (chats.isEmpty()) {
            val chat = Chat()
            chats.add(chat)
            _currentChatIndex.value = 0
            persistChats()
            return chat
        }
        return chats[index]
    }

    fun sendMessage(text: String, isUser: Boolean) {
        addMessage(Message(text = text, isUser = isUser))
        if (isUser) {
            getAIResponse(text)
        }
    }

    fun sendFile(fileName: String, mimeType: String?, bytes: ByteArray, userPrompt: String = "") {
        val safeMimeType = normalizeMimeType(fileName, mimeType)
        val prompt = userPrompt.trim()

        addMessage(
            Message(
                text = prompt,
                isUser = true,
                attachmentName = fileName,
                attachmentMimeType = safeMimeType
            )
        )

        getAIResponseWithFile(fileName, safeMimeType, bytes, prompt)
    }

    fun createNewChat() {
        val chats = _chatHistory.value ?: mutableListOf()
        if (chats.isEmpty() || getCurrentChat().messages.isNotEmpty()) {
            chats.add(Chat())
            _chatHistory.value = chats
            _currentChatIndex.value = chats.lastIndex
            persistChats()
        }
    }

    fun switchToChat(index: Int) {
        val chats = _chatHistory.value ?: return
        if (index in chats.indices && index != _currentChatIndex.value) {
            _currentChatIndex.value = index
            persistChats()
        }
    }

    fun consumeDocxExportRequest() {
        _docxExportRequest.value = null
    }

    private fun addMessage(message: Message) {
        val chats = _chatHistory.value ?: mutableListOf(Chat())
        if (chats.isEmpty()) {
            chats.add(Chat())
            _currentChatIndex.value = 0
        }
        getCurrentChat().messages.add(message)
        _chatHistory.value = chats
        persistChats()
    }

    private fun getAIResponse(userMessage: String) {
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val shouldCreateDocx = isDocumentGenerationRequest(userMessage)
            try {
                val payload = if (shouldCreateDocx) {
                    buildTextPayload(buildDocumentGenerationPrompt(userMessage), maxTokens = 3200)
                } else {
                    buildTextPayload(userMessage)
                }

                val aiResponse = executeOpenRouterRequestWithRetry(payload)

                withContext(Dispatchers.Main) {
                    if (shouldCreateDocx) {
                        val fileName = makeDocumentFileName(userMessage, aiResponse)
                        val documentMessage = Message(
                            text = "Документ готов: $fileName\nНажмите «Скачать DOCX», чтобы сохранить его на устройство.",
                            isUser = false,
                            generatedDocxFileName = fileName,
                            generatedDocxContent = aiResponse
                        )
                        addMessage(documentMessage)
                        _docxExportRequest.value = DocxExportRequest(
                            messageId = documentMessage.id,
                            fileName = fileName,
                            content = aiResponse
                        )
                    } else {
                        addMessage(Message(text = aiResponse, isUser = false))
                    }
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addMessage(Message(text = friendlyErrorMessage(e), isUser = false))
                    _isLoading.value = false
                }
            }
        }
    }

    private fun getAIResponseWithFile(fileName: String, mimeType: String, bytes: ByteArray, userPrompt: String) {
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val shouldCreateDocx = isDocumentGenerationRequest(userPrompt)
            try {
                val prompt = if (shouldCreateDocx) {
                    buildDocumentGenerationPromptForFile(fileName, mimeType, userPrompt)
                } else {
                    buildFilePrompt(fileName, mimeType, userPrompt)
                }
                val maxTokens = if (shouldCreateDocx) 3200 else 1800
                val payload = when {
                    isPdf(fileName, mimeType) -> buildPdfPayload(fileName, bytes, prompt, maxTokens)
                    isDocx(fileName, mimeType) -> buildDocxPayload(fileName, bytes, prompt, maxTokens)
                    isImageMime(mimeType) -> buildImagePayload(fileName, mimeType, bytes, prompt, maxTokens)
                    else -> throw IllegalArgumentException("Поддерживаются только изображения, PDF и DOCX")
                }

                val aiResponse = executeOpenRouterRequestWithRetry(payload)

                withContext(Dispatchers.Main) {
                    if (shouldCreateDocx) {
                        val fileNameForSave = makeDocumentFileName(userPrompt.ifBlank { fileName }, aiResponse)
                        val documentMessage = Message(
                            text = "Документ готов: $fileNameForSave\nНажмите «Скачать DOCX», чтобы сохранить его на устройство.",
                            isUser = false,
                            generatedDocxFileName = fileNameForSave,
                            generatedDocxContent = aiResponse
                        )
                        addMessage(documentMessage)
                        _docxExportRequest.value = DocxExportRequest(
                            messageId = documentMessage.id,
                            fileName = fileNameForSave,
                            content = aiResponse
                        )
                    } else {
                        addMessage(Message(text = aiResponse, isUser = false))
                    }
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addMessage(Message(text = "Не удалось обработать файл: ${friendlyErrorMessage(e)}", isUser = false))
                    _isLoading.value = false
                }
            }
        }
    }

    private suspend fun executeOpenRouterRequestWithRetry(
        payload: JSONObject,
        maxAttempts: Int = 3
    ): String {
        var lastException: Exception? = null

        for (attempt in 1..maxAttempts) {
            try {
                return executeOpenRouterRequest(payload)
            } catch (e: RateLimitException) {
                lastException = e
                if (attempt < maxAttempts) delay(2000L * (1 shl (attempt - 1)))
            } catch (e: GatewayTimeoutException) {
                lastException = e
                if (attempt < maxAttempts) delay(3000L * (1 shl (attempt - 1)))
            }
        }

        throw lastException ?: IllegalStateException("Неизвестная ошибка после $maxAttempts попыток")
    }

    private suspend fun buildTextPayload(userMessage: String, maxTokens: Int = 1600): JSONObject {
        return JSONObject().apply {
            put("model", AppConfig.openRouterModel)
            put("max_tokens", maxTokens)
            put("messages", buildMessagesArray(userMessage))
        }
    }

    private suspend fun buildPdfPayload(
        fileName: String,
        bytes: ByteArray,
        prompt: String,
        maxTokens: Int = 1800
    ): JSONObject {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val dataUrl = "data:application/pdf;base64,$base64"

        val currentContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt.ifBlank {
                    "Проанализируй PDF-документ. Извлеки ключевые факты, юридически значимые условия, риски и краткий вывод."
                })
            })
            put(JSONObject().apply {
                put("type", "file")
                put("file", JSONObject().apply {
                    put("filename", fileName)
                    put("file_data", dataUrl)
                })
            })
        }

        return JSONObject().apply {
            put("model", AppConfig.openRouterModel)
            put("max_tokens", maxTokens)
            put("messages", buildMessagesArray(currentContent))
        }
    }

    private suspend fun buildDocxPayload(
        fileName: String,
        bytes: ByteArray,
        prompt: String,
        maxTokens: Int = 1800
    ): JSONObject {
        val documentText = extractTextFromDocx(bytes)
            .take(MAX_DOCX_CHARS)
            .ifBlank { throw IllegalArgumentException("Не удалось извлечь текст из DOCX") }

        val userContent = buildString {
            appendLine(prompt.ifBlank { "Проанализируй DOCX-документ." })
            appendLine()
            appendLine("Имя файла: $fileName")
            appendLine("Текст документа:")
            append(documentText)
        }

        return buildTextPayload(userContent, maxTokens = maxTokens)
    }

    private suspend fun buildImagePayload(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        prompt: String,
        maxTokens: Int = 1400
    ): JSONObject {
        val safeMimeType = normalizeImageMimeType(fileName, mimeType)
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val dataUrl = "data:$safeMimeType;base64,$base64"

        val currentContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt.ifBlank { "Опиши изображение и извлеки весь видимый текст." })
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", dataUrl)
                })
            })
        }

        return JSONObject().apply {
            put("model", AppConfig.openRouterVisionModel)
            put("max_tokens", maxTokens)
            put("messages", buildMessagesArray(currentContent))
        }
    }

    private suspend fun buildMessagesArray(currentUserContent: Any): JSONArray {
        return JSONArray().apply {
            put(systemMessageJson())
            getPreviousDialogContext().forEach { contextMessage ->
                put(JSONObject().apply {
                    put("role", if (contextMessage.isUser) "user" else "assistant")
                    put("content", contextMessage.toContextText())
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", currentUserContent)
            })
        }
    }

    private fun getPreviousDialogContext(): List<Message> {
        val messages = getCurrentChat().messages.toList()
        val historyBeforeCurrent = if (messages.lastOrNull()?.isUser == true) {
            messages.dropLast(1)
        } else {
            messages
        }
        if (historyBeforeCurrent.isEmpty()) return emptyList()

        val lastAssistantIndex = historyBeforeCurrent.indexOfLast { !it.isUser }
        if (lastAssistantIndex == -1) {
            return historyBeforeCurrent.lastOrNull { it.isUser }?.let { listOf(it) } ?: emptyList()
        }

        val lastUserIndex = historyBeforeCurrent
            .subList(0, lastAssistantIndex)
            .indexOfLast { it.isUser }

        val result = mutableListOf<Message>()
        if (lastUserIndex != -1) result.add(historyBeforeCurrent[lastUserIndex])
        result.add(historyBeforeCurrent[lastAssistantIndex])
        return result
    }

    private fun Message.toContextText(): String {
        return buildString {
            if (text.isNotBlank()) append(text.take(MAX_CONTEXT_MESSAGE_CHARS))
            if (!attachmentName.isNullOrBlank()) {
                if (isNotEmpty()) appendLine()
                append("[Прикреплённый файл: $attachmentName")
                if (!attachmentMimeType.isNullOrBlank()) append(", тип: $attachmentMimeType")
                append("]")
            }
            if (isBlank()) append(if (isUser) "[Сообщение пользователя без текста]" else "[Ответ ассистента без текста]")
        }.trim()
    }

    private suspend fun systemMessageJson(): JSONObject {
        val prompt = getSystemPrompt()
        return JSONObject().apply {
            put("role", "system")
            put("content", prompt)
        }
    }

    private fun executeOpenRouterRequest(payload: JSONObject): String {
        val apiKey = AppConfig.openRouterApiKey
        if (apiKey.isBlank()) {
            throw IllegalStateException("OPENROUTER_API_KEY не задан в local.properties")
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://ai-lawyer.local")
            .addHeader("X-Title", "AI Lawyer Android")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                when (response.code) {
                    429 -> throw RateLimitException("Слишком много запросов. Попробуйте позже.")
                    504 -> throw GatewayTimeoutException("Сервер OpenRouter не успел ответить. Попробуйте ещё раз.")
                    401 -> throw IllegalStateException("Ошибка авторизации OpenRouter: проверьте OPENROUTER_API_KEY")
                    else -> throw IllegalStateException("Ошибка API ${response.code}: ${responseBody.take(400)}")
                }
            }
            return parseAIResponse(responseBody)
        }
    }

    private fun parseAIResponse(responseBody: String): String {
        val json = JSONObject(responseBody)
        val choices = json.optJSONArray("choices") ?: JSONArray()
        if (choices.length() == 0) return "Не удалось получить ответ от модели."

        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: return "Не удалось прочитать ответ модели."

        return when (val content = message.opt("content")) {
            is String -> content.trim()
            is JSONArray -> parseArrayContent(content)
            else -> content?.toString()?.trim().orEmpty()
        }.ifBlank { "Модель вернула пустой ответ." }
    }

    private fun parseArrayContent(content: JSONArray): String {
        val parts = mutableListOf<String>()
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            val text = item.optString("text").ifBlank { item.optString("content") }
            if (text.isNotBlank()) parts.add(text)
        }
        return parts.joinToString("\n").trim()
    }

    private fun buildFilePrompt(fileName: String, mimeType: String, userPrompt: String): String {
        if (userPrompt.isNotBlank()) return userPrompt
        return when {
            isPdf(fileName, mimeType) -> "Проанализируй PDF-документ: $fileName. Дай понятный юридический вывод, важные условия и возможные риски."
            isDocx(fileName, mimeType) -> "Проанализируй DOCX-документ: $fileName. Дай понятный юридический вывод, важные условия и возможные риски."
            isImageMime(mimeType) -> "Проанализируй изображение: $fileName. Извлеки текст и объясни юридически важные моменты."
            else -> "Проанализируй файл: $fileName."
        }
    }

    private fun isDocumentGenerationRequest(text: String): Boolean {
        val normalized = text.lowercase(Locale.getDefault())
        val createWords = listOf(
            "создай", "создать", "сгенерируй", "сгенерировать", "подготовь", "подготовить",
            "составь", "составить", "сделай", "сделать", "напиши", "написать", "оформи", "оформить"
        )
        val documentWords = listOf(
            "договор", "заявлен", "претензи", "иск", "исков", "жалоб", "документ",
            "соглашен", "уведомлен", "расписк", "акт", "ходатайств", "docx", "word", "ворд"
        )
        val downloadWords = listOf("скач", "docx", "word", "ворд", "файл")

        val hasCreateWord = createWords.any { normalized.contains(it) }
        val hasDocumentWord = documentWords.any { normalized.contains(it) }
        val hasDownloadWord = downloadWords.any { normalized.contains(it) }

        return hasDocumentWord && (hasCreateWord || hasDownloadWord)
    }

    private fun buildDocumentGenerationPrompt(userMessage: String): String {
        return buildString {
            appendLine(userMessage.trim())
            appendLine()
            appendLine("Сгенерируй полный текст документа для последующего сохранения в DOCX.")
            appendLine("Верни только содержимое документа. Не добавляй приветствие, пояснение, фразу «я подготовил», Markdown, HTML или блоки кода.")
            appendLine("Первая строка должна быть названием документа.")
            appendLine("Если не хватает данных, используй аккуратные поля-заглушки в квадратных скобках, например [Адрес квартиры], [ФИО наймодателя].")
            appendLine("Используй данные профиля пользователя из системного промпта, если они подходят для стороны документа.")
        }.trim()
    }

    private fun buildDocumentGenerationPromptForFile(fileName: String, mimeType: String, userMessage: String): String {
        val baseRequest = userMessage.trim().ifBlank {
            "Подготовь юридический документ на основе приложенного файла."
        }
        return buildString {
            appendLine(baseRequest)
            appendLine()
            appendLine("Приложенный файл: $fileName")
            appendLine("Тип файла: $mimeType")
            appendLine("Сгенерируй полный текст документа для последующего сохранения в DOCX, учитывая содержимое приложенного файла.")
            appendLine("Верни только содержимое документа. Не добавляй приветствие, пояснение, Markdown, HTML или блоки кода.")
            appendLine("Первая строка должна быть названием документа.")
            appendLine("Если не хватает данных, используй аккуратные поля-заглушки в квадратных скобках.")
            appendLine("Используй данные профиля пользователя из системного промпта, если они подходят для стороны документа.")
        }.trim()
    }

    private fun makeDocumentFileName(userMessage: String, content: String): String {
        val titleCandidate = content.lineSequence().firstOrNull { it.isNotBlank() }
            ?: userMessage.lineSequence().firstOrNull { it.isNotBlank() }
            ?: "document"
        val safeTitle = titleCandidate
            .replace(Regex("[^A-Za-zА-Яа-я0-9 _.-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(36)
            .ifBlank { "document" }
        val date = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return "${safeTitle}_$date.docx"
    }

    private suspend fun getSystemPrompt(): String {
        return buildString {
            append(app.getString(R.string.ai_system_prompt).trimIndent())
            appendLine()
            appendLine()
            appendLine(buildProfileContext())
            appendLine()
            appendLine("Используй данные профиля только когда они реально нужны для ответа или подготовки документа. Если данных недостаточно, прямо скажи, каких данных не хватает.")
            appendLine("Не выдавай себя за адвоката и не обещай гарантированный правовой результат.")
        }.trim()
    }

    private suspend fun buildProfileContext(): String {
        val email = sessionManager.getUserEmail()
        if (email.isNullOrBlank()) {
            return "Пользователь не авторизован, данные профиля недоступны."
        }

        val profile = withContext(Dispatchers.IO) {
            runCatching {
                val dao = AppDatabase.getDatabase(app).userDao()
                ProfileRepository(dao).getUserByEmail(email)
            }.getOrNull()
        }

        if (profile == null) {
            return "Данные профиля пользователя в приложении не найдены. Email текущей сессии: $email"
        }

        val lines = mutableListOf<String>()
        if (profile.fullName.isNotBlank()) lines.add("ФИО: ${profile.fullName}")
        if (!profile.birthDate.isNullOrBlank()) lines.add("Дата рождения: ${profile.birthDate}")
        if (!profile.passportNumber.isNullOrBlank()) lines.add("Паспорт: ${profile.passportNumber}")
        if (profile.email.isNotBlank()) lines.add("Email: ${profile.email}")

        return if (lines.isEmpty()) {
            "Профиль текущего пользователя найден, но ФИО, дата рождения и паспорт не заполнены."
        } else {
            "Данные профиля текущего пользователя из локальной Room-базы приложения:\n${lines.joinToString("\n")}"
        }
    }

    private fun friendlyErrorMessage(e: Exception): String {
        return when (e) {
            is RateLimitException -> e.message ?: "Слишком много запросов. Попробуйте позже."
            is GatewayTimeoutException -> e.message ?: "Сервер не успел ответить. Попробуйте ещё раз."
            else -> e.message?.takeIf { it.isNotBlank() } ?: "Ошибка соединения. Проверьте интернет и попробуйте ещё раз."
        }
    }

    private fun extractTextFromDocx(bytes: ByteArray): String {
        val documentXml = readDocxDocumentXml(bytes) ?: return ""
        return parseWordDocumentXml(documentXml)
            .replace(Regex("[ \t\\x0B\u000C\r]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun readDocxDocumentXml(bytes: ByteArray): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name == "word/document.xml") {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun parseWordDocumentXml(xml: String): String {
        val result = StringBuilder()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "w:tab", "tab" -> result.append(' ')
                        "w:br", "br" -> result.append('\n')
                    }
                }
                XmlPullParser.TEXT -> result.append(parser.text)
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "w:p", "p" -> result.append("\n")
                    }
                }
            }
            eventType = parser.next()
        }
        return result.toString()
    }

    private fun normalizeMimeType(fileName: String, mimeType: String?): String {
        val lowerName = fileName.lowercase(Locale.US)
        return when {
            mimeType?.startsWith("image/") == true -> mimeType
            mimeType == "application/pdf" || lowerName.endsWith(".pdf") -> "application/pdf"
            mimeType == DOCX_MIME || lowerName.endsWith(".docx") -> DOCX_MIME
            lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") -> "image/jpeg"
            lowerName.endsWith(".png") -> "image/png"
            lowerName.endsWith(".webp") -> "image/webp"
            else -> mimeType ?: "application/octet-stream"
        }
    }

    private fun normalizeImageMimeType(fileName: String, mimeType: String): String {
        val lowerName = fileName.lowercase(Locale.US)
        return when {
            mimeType == "image/jpg" -> "image/jpeg"
            mimeType.startsWith("image/") -> mimeType
            lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") -> "image/jpeg"
            lowerName.endsWith(".png") -> "image/png"
            lowerName.endsWith(".webp") -> "image/webp"
            lowerName.endsWith(".gif") -> "image/gif"
            else -> mimeType
        }
    }

    private fun isImageMime(mimeType: String): Boolean = mimeType.startsWith("image/")

    private fun isPdf(fileName: String, mimeType: String): Boolean {
        return mimeType == "application/pdf" || fileName.lowercase(Locale.US).endsWith(".pdf")
    }

    private fun isDocx(fileName: String, mimeType: String): Boolean {
        return mimeType == DOCX_MIME || fileName.lowercase(Locale.US).endsWith(".docx")
    }

    private fun loadChatsFromStorage(): MutableList<Chat> {
        val rawJson = getUserChatPrefs().getString(KEY_CHATS_JSON, null) ?: return mutableListOf(Chat())
        return runCatching {
            val array = JSONArray(rawJson)
            val chats = mutableListOf<Chat>()
            for (i in 0 until array.length()) {
                val chatJson = array.getJSONObject(i)
                val messagesJson = chatJson.optJSONArray("messages") ?: JSONArray()
                val messages = mutableListOf<Message>()
                for (j in 0 until messagesJson.length()) {
                    val messageJson = messagesJson.getJSONObject(j)
                    messages.add(
                        Message(
                            text = messageJson.optString("text"),
                            isUser = messageJson.optBoolean("isUser"),
                            time = messageJson.optString("time"),
                            attachmentName = messageJson.optStringOrNull("attachmentName"),
                            attachmentMimeType = messageJson.optStringOrNull("attachmentMimeType"),
                            id = messageJson.optString("id").ifBlank { UUID.randomUUID().toString() },
                            generatedDocxFileName = messageJson.optStringOrNull("generatedDocxFileName"),
                            generatedDocxContent = messageJson.optStringOrNull("generatedDocxContent")
                        )
                    )
                }
                chats.add(
                    Chat(
                        id = chatJson.optString("id").ifBlank { UUID.randomUUID().toString() },
                        messages = messages,
                        createdAt = chatJson.optString("createdAt").ifBlank { Chat().createdAt }
                    )
                )
            }
            chats.ifEmpty { mutableListOf(Chat()) }
        }.getOrElse { mutableListOf(Chat()) }
    }

    private fun loadCurrentChatIndex(chatCount: Int): Int {
        val savedIndex = getUserChatPrefs().getInt(KEY_CURRENT_CHAT_INDEX, 0)
        return savedIndex.coerceIn(0, (chatCount - 1).coerceAtLeast(0))
    }

    private fun persistChats() {
        val chats = _chatHistory.value ?: mutableListOf(Chat())
        val currentIndex = (_currentChatIndex.value ?: 0).coerceIn(0, (chats.size - 1).coerceAtLeast(0))
        getUserChatPrefs().edit()
            .putString(KEY_CHATS_JSON, chatsToJson(chats).toString())
            .putInt(KEY_CURRENT_CHAT_INDEX, currentIndex)
            .apply()
    }

    private fun chatsToJson(chats: List<Chat>): JSONArray {
        return JSONArray().apply {
            chats.forEach { chat ->
                put(JSONObject().apply {
                    put("id", chat.id)
                    put("createdAt", chat.createdAt)
                    put("messages", JSONArray().apply {
                        chat.messages.forEach { message ->
                            put(JSONObject().apply {
                                put("id", message.id)
                                put("text", message.text)
                                put("isUser", message.isUser)
                                put("time", message.time)
                                put("attachmentName", message.attachmentName)
                                put("attachmentMimeType", message.attachmentMimeType)
                                put("generatedDocxFileName", message.generatedDocxFileName)
                                put("generatedDocxContent", message.generatedDocxContent)
                            })
                        }
                    })
                })
            }
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        if (isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }

    class RateLimitException(message: String) : Exception(message)
    class GatewayTimeoutException(message: String) : Exception(message)

    private object AppConfig {
        val openRouterApiKey: String by lazy { buildConfigString("OPENROUTER_API_KEY") }
        val openRouterModel: String by lazy { buildConfigString("OPENROUTER_MODEL", "openai/gpt-4o-mini") }
        val openRouterVisionModel: String by lazy { buildConfigString("OPENROUTER_VISION_MODEL", openRouterModel) }

        private fun buildConfigString(name: String, defaultValue: String = ""): String {
            val rawValue = try {
                val field = BuildConfig::class.java.getField(name)
                field.get(null)?.toString() ?: defaultValue
            } catch (_: Exception) {
                defaultValue
            }
            return cleanBuildConfigValue(rawValue)
        }

        private fun cleanBuildConfigValue(value: String): String {
            var result = value.trim()
            if (result.length >= 2) {
                val first = result.first()
                val last = result.last()
                if (first == '"' && last == '"') {
                    result = result.substring(1, result.length - 1).trim()
                }
            }
            val inlineCommentIndex = Regex("\\s+#").find(result)?.range?.first
            if (inlineCommentIndex != null) {
                result = result.substring(0, inlineCommentIndex).trim()
            }
            return result
        }
    }

    companion object {
        private const val CHAT_PREFS_NAME = "chat_local_storage"
        private const val KEY_CHATS_JSON = "chats_json"
        private const val KEY_CURRENT_CHAT_INDEX = "current_chat_index"
        private const val MAX_DOCX_CHARS = 60_000
        private const val MAX_CONTEXT_MESSAGE_CHARS = 4_000
        private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
