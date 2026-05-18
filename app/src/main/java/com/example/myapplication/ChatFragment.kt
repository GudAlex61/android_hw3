package com.example.myapplication

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// сохранение состояния при повороте
class ChatFragment : Fragment() {

    data class Message(
        val text: String,
        val isUser: Boolean,
        val time: String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    )

    data class Chat(
        val id: String = UUID.randomUUID().toString(),
        val messages: MutableList<Message> = mutableListOf(),
        val createdAt: String = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date())
    ) {
        fun getTitle(): String {
            val lastUserMessage = messages.lastOrNull { it.isUser }
            return if (lastUserMessage != null) {
                val text = lastUserMessage.text
                if (text.length > 40) text.substring(0, 40) + "..." else text
            } else {
                "Новый чат"
            }
        }

        fun getLastMessageTime(): String {
            return messages.lastOrNull()?.time ?: createdAt
        }

        fun getShortPreview(): String {
            return messages.firstOrNull()?.text?.take(60) ?: "Пустой чат"
        }

        fun isUnread(): Boolean {
            return messages.size == 1 && messages[0].isUser
        }
    }

    private val chatHistory = mutableListOf<Chat>()
    private var currentChatIndex = 0

    private fun getCurrentChat(): Chat {
        if (chatHistory.isEmpty()) {
            chatHistory.add(Chat())
        }
        return chatHistory[currentChatIndex]
    }

    private fun getCurrentMessages(): MutableList<Message> {
        return getCurrentChat().messages
    }

    private lateinit var messagesContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: TextView
    private lateinit var newChatButton: TextView
    private lateinit var attachButton: TextView
    private lateinit var menuButton: TextView

    private lateinit var welcomeContainer: LinearLayout
    private lateinit var chatContainer: FrameLayout

    private lateinit var historyMenu: LinearLayout
    private lateinit var closeMenuButton: TextView
    private lateinit var chatHistoryList: ListView
    private lateinit var historyAdapter: ChatHistoryAdapter

    private var isFirstMessage = true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
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

        welcomeContainer = view.findViewById(R.id.welcomeContainer)
        chatContainer = view.findViewById(R.id.messagesContainer)

        historyMenu = view.findViewById(R.id.historyMenu)
        closeMenuButton = view.findViewById(R.id.closeMenuButton)
        chatHistoryList = view.findViewById(R.id.chatHistoryList)

        initChatHistory()
        initMessagesContainer(view)

        setupClickListeners()
        setupHistoryMenu()

        chatContainer.visibility = View.GONE
        welcomeContainer.visibility = View.VISIBLE
    }

    private fun initChatHistory() {
        if (chatHistory.isEmpty()) {
            chatHistory.add(Chat())
        }

        historyAdapter = ChatHistoryAdapter(requireContext(), chatHistory)
        chatHistoryList.adapter = historyAdapter
    }

    private inner class ChatHistoryAdapter(
        context: android.content.Context, private val chats: List<Chat>
    ) : ArrayAdapter<Chat>(context, 0, chats) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val chat = chats[position]

            val view = convertView ?: LayoutInflater.from(context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)

            val textView = view.findViewById<TextView>(android.R.id.text1)

            val background = GradientDrawable().apply {
                cornerRadius = dpToPx(12).toFloat()
                if (position == currentChatIndex) {
                    setColor(ContextCompat.getColor(context, R.color.chat_history_selected_bg))
                } else if (chat.isUnread()) {
                    setColor(ContextCompat.getColor(context, R.color.chat_history_unread_bg))
                } else {
                    setColor(ContextCompat.getColor(context, R.color.chat_history_default_bg))
                }
                setStroke(dpToPx(1), ContextCompat.getColor(context, R.color.chat_history_border))
            }

            textView.apply {
                val title = chat.getTitle()
                val time = chat.getLastMessageTime()
//                val preview = chat.getShortPreview()

                text = if (chat.messages.isEmpty()) {
                    context.getString(R.string.empty_chat_title)
                } else {
                    "$title $time"
                }

                this.background = background
                setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
                textSize = 18f
                maxLines = 5
                gravity = Gravity.START
                isSingleLine = false

                if (position == currentChatIndex) {
                    setTextColor(ContextCompat.getColor(context, R.color.current_chat_title))
                    setTypeface(null, Typeface.BOLD)
                } else if (chat.isUnread()) {
                    setTextColor(ContextCompat.getColor(context, R.color.unread_chat_title))
                    setTypeface(null, Typeface.BOLD)
                } else {
                    setTextColor(ContextCompat.getColor(context, R.color.chat_title))
                    setTypeface(null, Typeface.NORMAL)
                }


                minimumHeight = dpToPx(80)
            }

            return view
        }
    }

    private fun initMessagesContainer(view: View) {
        chatContainer.removeAllViews()

        scrollView = ScrollView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        messagesContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        scrollView.addView(messagesContainer)
        chatContainer.addView(scrollView)
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()
    }

    private fun setupClickListeners() {
        sendButton.setOnClickListener {
            sendMessage()
        }

        newChatButton.setOnClickListener {
            createNewChat()
        }

        menuButton.setOnClickListener {
            showHistoryMenu()
        }

        attachButton.setOnClickListener {
            Toast.makeText(
                requireContext(),
                context?.getString(R.string.attach_function_toast),
                Toast.LENGTH_SHORT
            ).show()
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
        closeMenuButton.setOnClickListener {
            hideHistoryMenu()
        }

        chatHistoryList.setOnItemClickListener { _, _, position, _ ->
            switchToChat(position)
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

    private fun createNewChat() {
        val currentMessages = getCurrentMessages()
        if (currentMessages.isEmpty()) {
            Toast.makeText(
                requireContext(),
                context?.getString(R.string.error_current_chat_empty),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val newChat = Chat()
        chatHistory.add(newChat)
        currentChatIndex = chatHistory.size - 1

        clearChatUI()

        welcomeContainer.visibility = View.VISIBLE
        chatContainer.visibility = View.GONE
        isFirstMessage = true

        updateHistoryList()
        Toast.makeText(
            requireContext(),
            context?.getString(R.string.toast_new_chat_created),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun switchToChat(index: Int) {
        if (index in chatHistory.indices && index != currentChatIndex) {
            currentChatIndex = index
            val chat = getCurrentChat()

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

            updateHistoryList()
        }
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isNotEmpty()) {
            if (isFirstMessage) {
                isFirstMessage = false
                welcomeContainer.visibility = View.GONE
                chatContainer.visibility = View.VISIBLE
            }

            addMessage(text, true)
            messageInput.text.clear()
            getAIResponse(text)
        }
    }

    private fun addMessage(text: String, isUser: Boolean) {
        val message = Message(text, isUser)
        getCurrentMessages().add(message)
        addMessageToUI(message)

        scrollView.postDelayed({
            scrollView.fullScroll(View.FOCUS_DOWN)
        }, 100)

        updateHistoryList()
    }

    private fun addMessageToUI(message: Message) {
        val messageLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
                bottomMargin = dpToPx(8)
            }
        }

        val messageText = TextView(requireContext()).apply {
            this.text = message.text
            textSize = 16f
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))

            val maxWidth = (resources.displayMetrics.widthPixels * 0.7).toInt()
            this.maxWidth = maxWidth
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
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        textContainer.addView(messageText)
        textContainer.addView(timeText)

        if (message.isUser) {
            messageLayout.gravity = Gravity.END

            messageLayout.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0).apply {
                    weight = 1f
                }
            })

            messageText.setBackgroundResource(R.drawable.bubble_user)
            messageText.setTextColor(
                ContextCompat.getColor(
                    requireContext(), R.color.message_user_text
                )
            )
            messageLayout.addView(textContainer)
        } else {
            messageLayout.gravity = Gravity.START

            val botIcon = TextView(requireContext()).apply {
                text = context?.getString(R.string.ai_bot_emoji)
                textSize = 24f
                setPadding(0, dpToPx(4), dpToPx(8), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            messageLayout.addView(botIcon)

            messageText.setBackgroundResource(R.drawable.bubble_bot)
            messageText.setTextColor(
                ContextCompat.getColor(
                    requireContext(), R.color.message_bot_text
                )
            )
            messageLayout.addView(textContainer)

            messageLayout.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0).apply {
                    weight = 1f
                }
            })
        }

        messagesContainer.addView(messageLayout)
    }

    private fun clearChatUI() {
        messagesContainer.removeAllViews()
    }

    private fun clearChat() {
        getCurrentMessages().clear()
        clearChatUI()
        isFirstMessage = true

        welcomeContainer.visibility = View.VISIBLE
        chatContainer.visibility = View.GONE

        updateHistoryList()
        Toast.makeText(
            requireContext(), context?.getString(R.string.toast_chat_cleared), Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateHistoryList() {
        historyAdapter.notifyDataSetChanged()
    }

    private fun getAIResponse(userMessage: String) {
        sendButton.text = "⌛"
        sendButton.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply {
                    put("model", "openai/gpt-3.5-turbo")
                    put("max_tokens", 500)

                    val messagesArray = JSONArray().apply {

                        put(JSONObject().apply {
                            put("role", "system")
                            put(
                                "content",
                                context?.getString(R.string.ai_system_prompt)?.trimIndent()
                            )
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", userMessage)
                        })
                    }
                    put("messages", messagesArray)
                }

                // БЕЗОПАСНОЕ ИСПОЛЬЗОВАНИЕ API КЛЮЧА
                val apiKey = BuildConfig.OPENROUTER_API_KEY

                val request = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(json.toString().toRequestBody("application/json".toMediaType())).build()

                val client =
                    OkHttpClient.Builder().connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val aiResponse = parseAIResponse(responseBody)

                    withContext(Dispatchers.Main) {
                        addMessage(aiResponse, false)
                        resetSendButton()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        addMessage(
                            context?.getString(R.string.error_api_code, response.code)
                                ?: "API Error", false
                        )
                        resetSendButton()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addMessage(
                        context?.getString(R.string.error_connection) ?: "Connection Error", false
                    )
                    resetSendButton()
                }
            }
        }
    }

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