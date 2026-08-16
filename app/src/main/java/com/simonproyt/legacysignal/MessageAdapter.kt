package com.simonproyt.legacysignal

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val id: Long,
    val sender: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean = false
)

class MessageAdapter(
    private val messages: MutableList<ChatMessage>,
    private val onMessageLongClick: ((Long) -> Unit)? = null
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val llRoot: LinearLayout = view.findViewById(R.id.llRoot)
        val llBubble: LinearLayout = view.findViewById(R.id.llBubble)
        val tvSender: TextView = view.findViewById(R.id.tvSender)
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.tvMessage.text = message.text
        holder.tvTime.text = timeFormat.format(Date(message.timestamp))

        if (message.isOutgoing) {
            holder.llRoot.gravity = Gravity.END
            holder.llBubble.setBackgroundResource(R.drawable.bg_bubble_outgoing)
            holder.tvMessage.setTextColor(Color.WHITE)
            holder.tvTime.setTextColor(Color.parseColor("#D0E4FF"))
            holder.tvSender.visibility = View.GONE
        } else {
            holder.llRoot.gravity = Gravity.START
            holder.llBubble.setBackgroundResource(R.drawable.bg_bubble_incoming)
            val context = holder.itemView.context
            holder.tvMessage.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.signal_bubble_incoming_text))
            holder.tvTime.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.signal_bubble_incoming_time))
            holder.tvSender.visibility = View.GONE
        }

        holder.itemView.setOnLongClickListener {
            onMessageLongClick?.invoke(message.id)
            true
        }
    }

    override fun getItemCount() = messages.size

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
}
