package com.simonproyt.legacysignal

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.simonproyt.legacysignal.data.DatabaseHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var threadAdapter: ThreadAdapter
    private val db by lazy { DatabaseHelper.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        
        BackgroundSyncManager.start(this)

        val myUuid = CredentialsManager.getPhoneNumber(this) ?: "Unknown"
        val tvMyUuid = findViewById<TextView>(R.id.tvMyUuid)
        tvMyUuid.text = "My UUID:\n$myUuid"

        threadAdapter = ThreadAdapter(emptyList()) { thread ->
            val intent = Intent(this, ChatActivity::class.java).apply {
                putExtra("THREAD_ID", thread.id)
                putExtra("RECIPIENT_ID", thread.recipientNumber)
            }
            startActivity(intent)
        }

        val rvThreads = findViewById<RecyclerView>(R.id.rvThreads)
        rvThreads.layoutManager = LinearLayoutManager(this)
        rvThreads.adapter = threadAdapter

        val fabNewChat = findViewById<FloatingActionButton>(R.id.fabNewChat)
        fabNewChat.setOnClickListener {
            showNewChatDialog()
        }

        lifecycleScope.launch {
            db.getAllThreads().collectLatest { threads ->
                threadAdapter.updateThreads(threads)
            }
        }
    }

    private fun showNewChatDialog() {
        val input = EditText(this)
        input.hint = "Recipient UUID (ACI/PNI)"

        AlertDialog.Builder(this)
            .setTitle("New Chat")
            .setView(input)
            .setPositiveButton("Start") { _, _ ->
                val recipientId = input.text.toString()
                if (recipientId.isNotBlank()) {
                    val intent = Intent(this, ChatActivity::class.java).apply {
                        putExtra("RECIPIENT_ID", recipientId)
                    }
                    startActivity(intent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
