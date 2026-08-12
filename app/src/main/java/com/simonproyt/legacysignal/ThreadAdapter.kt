package com.simonproyt.legacysignal

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.simonproyt.legacysignal.data.ThreadEntity

class ThreadAdapter(
    private var threads: List<ThreadEntity>,
    private val onThreadClick: (ThreadEntity) -> Unit
) : RecyclerView.Adapter<ThreadAdapter.ThreadViewHolder>() {

    class ThreadViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRecipient: TextView = view.findViewById(R.id.tvRecipient)
        val tvSnippet: TextView = view.findViewById(R.id.tvSnippet)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThreadViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_thread, parent, false)
        return ThreadViewHolder(view)
    }

    override fun onBindViewHolder(holder: ThreadViewHolder, position: Int) {
        val thread = threads[position]
        holder.tvRecipient.text = if (!thread.name.isNullOrBlank()) thread.name else thread.recipientNumber
        holder.tvSnippet.text = thread.lastMessageSnippet
        holder.itemView.setOnClickListener {
            onThreadClick(thread)
        }
    }

    override fun getItemCount() = threads.size

    fun updateThreads(newThreads: List<ThreadEntity>) {
        threads = newThreads
        notifyDataSetChanged()
    }
}
