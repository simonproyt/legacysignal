package com.simonproyt.legacysignal

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.simonproyt.legacysignal.data.DatabaseHelper
import com.simonproyt.legacysignal.data.ThreadEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ThreadAdapter(
    private var threads: List<ThreadEntity>,
    private val onThreadClick: (ThreadEntity) -> Unit
) : RecyclerView.Adapter<ThreadAdapter.ThreadViewHolder>() {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())

    class ThreadViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAvatar: ImageView = view.findViewById(R.id.ivAvatar)
        val tvAvatarInitial: TextView = view.findViewById(R.id.tvAvatarInitial)
        val tvRecipient: TextView = view.findViewById(R.id.tvRecipient)
        val tvSnippet: TextView = view.findViewById(R.id.tvSnippet)
        val tvTimestamp: TextView = view.findViewById(R.id.tvTimestamp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThreadViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_thread, parent, false)
        return ThreadViewHolder(view)
    }

    override fun onBindViewHolder(holder: ThreadViewHolder, position: Int) {
        val thread = threads[position]
        val displayName = if (!thread.name.isNullOrBlank()) thread.name else (thread.recipientNumber ?: "Unknown")
        holder.tvRecipient.text = displayName
        holder.tvSnippet.text = if (!thread.lastMessageSnippet.isNullOrBlank()) thread.lastMessageSnippet else "No messages"

        // Format timestamp
        if (thread.timestamp > 0) {
            val isToday = DateUtils.isToday(thread.timestamp)
            holder.tvTimestamp.text = if (isToday) {
                timeFormat.format(Date(thread.timestamp))
            } else {
                dateFormat.format(Date(thread.timestamp))
            }
            holder.tvTimestamp.visibility = View.VISIBLE
        } else {
            holder.tvTimestamp.visibility = View.GONE
        }

        // Avatar handling: Decrypted image or initial fallback
        val db = DatabaseHelper.getInstance(holder.itemView.context)
        val avatarPath = thread.recipientNumber?.let { db.getContactAvatar(it) }
        var bitmapLoaded = false

        if (!avatarPath.isNullOrBlank()) {
            val file = File(avatarPath)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    holder.ivAvatar.setImageBitmap(getCircularBitmap(bitmap))
                    holder.ivAvatar.visibility = View.VISIBLE
                    holder.tvAvatarInitial.visibility = View.GONE
                    bitmapLoaded = true
                }
            }
        }

        if (!bitmapLoaded) {
            holder.ivAvatar.visibility = View.GONE
            holder.tvAvatarInitial.visibility = View.VISIBLE
            val initial = (displayName ?: "?").trim().take(1).uppercase()
            holder.tvAvatarInitial.text = if (initial.isNotEmpty()) initial else "?"
        }

        holder.itemView.setOnClickListener {
            onThreadClick(thread)
        }
    }

    private fun getCircularBitmap(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val size = Math.min(bitmap.width, bitmap.height)
        val output = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = -0x1
        }
        val x = (bitmap.width - size) / 2
        val y = (bitmap.height - size) / 2
        val srcRect = android.graphics.Rect(x, y, x + size, y + size)
        val dstRect = android.graphics.Rect(0, 0, size, size)
        canvas.drawOval(android.graphics.RectF(dstRect), paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        return output
    }

    override fun getItemCount() = threads.size

    fun updateThreads(newThreads: List<ThreadEntity>) {
        threads = newThreads
        notifyDataSetChanged()
    }
}
