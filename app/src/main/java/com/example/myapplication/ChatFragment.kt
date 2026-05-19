package com.example.myapplication

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import java.io.ByteArrayOutputStream
import java.util.Locale

class ChatFragment : Fragment() {

    // activityViewModels() — ViewModel живёт пока жива Activity,
    // а не фрагмент, поэтому история не теряется при смене вкладок
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

    private var isFirstMessage = true
    private var pendingAttachment: PendingAttachment? = null

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleSelectedFile(it) }
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

        initMessagesContainer(view)
        setupAttachmentPreviewStyle()
        setupClickListeners()
        setupHistoryMenu()
        observeViewModel()
        updateUIForCurrentChat()
    }

    private fun observeViewModel() {
        viewModel.chatHistory.observe(viewLifecycleOwner, Observer {
            updateHistoryList()
            updateUIForCurrentChat()
        })

        viewModel.currentChatIndex.observe(viewLifecycleOwner, Observer {
            updateHistoryList()
            updateUIForCurrentChat()
            clearPendingAttachment()
        })

        viewModel.isLoading.observe(viewLifecycleOwner, Observer { loading ->
            sendButton.text = if (loading) "⌛" else "↑"
            sendButton.isEnabled = !loading
            attachButton.isEnabled = !loading
            attachButton.alpha = if (loading) 0.5f else 1f
        })
    }

    private fun updateUIForCurrentChat() {
        val chat = viewModel.getCurrentChat()
        clearChatUI()
        if (chat.messages.isNotEmpty()) {
            welcomeContainer.visibility = View.GONE
            chatContainer.visibility = View.VISIBLE
            isFirstMessage = false
            for (message in chat.messages) {
                addMessageToUI(message)
            }
        } else {
            welcomeContainer.visibility = View.VISIBLE
            chatContainer.visibility = View.VISIBLE
            isFirstMessage = true
        }
    }

    private fun initMessagesContainer(view: View) {
        chatContainer.removeAllViews()

        scrollView = ScrollView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
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

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun setupClickListeners() {
        sendButton.setOnClickListener {
            sendMessage()
        }

        newChatButton.setOnClickListener {
            clearPendingAttachment()
            viewModel.createNewChat()
        }

        menuButton.setOnClickListener { showHistoryMenu() }

        removeAttachmentButton.setOnClickListener { clearPendingAttachment() }

        attachButton.setOnClickListener {
            filePickerLauncher.launch(
                arrayOf(
                    "image/*",
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )
            )
        }

        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
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

    private fun guessMimeType(fileName: String): String {
        val lower = fileName.lowercase(Locale.US)
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            else -> "application/octet-stream"
        }
    }

    private fun isSupportedFile(fileName: String, mimeType: String): Boolean {
        val lower = fileName.lowercase(Locale.US)
        return mimeType.startsWith("image/") ||
                mimeType == "application/pdf" || lower.endsWith(".pdf") ||
                mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || lower.endsWith(".docx")
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
    }

    private fun hideHistoryMenu() {
        historyMenu.visibility = View.GONE
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        val attachment = pendingAttachment

        if (attachment != null) {
            messageInput.text.clear()
            clearPendingAttachment()
            viewModel.sendFile(attachment.name, attachment.mimeType, attachment.bytes, text)
            return
        }

        if (text.isNotEmpty()) {
            viewModel.sendMessage(text, true)
            messageInput.text.clear()
        }
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

        val bubbleContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            val maxWidth = (resources.displayMetrics.widthPixels * 0.76).toInt()
        }

        if (message.attachmentName != null) {
            bubbleContainer.addView(createAttachmentChip(message.attachmentName, message.attachmentMimeType))
        }

        if (message.text.isNotBlank()) {
            val messageText = TextView(requireContext()).apply {
                text = message.text
                textSize = 16f
                setTextColor(if (message.isUser) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
                if (message.attachmentName != null) {
                    setPadding(0, dpToPx(8), 0, 0)
                }
            }
            bubbleContainer.addView(messageText)
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
                layoutParams = LinearLayout.LayoutParams(0, 0).apply {
                    weight = 1f
                }
            })
            bubbleContainer.background = roundedBackground(0xFFF59E0B.toInt(), dpToPx(18))
            messageLayout.addView(textContainer)
        } else {
            messageLayout.gravity = Gravity.START
            val botIcon = TextView(requireContext()).apply {
                text = context?.getString(R.string.ai_bot_emoji)
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
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun createAttachmentChip(fileName: String, mimeType: String?): View {
        val chip = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            background = roundedBackground(0x22FFFFFF, dpToPx(14))
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
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                leftMargin = dpToPx(8)
            }
        }

        chip.addView(icon)
        chip.addView(name)
        return chip
    }

    private fun iconForMime(fileName: String, mimeType: String?): String {
        val lower = fileName.lowercase(Locale.US)
        return when {
            mimeType?.startsWith("image/") == true || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") -> "🖼️"
            mimeType == "application/pdf" || lower.endsWith(".pdf") -> "📕"
            mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || lower.endsWith(".docx") -> "📄"
            else -> "📎"
        }
    }

    private fun roundedBackground(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(color)
        }
    }

    private fun clearChatUI() {
        messagesContainer.removeAllViews()
    }

    private fun updateHistoryList() {
        val currentIndex = viewModel.currentChatIndex.value ?: 0
        val chats = viewModel.chatHistory.value ?: listOf()
        historyAdapter = ChatHistoryAdapter(requireContext(), chats, currentIndex)
        chatHistoryList.adapter = historyAdapter
    }

    private data class PendingAttachment(
        val name: String,
        val mimeType: String,
        val bytes: ByteArray
    )

    companion object {
        private const val MAX_FILE_SIZE_BYTES = 20 * 1024 * 1024
    }


    private data class PendingAttachment(
        val name: String,
        val mimeType: String,
        val bytes: ByteArray
    )

    private fun resetSendButton() {
        sendButton.text = "↑"
        sendButton.isEnabled = true
    }

    private fun parseAIResponse(responseBody: String?): String {
        return try {
            val json = JSONObject(responseBody ?: "")
            val choices = json.getJSONArray("choices")
            if (choices.length() > 0) {
                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.getJSONObject("message")
                message.getString("content").trim()
            } else {
                context?.getString(R.string.error_no_ai_response) ?: "No response"
            }
        } catch (e: Exception) {
            context?.getString(R.string.error_parsing_response) ?: "Parsing error"
        }
    }
}