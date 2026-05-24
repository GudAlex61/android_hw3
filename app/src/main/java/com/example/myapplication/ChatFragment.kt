package com.example.myapplication

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatFragment : Fragment() {

    private val viewModel: ChatViewModel by activityViewModels()

    private lateinit var messagesContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: TextView
    private lateinit var newChatButton: TextView
    private lateinit var attachButton: TextView
    private lateinit var menuButton: TextView

    private lateinit var attachmentPreviewContainer: LinearLayout
    private lateinit var attachmentIcon: TextView
    private lateinit var attachmentName: TextView
    private lateinit var removeAttachmentButton: TextView

    private lateinit var welcomeContainer: LinearLayout
    private lateinit var chatContainer: FrameLayout

    private lateinit var historyMenu: LinearLayout
    private lateinit var closeMenuButton: TextView
    private lateinit var chatHistoryList: ListView
    private lateinit var historyAdapter: ChatHistoryAdapter

    private var pendingAttachment: PendingAttachment? = null
    private var pendingDocx: PendingDocx? = null

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleSelectedFile(it) }
    }

    private val createDocxLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(DOCX_MIME)
    ) { uri ->
        val document = pendingDocx
        pendingDocx = null
        if (uri != null && document != null) {
            saveDocxToUri(uri, document.bytes)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        initMessagesContainer()
        setupAttachmentPreviewStyle()
        setupMessageInput()
        setupClickListeners()
        setupHistoryMenu()
        observeViewModel()
        updateUIForCurrentChat()
    }

    private fun bindViews(view: View) {
        messageInput = view.findViewById(R.id.messageInput)
        sendButton = view.findViewById(R.id.sendButton)
        newChatButton = view.findViewById(R.id.newChatButton)
        attachButton = view.findViewById(R.id.attachButton)
        menuButton = view.findViewById(R.id.menuButton)

        attachmentPreviewContainer = view.findViewById(R.id.attachmentPreviewContainer)
        attachmentIcon = view.findViewById(R.id.attachmentIcon)
        attachmentName = view.findViewById(R.id.attachmentName)
        removeAttachmentButton = view.findViewById(R.id.removeAttachmentButton)

        welcomeContainer = view.findViewById(R.id.welcomeContainer)
        chatContainer = view.findViewById(R.id.messagesContainer)

        historyMenu = view.findViewById(R.id.historyMenu)
        closeMenuButton = view.findViewById(R.id.closeMenuButton)
        chatHistoryList = view.findViewById(R.id.chatHistoryList)
    }

    private fun observeViewModel() {
        viewModel.chatHistory.observe(viewLifecycleOwner) {
            updateHistoryList()
            updateUIForCurrentChat()
        }

        viewModel.currentChatIndex.observe(viewLifecycleOwner) {
            updateHistoryList()
            updateUIForCurrentChat()
            clearPendingAttachment()
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            sendButton.text = if (loading) "⌛" else "↑"
            sendButton.isEnabled = !loading
            attachButton.isEnabled = !loading
            attachButton.alpha = if (loading) 0.5f else 1f
        }

        viewModel.docxExportRequest.observe(viewLifecycleOwner) { request ->
            if (request != null) {
                startDocxSave(
                    fileName = request.fileName,
                    content = request.content
                )
                viewModel.consumeDocxExportRequest()
            }
        }
    }

    private fun initMessagesContainer() {
        chatContainer.removeAllViews()

        scrollView = ScrollView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        messagesContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        scrollView.addView(messagesContainer)
        chatContainer.addView(scrollView)
    }

    private fun setupAttachmentPreviewStyle() {
        attachmentPreviewContainer.background = roundedBackground(0xFFFFFFFF.toInt(), dpToPx(16))
    }

    private fun setupMessageInput() {
        messageInput.setSingleLine(false)
        messageInput.minLines = 1
        messageInput.maxLines = 5
        messageInput.isVerticalScrollBarEnabled = true
        messageInput.overScrollMode = View.OVER_SCROLL_NEVER
        messageInput.setRawInputType(
            InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        )
        messageInput.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI

        messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (::scrollView.isInitialized && chatContainer.visibility == View.VISIBLE) {
                    scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                }
            }
        })
    }

    private fun setupClickListeners() {
        sendButton.setOnClickListener { sendMessage() }

        newChatButton.setOnClickListener {
            clearPendingAttachment()
            if (viewModel.getCurrentChat().messages.isEmpty()) {
                Toast.makeText(requireContext(), R.string.error_current_chat_empty, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.createNewChat()
                Toast.makeText(requireContext(), R.string.toast_new_chat_created, Toast.LENGTH_SHORT).show()
            }
        }

        menuButton.setOnClickListener { showHistoryMenu() }
        removeAttachmentButton.setOnClickListener { clearPendingAttachment() }

        attachButton.setOnClickListener {
            filePickerLauncher.launch(
                arrayOf(
                    "image/*",
                    "application/pdf",
                    DOCX_MIME
                )
            )
        }

        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }
    }

    private fun setupHistoryMenu() {
        closeMenuButton.setOnClickListener { hideHistoryMenu() }
        chatHistoryList.setOnItemClickListener { _, _, position, _ ->
            viewModel.switchToChat(position)
            hideHistoryMenu()
        }
    }

    private fun showHistoryMenu() {
        updateHistoryList()
        historyMenu.visibility = View.VISIBLE
        historyMenu.bringToFront()
    }

    private fun hideHistoryMenu() {
        historyMenu.visibility = View.GONE
    }

    private fun updateHistoryList() {
        if (!::chatHistoryList.isInitialized) return
        val currentIndex = viewModel.currentChatIndex.value ?: 0
        val chats = viewModel.chatHistory.value ?: listOf()
        historyAdapter = ChatHistoryAdapter(requireContext(), chats, currentIndex)
        chatHistoryList.adapter = historyAdapter
    }

    private fun updateUIForCurrentChat() {
        if (!::messagesContainer.isInitialized) return

        val currentChat = viewModel.getCurrentChat()
        clearChatUI()

        if (currentChat.messages.isEmpty()) {
            welcomeContainer.visibility = View.VISIBLE
            chatContainer.visibility = View.GONE
            return
        }

        welcomeContainer.visibility = View.GONE
        chatContainer.visibility = View.VISIBLE
        currentChat.messages.forEach { message -> addMessageToUI(message) }
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        val attachment = pendingAttachment

        if (attachment != null) {
            messageInput.text?.clear()
            clearPendingAttachment()
            viewModel.sendFile(attachment.name, attachment.mimeType, attachment.bytes, text)
            return
        }

        if (text.isNotBlank()) {
            messageInput.text?.clear()
            viewModel.sendMessage(text, true)
        }
    }

    private fun handleSelectedFile(uri: Uri) {
        val fileName = getFileName(uri) ?: "file"
        val mimeType = requireContext().contentResolver.getType(uri) ?: guessMimeType(fileName)

        if (!isSupportedFile(fileName, mimeType)) {
            Toast.makeText(requireContext(), "Поддерживаются только фото, PDF и DOCX", Toast.LENGTH_LONG).show()
            return
        }

        val bytes = runCatching { readUriBytes(uri) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            Toast.makeText(requireContext(), "Не удалось прочитать файл", Toast.LENGTH_LONG).show()
            return
        }

        if (bytes.size > MAX_FILE_SIZE_BYTES) {
            Toast.makeText(requireContext(), "Файл слишком большой. Максимум 20 МБ", Toast.LENGTH_LONG).show()
            return
        }

        pendingAttachment = PendingAttachment(fileName, mimeType, bytes)
        showPendingAttachment()
    }

    private fun showPendingAttachment() {
        val attachment = pendingAttachment ?: return
        attachmentIcon.text = iconForMime(attachment.name, attachment.mimeType)
        attachmentName.text = attachment.name
        attachmentPreviewContainer.visibility = View.VISIBLE
    }

    private fun clearPendingAttachment() {
        pendingAttachment = null
        if (::attachmentPreviewContainer.isInitialized) {
            attachmentPreviewContainer.visibility = View.GONE
            attachmentName.text = ""
            attachmentIcon.text = "📎"
        }
    }

    private fun readUriBytes(uri: Uri): ByteArray {
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
        throw IllegalArgumentException("InputStream is null")
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex != -1) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun addMessageToUI(message: Message) {
        val messageLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
                bottomMargin = dpToPx(8)
            }
        }

        val maxBubbleWidth = getMaxBubbleWidth(message.isUser)
        val bubbleContainer = MaxWidthLinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            maxWidthPx = maxBubbleWidth
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            minimumWidth = dpToPx(48)
        }

        if (message.attachmentName != null) {
            bubbleContainer.addView(
                createAttachmentChip(
                    fileName = message.attachmentName,
                    mimeType = message.attachmentMimeType,
                    isUser = message.isUser
                )
            )
        }

        if (message.text.isNotBlank()) {
            bubbleContainer.addView(createMessageText(message, maxBubbleWidth))
        }

        if (!message.isUser && message.hasGeneratedDocx()) {
            bubbleContainer.addView(createGeneratedDocxCard(message))
        }

        val timeText = TextView(requireContext()).apply {
            text = message.time
            textSize = 10f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.message_time_text))
            setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
        }

        val textContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        textContainer.addView(bubbleContainer)
        textContainer.addView(timeText)

        if (message.isUser) {
            messageLayout.gravity = Gravity.END
            messageLayout.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0).apply { weight = 1f }
            })
            bubbleContainer.background = roundedBackground(0xFFF59E0B.toInt(), dpToPx(18))
            messageLayout.addView(textContainer)
        } else {
            messageLayout.gravity = Gravity.START
            val botIcon = TextView(requireContext()).apply {
                text = getString(R.string.ai_bot_emoji)
                textSize = 24f
                setPadding(0, dpToPx(4), dpToPx(8), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            messageLayout.addView(botIcon)
            bubbleContainer.background = roundedBackground(0xFFE5E7EB.toInt(), dpToPx(18))
            messageLayout.addView(textContainer)
            messageLayout.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0).apply { weight = 1f }
            })
        }

        messagesContainer.addView(messageLayout)
    }

    private fun createMessageText(message: Message, maxBubbleWidth: Int): TextView {
        return TextView(requireContext()).apply {
            text = message.text
            textSize = 16f
            setTextColor(if (message.isUser) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
            setLineSpacing(dpToPx(2).toFloat(), 1.0f)
            setHorizontallyScrolling(false)
            maxWidth = maxBubbleWidth - dpToPx(28)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            if (message.attachmentName != null) {
                setPadding(0, dpToPx(8), 0, 0)
            }
        }
    }

    private fun createAttachmentChip(fileName: String, mimeType: String?, isUser: Boolean): View {
        val chip = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            background = roundedBackground(if (isUser) 0x22FFFFFF else 0xFFFFFFFF.toInt(), dpToPx(14))
        }

        val icon = TextView(requireContext()).apply {
            text = iconForMime(fileName, mimeType)
            textSize = 22f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(30), dpToPx(30))
        }

        val name = TextView(requireContext()).apply {
            text = fileName
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isUser) 0xFFFFFFFF.toInt() else 0xFF111827.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                leftMargin = dpToPx(8)
            }
        }

        chip.addView(icon)
        chip.addView(name)
        return chip
    }

    private fun createGeneratedDocxCard(message: Message): View {
        val fileName = message.generatedDocxFileName ?: makeDocxFileName("document")

        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            background = roundedBackground(0xFFFFFFFF.toInt(), dpToPx(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(10)
            }
        }

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val icon = TextView(requireContext()).apply {
            text = "📄"
            textSize = 24f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32))
        }

        val title = TextView(requireContext()).apply {
            text = fileName
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF111827.toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                leftMargin = dpToPx(8)
            }
        }

        row.addView(icon)
        row.addView(title)
        card.addView(row)
        card.addView(createSaveDocxButton(message))

        return card
    }

    private fun createSaveDocxButton(message: Message): TextView {
        return TextView(requireContext()).apply {
            text = "Скачать DOCX"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), dpToPx(9), dpToPx(12), dpToPx(9))
            background = roundedBackground(0xFFF59E0B.toInt(), dpToPx(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(10)
            }
            setOnClickListener { prepareAndSaveDocx(message) }
        }
    }

    private fun prepareAndSaveDocx(message: Message) {
        val content = message.generatedDocxContent ?: message.text
        val fileName = message.generatedDocxFileName ?: makeDocxFileName(content)
        startDocxSave(fileName, content)
    }

    private fun startDocxSave(fileName: String, content: String) {
        val title = content.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "Документ"
        val bytes = DocxBuilder.createSimpleDocx(title = title, text = content)
        pendingDocx = PendingDocx(fileName, bytes)
        createDocxLauncher.launch(fileName)
    }

    private fun saveDocxToUri(uri: Uri, bytes: ByteArray) {
        val saved = runCatching {
            requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
            } ?: error("OutputStream is null")
        }.isSuccess

        val text = if (saved) "DOCX сохранён" else "Не удалось сохранить DOCX"
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    private fun makeDocxFileName(title: String): String {
        val safeTitle = title.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .replace(Regex("[^A-Za-zА-Яа-я0-9 _.-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(36)
            .ifBlank { "document" }
        val date = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return "${safeTitle}_$date.docx"
    }

    private fun getMaxBubbleWidth(isUser: Boolean): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        val horizontalPadding = dpToPx(32)
        val botIconWidth = if (isUser) 0 else dpToPx(40)
        val available = screenWidth - horizontalPadding - botIconWidth
        return (available * 0.88f).toInt().coerceAtLeast(dpToPx(220))
    }

    private fun iconForMime(fileName: String, mimeType: String?): String {
        val lower = fileName.lowercase(Locale.US)
        return when {
            mimeType?.startsWith("image/") == true || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") -> "🖼️"
            mimeType == "application/pdf" || lower.endsWith(".pdf") -> "📕"
            mimeType == DOCX_MIME || lower.endsWith(".docx") -> "📄"
            else -> "📎"
        }
    }

    private fun guessMimeType(fileName: String): String {
        val lower = fileName.lowercase(Locale.US)
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".docx") -> DOCX_MIME
            else -> "application/octet-stream"
        }
    }

    private fun isSupportedFile(fileName: String, mimeType: String): Boolean {
        val lower = fileName.lowercase(Locale.US)
        return mimeType.startsWith("image/") ||
                mimeType == "application/pdf" || lower.endsWith(".pdf") ||
                mimeType == DOCX_MIME || lower.endsWith(".docx")
    }

    private fun clearChatUI() {
        messagesContainer.removeAllViews()
    }

    private fun roundedBackground(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(color)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private class MaxWidthLinearLayout(context: Context) : LinearLayout(context) {
        var maxWidthPx: Int = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (maxWidthPx > 0) {
                val originalMode = View.MeasureSpec.getMode(widthMeasureSpec)
                val originalSize = View.MeasureSpec.getSize(widthMeasureSpec)
                val width = if (originalMode == View.MeasureSpec.UNSPECIFIED) {
                    maxWidthPx
                } else {
                    minOf(originalSize, maxWidthPx)
                }
                val constrainedSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST)
                super.onMeasure(constrainedSpec, heightMeasureSpec)
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
    }

    private data class PendingAttachment(
        val name: String,
        val mimeType: String,
        val bytes: ByteArray
    )

    private data class PendingDocx(
        val fileName: String,
        val bytes: ByteArray
    )

    companion object {
        private const val MAX_FILE_SIZE_BYTES = 20 * 1024 * 1024
        private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
