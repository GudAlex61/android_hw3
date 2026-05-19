package com.example.myapplication

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

class ChatHistoryAdapter(
    context: Context,
    private val chats: List<Chat>,
    private val currentChatIndex: Int
) : ArrayAdapter<Chat>(context, 0, chats) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val chat = chats[position]

        val view = convertView ?: LayoutInflater.from(context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)

        val textView = view.findViewById<TextView>(android.R.id.text1)

        val background = GradientDrawable().apply {
            cornerRadius = dpToPx(12).toFloat()
            if (position == currentChatIndex) {
                setColor(Color.parseColor("#FFF9C4"))
            } else if (chat.isUnread()) {
                setColor(Color.parseColor("#E8F5E9"))
            } else {
                setColor(Color.parseColor("#F3F4F6"))
            }
            setStroke(dpToPx(1), Color.parseColor("#E5E7EB"))
        }

        textView.apply {
            val title = chat.getTitle()
            val time = chat.getLastMessageTime()

            text = if (chat.messages.isEmpty()) {
                "Новый чат"
            } else {
                "$title - $time"
            }

            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            textSize = 18f
            maxLines = 5
            gravity = Gravity.START
            isSingleLine = false

            if (position == currentChatIndex) {
                setTextColor(Color.parseColor("#000000"))
                setTypeface(null, Typeface.BOLD)
            } else if (chat.isUnread()) {
                setTextColor(Color.parseColor("#1B5E20"))
                setTypeface(null, Typeface.BOLD)
            } else {
                setTextColor(Color.parseColor("#374151"))
                setTypeface(null, Typeface.NORMAL)
            }

            minimumHeight = dpToPx(80)
        }

        return view
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}