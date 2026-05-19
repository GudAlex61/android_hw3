package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

class ChatViewModel : ViewModel() {

    private val _chatHistory = MutableLiveData<MutableList<Chat>>(mutableListOf(Chat()))
    val chatHistory: LiveData<MutableList<Chat>> = _chatHistory

    private val _currentChatIndex = MutableLiveData(0)
    val currentChatIndex: LiveData<Int> = _currentChatIndex

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun getCurrentChat(): Chat {
        return _chatHistory.value?.getOrElse(_currentChatIndex.value ?: 0) { Chat() } ?: Chat()
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
        val currentMessages = getCurrentChat().messages
        if (currentMessages.isNotEmpty()) {
            val newChat = Chat()
            _chatHistory.value?.add(newChat)
            _currentChatIndex.value = (_chatHistory.value?.size ?: 1) - 1
            _chatHistory.value = _chatHistory.value
        }
    }

    fun switchToChat(index: Int) {
        if (index in 0 until (_chatHistory.value?.size ?: 0) && index != _currentChatIndex.value) {
            _currentChatIndex.value = index
        }
    }

    private fun addMessage(message: Message) {
        getCurrentChat().messages.add(message)
        _chatHistory.value = _chatHistory.value
    }

    private fun getAIResponse(userMessage: String) {
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val payload = buildTextPayload(userMessage)
                val aiResponse = executeOpenRouterRequestWithRetry(payload)

                withContext(Dispatchers.Main) {
                    addMessage(Message(text = aiResponse, isUser = false))
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
            try {
                val prompt = buildFilePrompt(fileName, mimeType, userPrompt)
                val payload = when {
                    isImageMime(mimeType) -> buildImagePayload(fileName, bytes, prompt)
                    isPdf(fileName, mimeType) -> buildPdfPayload(fileName, bytes, prompt)
                    isDocx(fileName, mimeType) -> buildDocxPayload(fileName, bytes, prompt)
                    else -> throw IllegalArgumentException("Поддерживаются только изображения, PDF и DOCX")
                }

                val aiResponse = executeOpenRouterRequestWithRetry(payload)

                withContext(Dispatchers.Main) {
                    addMessage(Message(text = aiResponse, isUser = false))
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

    /**
     * Выполняет запрос с повторными попытками при ошибках 429 (rate limit) и 504 (таймаут).
     * Стратегия: до 3 попыток с экспоненциальной задержкой 2с → 4с → 8с.
     */
    private suspend fun executeOpenRouterRequestWithRetry(
        payload: JSONObject,
        maxAttempts: Int = 1
    ): String {
        var lastException: Exception? = null

        for (attempt in 1..maxAttempts) {
            try {
                return executeOpenRouterRequest(payload)
            } catch (e: RateLimitException) {
                lastException = e
                if (attempt < maxAttempts) {
                    // Экспоненциальная задержка: 2с, 4с, 8с
                    val delayMs = (2000L * (1 shl (attempt - 1)))
                    delay(delayMs)
                }
            } catch (e: GatewayTimeoutException) {
                lastException = e
                if (attempt < maxAttempts) {
                    val delayMs = (3000L * (1 shl (attempt - 1)))
                    delay(delayMs)
                }
            }
            // Остальные исключения бросаем сразу без retry
        }

        throw lastException ?: IllegalStateException("Неизвестная ошибка после $maxAttempts попыток")
    }

    private fun buildTextPayload(userMessage: String): JSONObject {
        return JSONObject().apply {
            put("model", AppConfig.openRouterModel)
            put("max_tokens", 1200)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", getSystemPrompt())
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
            })
        }
    }

    private fun buildImagePayload(fileName: String, originalBytes: ByteArray, prompt: String): JSONObject {
        ensureS3Configured()
        val jpegBytes = compressImageHighQuality(originalBytes)
        val key = "mobile_uploads/images/${UUID.randomUUID().toString().replace("-", "")}.jpg"
        val uploaded = uploadBytesToS3AndGetPresignedUrl(jpegBytes, key, "image/jpeg")
        scheduleS3DeleteIfNeeded(uploaded.key)

        return JSONObject().apply {
            put("model", AppConfig.openRouterVisionModel)
            put("max_tokens", 1400)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", getSystemPrompt())
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt.ifBlank { "Опиши изображение и извлеки весь видимый текст." })
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", uploaded.presignedUrl)
                            })
                        })
                    })
                })
            })
        }
    }

    private fun buildPdfPayload(fileName: String, bytes: ByteArray, prompt: String): JSONObject {
        ensureS3Configured()
        val key = "mobile_uploads/documents/${UUID.randomUUID().toString().replace("-", "")}.pdf"
        val uploaded = uploadBytesToS3AndGetPresignedUrl(bytes, key, "application/pdf")
        scheduleS3DeleteIfNeeded(uploaded.key)

        return JSONObject().apply {
            put("model", AppConfig.openRouterModel)
            put("max_tokens", 1600)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", getSystemPrompt())
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt.ifBlank { "Проанализируй PDF-документ. Извлеки ключевые факты, юридически значимые условия, риски и краткий вывод." })
                        })
                        put(JSONObject().apply {
                            put("type", "file")
                            put("file", JSONObject().apply {
                                put("filename", fileName)
                                put("file_data", uploaded.presignedUrl)
                            })
                        })
                    })
                })
            })
            put("plugins", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "file-parser")
                    put("pdf", JSONObject().apply {
                        put("engine", "cloudflare-ai")
                    })
                })
            })
        }
    }

    private fun buildDocxPayload(fileName: String, bytes: ByteArray, prompt: String): JSONObject {
        ensureS3Configured()
        val key = "mobile_uploads/documents/${UUID.randomUUID().toString().replace("-", "")}.docx"
        val uploaded = uploadBytesToS3AndGetPresignedUrl(
            bytes,
            key,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
        scheduleS3DeleteIfNeeded(uploaded.key)

        val extractedText = extractTextFromDocx(bytes)
        if (extractedText.isBlank()) {
            throw IllegalArgumentException("DOCX загружен, но текст из него извлечь не удалось")
        }

        val textForModel = extractedText.take(MAX_DOCX_CHARS)
        val finalPrompt = buildString {
            append(prompt.ifBlank { "Проанализируй DOCX-документ. Извлеки ключевые факты, юридически значимые условия, риски и краткий вывод." })
            append("\n\nИмя файла: ").append(fileName)
            append("\nВременная ссылка на исходный файл в S3: ").append(uploaded.presignedUrl)
            append("\n\nТекст DOCX:\n").append(textForModel)
            if (extractedText.length > MAX_DOCX_CHARS) {
                append("\n\n[Текст обрезан до первых ").append(MAX_DOCX_CHARS).append(" символов из-за ограничения контекста.]")
            }
        }

        return buildTextPayload(finalPrompt)
    }

    private fun executeOpenRouterRequest(payload: JSONObject): String {
        val apiKey = AppConfig.openRouterApiKey
        if (apiKey.isBlank()) {
            throw IllegalStateException("OPENROUTER_API_KEY не задан в BuildConfig/local.properties")
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()

            // Выбрасываем специализированные исключения для retry-логики
            if (response.code == 429) {
                throw RateLimitException("Превышен лимит запросов (429): ${responseBody?.take(200) ?: ""}")
            }
            if (response.code == 504) {
                throw GatewayTimeoutException("Сервер не ответил вовремя (504): ${responseBody?.take(200) ?: ""}")
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("OpenRouter API вернул ${response.code}: ${responseBody?.take(250) ?: "пустой ответ"}")
            }
            return parseAIResponse(responseBody)
        }
    }

    private fun parseAIResponse(responseBody: String?): String {
        return try {
            val json = JSONObject(responseBody ?: "")
            if (json.has("error")) {
                val error = json.getJSONObject("error")
                return error.optString("message", "Ошибка API")
            }
            val choices = json.getJSONArray("choices")
            if (choices.length() > 0) {
                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.getJSONObject("message")
                message.getString("content").trim()
            } else {
                "Не удалось получить ответ"
            }
        } catch (e: Exception) {
            "Ошибка обработки ответа"
        }
    }

    /**
     * Переводит техническое исключение в понятное пользователю сообщение.
     */
    private fun friendlyErrorMessage(e: Exception): String {
        return when (e) {
            is RateLimitException ->
                "Сервис временно перегружен. Попробуйте через несколько секунд."
            is GatewayTimeoutException ->
                "Сервер не успел ответить. Попробуйте повторить запрос — обычно помогает с 1–2 попытки."
            else -> e.message ?: "Неизвестная ошибка"
        }
    }

    private fun buildFilePrompt(fileName: String, mimeType: String, userPrompt: String): String {
        return if (userPrompt.isNotBlank()) {
            userPrompt
        } else {
            when {
                isImageMime(mimeType) -> "Опиши изображение, извлеки весь читаемый текст и укажи, есть ли на нём юридически значимая информация. Файл: $fileName"
                isPdf(fileName, mimeType) -> "Проанализируй PDF-документ как юридический консультант. Кратко выдели суть, стороны, даты, суммы, обязательства, риски и что стоит проверить. Файл: $fileName"
                isDocx(fileName, mimeType) -> "Проанализируй DOCX-документ как юридический консультант. Кратко выдели суть, стороны, даты, суммы, обязательства, риски и что стоит проверить. Файл: $fileName"
                else -> "Проанализируй файл: $fileName"
            }
        }
    }

    private fun getSystemPrompt(): String {
        return """
            Ты юридический консультант в чате Android-приложения AI Lawyer.

            КРИТИЧЕСКИ ВАЖНО: приложение отображает только чистый текст.
            Не используй Markdown, HTML, таблицы, кодовые блоки и декоративное форматирование.
            Списки делай только через цифры с точкой: 1. 2. 3.

            При анализе документов и фотографий:
            1. Сначала кратко опиши, что это за материал.
            2. Выдели юридически значимые факты: стороны, даты, суммы, обязательства, сроки, подписи, реквизиты.
            3. Отдельно перечисли риски и неясные места.
            4. Дай практический вывод и следующие шаги.
            5. Если данных недостаточно или текст не читается, прямо скажи об этом.

            Не выдавай себя за адвоката и не обещай гарантированный правовой результат.
        """.trimIndent()
    }

    private fun compressImageHighQuality(imageData: ByteArray): ByteArray {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size) ?: return imageData
            val maxDim = maxOf(bitmap.width, bitmap.height)
            val scaledBitmap = if (maxDim > AppConfig.imageMaxSize) {
                val ratio = AppConfig.imageMaxSize.toFloat() / maxDim.toFloat()
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
                    (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }

            val output = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, AppConfig.imageQuality, output)
            if (scaledBitmap !== bitmap) scaledBitmap.recycle()
            bitmap.recycle()
            output.toByteArray()
        } catch (e: Exception) {
            imageData
        }
    }

    private fun uploadBytesToS3AndGetPresignedUrl(bytes: ByteArray, key: String, contentType: String): UploadedS3Object {
        putObjectToS3(key, bytes, contentType)
        return UploadedS3Object(key = key, presignedUrl = createPresignedGetUrl(key, AppConfig.s3PresignedExpiration))
    }

    private fun ensureS3Configured() {
        if (!AppConfig.s3Enabled) {
            throw IllegalStateException("S3_ENABLED отключён")
        }
        if (AppConfig.s3EndpointUrl.isBlank() || AppConfig.s3AccessKey.isBlank() || AppConfig.s3SecretKey.isBlank() || AppConfig.s3Bucket.isBlank()) {
            throw IllegalStateException("S3_ENDPOINT_URL/S3_ACCESS_KEY/S3_SECRET_KEY/S3_BUCKET не заданы")
        }
        if (!AppConfig.s3AccessKey.matches(Regex("^[A-Za-z0-9]+$"))) {
            throw IllegalStateException("S3_ACCESS_KEY выглядит некорректно: уберите кавычки, пробелы и комментарии после значения в local.properties")
        }
        if (AppConfig.s3Region.contains(' ') || AppConfig.s3Region.contains('#')) {
            throw IllegalStateException("S3_REGION выглядит некорректно: в local.properties оставьте только us-east-005 без комментария в этой же строке")
        }
    }

    private fun putObjectToS3(key: String, bytes: ByteArray, contentType: String) {
        val uploadUrl = createPresignedPutUrl(key, AppConfig.s3PresignedExpiration)

        val request = Request.Builder()
            .url(uploadUrl)
            .put(bytes.toRequestBody(contentType.toMediaType()))
            .addHeader("Content-Type", contentType)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string()?.take(500) ?: ""
                throw IllegalStateException("S3 upload failed: ${response.code} $body")
            }
        }
    }

    private fun createPresignedPutUrl(key: String, expiresInSeconds: Int): String {
        val endpoint = AppConfig.s3EndpointUrl.trimEnd('/')
        val uri = URI(endpoint)
        val host = buildHostHeader(uri)
        val now = Date()
        val amzDate = formatAmzDate(now)
        val dateStamp = formatDateStamp(now)
        val credentialScope = "$dateStamp/${AppConfig.s3Region}/s3/aws4_request"
        val canonicalUri = "/${awsEncode(AppConfig.s3Bucket, true)}/${awsEncode(key, false)}"
        val signedHeaders = "host"

        val queryParams = linkedMapOf(
            "X-Amz-Algorithm" to "AWS4-HMAC-SHA256",
            "X-Amz-Credential" to "${AppConfig.s3AccessKey}/$credentialScope",
            "X-Amz-Date" to amzDate,
            "X-Amz-Expires" to expiresInSeconds.coerceAtLeast(1).toString(),
            "X-Amz-SignedHeaders" to signedHeaders
        ).toSortedMap()

        val canonicalQuery = queryParams.entries.joinToString("&") { (name, value) ->
            "${awsEncode(name, true)}=${awsEncode(value, true)}"
        }
        val canonicalHeaders = "host:$host\n"
        val canonicalRequest = listOf(
            "PUT",
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            "UNSIGNED-PAYLOAD"
        ).joinToString("\n")
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256",
            amzDate,
            credentialScope,
            sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8))
        ).joinToString("\n")
        val signature = hmacSha256Hex(getSignatureKey(AppConfig.s3SecretKey, dateStamp, AppConfig.s3Region, "s3"), stringToSign)
        return "$endpoint$canonicalUri?$canonicalQuery&X-Amz-Signature=$signature"
    }

    private fun createPresignedGetUrl(key: String, expiresInSeconds: Int): String {
        val endpoint = AppConfig.s3EndpointUrl.trimEnd('/')
        val uri = URI(endpoint)
        val host = buildHostHeader(uri)
        val now = Date()
        val amzDate = formatAmzDate(now)
        val dateStamp = formatDateStamp(now)
        val credentialScope = "$dateStamp/${AppConfig.s3Region}/s3/aws4_request"
        val canonicalUri = "/${awsEncode(AppConfig.s3Bucket, true)}/${awsEncode(key, false)}"
        val signedHeaders = "host"

        val queryParams = linkedMapOf(
            "X-Amz-Algorithm" to "AWS4-HMAC-SHA256",
            "X-Amz-Credential" to "${AppConfig.s3AccessKey}/$credentialScope",
            "X-Amz-Date" to amzDate,
            "X-Amz-Expires" to expiresInSeconds.coerceAtLeast(1).toString(),
            "X-Amz-SignedHeaders" to signedHeaders
        ).toSortedMap()

        val canonicalQuery = queryParams.entries.joinToString("&") { (name, value) ->
            "${awsEncode(name, true)}=${awsEncode(value, true)}"
        }
        val canonicalHeaders = "host:$host\n"
        val canonicalRequest = listOf(
            "GET",
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            "UNSIGNED-PAYLOAD"
        ).joinToString("\n")
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256",
            amzDate,
            credentialScope,
            sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8))
        ).joinToString("\n")
        val signature = hmacSha256Hex(getSignatureKey(AppConfig.s3SecretKey, dateStamp, AppConfig.s3Region, "s3"), stringToSign)
        return "$endpoint$canonicalUri?$canonicalQuery&X-Amz-Signature=$signature"
    }

    private fun deleteObjectFromS3(key: String) {
        val deleteUrl = createPresignedDeleteUrl(key, AppConfig.s3PresignedExpiration)
        val request = Request.Builder()
            .url(deleteUrl)
            .delete()
            .build()

        client.newCall(request).execute().close()
    }

    private fun createPresignedDeleteUrl(key: String, expiresInSeconds: Int): String {
        val endpoint = AppConfig.s3EndpointUrl.trimEnd('/')
        val uri = URI(endpoint)
        val host = buildHostHeader(uri)
        val now = Date()
        val amzDate = formatAmzDate(now)
        val dateStamp = formatDateStamp(now)
        val credentialScope = "$dateStamp/${AppConfig.s3Region}/s3/aws4_request"
        val canonicalUri = "/${awsEncode(AppConfig.s3Bucket, true)}/${awsEncode(key, false)}"
        val signedHeaders = "host"

        val queryParams = linkedMapOf(
            "X-Amz-Algorithm" to "AWS4-HMAC-SHA256",
            "X-Amz-Credential" to "${AppConfig.s3AccessKey}/$credentialScope",
            "X-Amz-Date" to amzDate,
            "X-Amz-Expires" to expiresInSeconds.coerceAtLeast(1).toString(),
            "X-Amz-SignedHeaders" to signedHeaders
        ).toSortedMap()

        val canonicalQuery = queryParams.entries.joinToString("&") { (name, value) ->
            "${awsEncode(name, true)}=${awsEncode(value, true)}"
        }
        val canonicalHeaders = "host:$host\n"
        val canonicalRequest = listOf(
            "DELETE",
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            "UNSIGNED-PAYLOAD"
        ).joinToString("\n")
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256",
            amzDate,
            credentialScope,
            sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8))
        ).joinToString("\n")
        val signature = hmacSha256Hex(getSignatureKey(AppConfig.s3SecretKey, dateStamp, AppConfig.s3Region, "s3"), stringToSign)
        return "$endpoint$canonicalUri?$canonicalQuery&X-Amz-Signature=$signature"
    }

    private fun scheduleS3DeleteIfNeeded(key: String) {
        val delaySeconds = AppConfig.s3AutoDeleteAfter
        if (delaySeconds <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            delay(delaySeconds * 1000L)
            runCatching { deleteObjectFromS3(key) }
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
            mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || lowerName.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") -> "image/jpeg"
            lowerName.endsWith(".png") -> "image/png"
            lowerName.endsWith(".webp") -> "image/webp"
            else -> mimeType ?: "application/octet-stream"
        }
    }

    private fun isImageMime(mimeType: String): Boolean = mimeType.startsWith("image/")

    private fun isPdf(fileName: String, mimeType: String): Boolean {
        return mimeType == "application/pdf" || fileName.lowercase(Locale.US).endsWith(".pdf")
    }

    private fun isDocx(fileName: String, mimeType: String): Boolean {
        return mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || fileName.lowercase(Locale.US).endsWith(".docx")
    }

    private fun buildHostHeader(uri: URI): String {
        val host = uri.host ?: throw IllegalArgumentException("Некорректный S3_ENDPOINT_URL")
        return if (uri.port != -1) "$host:${uri.port}" else host
    }

    private fun formatAmzDate(date: Date): String {
        return SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(date)
    }

    private fun formatDateStamp(date: Date): String {
        return SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(date)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        return hmacSha256(key, data).toHex()
    }

    private fun getSignatureKey(secretKey: String, dateStamp: String, regionName: String, serviceName: String): ByteArray {
        val kDate = hmacSha256(("AWS4$secretKey").toByteArray(StandardCharsets.UTF_8), dateStamp)
        val kRegion = hmacSha256(kDate, regionName)
        val kService = hmacSha256(kRegion, serviceName)
        return hmacSha256(kService, "aws4_request")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun awsEncode(value: String, encodeSlash: Boolean): String {
        val result = StringBuilder()
        for (byte in value.toByteArray(StandardCharsets.UTF_8)) {
            val unsigned = byte.toInt() and 0xff
            val char = unsigned.toChar()
            val isUnreserved = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '-' || char == '_' || char == '.' || char == '~'
            when {
                isUnreserved -> result.append(char)
                char == '/' && !encodeSlash -> result.append('/')
                else -> result.append("%${"%02X".format(unsigned)}")
            }
        }
        return result.toString()
    }

    // Специализированные исключения для retry-логики
    class RateLimitException(message: String) : Exception(message)
    class GatewayTimeoutException(message: String) : Exception(message)

    private data class UploadedS3Object(val key: String, val presignedUrl: String)

    private object AppConfig {
        val openRouterApiKey: String by lazy { buildConfigString("OPENROUTER_API_KEY") }
        val openRouterModel: String by lazy { buildConfigString("OPENROUTER_MODEL", "google/gemini-2.0-flash-001") }
        val openRouterVisionModel: String by lazy { buildConfigString("OPENROUTER_VISION_MODEL", openRouterModel) }

        val s3Enabled: Boolean by lazy { buildConfigString("S3_ENABLED", "1") == "1" }
        val s3EndpointUrl: String by lazy { buildConfigString("S3_ENDPOINT_URL") }
        val s3Region: String by lazy { buildConfigString("S3_REGION", "us-east-005").ifBlank { "us-east-005" } }
        val s3AccessKey: String by lazy { buildConfigString("S3_ACCESS_KEY") }
        val s3SecretKey: String by lazy { buildConfigString("S3_SECRET_KEY") }
        val s3Bucket: String by lazy { buildConfigString("S3_BUCKET") }
        val s3PresignedExpiration: Int by lazy { buildConfigInt("S3_PRESIGNED_EXPIRATION", 600) }
        val s3AutoDeleteAfter: Int by lazy { buildConfigInt("S3_AUTO_DELETE_AFTER", 0) }

        val imageMaxSize: Int by lazy { buildConfigInt("IMAGE_MAX_SIZE", 1600) }
        val imageQuality: Int by lazy { buildConfigInt("IMAGE_QUALITY", 90).coerceIn(1, 100) }

        private fun buildConfigString(name: String, defaultValue: String = ""): String {
            val rawValue = try {
                val field = BuildConfig::class.java.getField(name)
                field.get(null)?.toString() ?: defaultValue
            } catch (_: Exception) {
                defaultValue
            }
            return cleanBuildConfigValue(rawValue)
        }

        private fun buildConfigInt(name: String, defaultValue: Int): Int {
            return buildConfigString(name, defaultValue.toString()).toIntOrNull() ?: defaultValue
        }

        private fun cleanBuildConfigValue(value: String): String {
            var result = value.trim()

            if (result.length >= 2) {
                val first = result.first()
                val last = result.last()
                if ((first == '"' && last == '"')) {
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
        private const val MAX_DOCX_CHARS = 60_000
    }
}
