package com.simonproyt.legacysignal

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.Base64
import com.simonproyt.legacysignal.api.SignalClient
import com.simonproyt.legacysignal.crypto.MessageReceiver
import com.simonproyt.legacysignal.crypto.MessageSender
import com.simonproyt.legacysignal.crypto.SharedPrefsSignalProtocolStore
import com.simonproyt.legacysignal.data.DatabaseHelper
import com.simonproyt.legacysignal.data.MessageEntity
import com.simonproyt.legacysignal.data.ThreadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatActivity : AppCompatActivity() {

    private lateinit var signalClient: SignalClient
    private lateinit var messageAdapter: MessageAdapter
    private val messagesList = mutableListOf<ChatMessage>()
    private val db by lazy { DatabaseHelper.getInstance(this) }
    
    private var recipientId: String = ""
    private var threadId: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recipientId = intent.getStringExtra("RECIPIENT_ID") ?: ""
        threadId = intent.getLongExtra("THREAD_ID", 0)

        // Read credentials from CredentialsManager
        val phone = CredentialsManager.getPhoneNumber(this) ?: ""
        val pass = CredentialsManager.getPassword(this) ?: ""
        BackgroundSyncManager.start(this)
        signalClient = BackgroundSyncManager.getClient() ?: SignalClient(this, phone, pass)
        
        messageAdapter = MessageAdapter(messagesList) { msgId ->
            android.app.AlertDialog.Builder(this)
                .setTitle("Delete Message")
                .setMessage("Are you sure you want to delete this message?")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.deleteMessage(msgId)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        val rvMessages = findViewById<RecyclerView>(R.id.rvMessages)
        rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rvMessages.adapter = messageAdapter

        val etMessage = findViewById<EditText>(R.id.etMessage)
        val btnSend = findViewById<Button>(R.id.btnSend)
        
        val authHeader = "Basic " + Base64.encodeToString("$phone:$pass".toByteArray(), Base64.NO_WRAP)
        val messageSender = MessageSender(signalClient.api, SharedPrefsSignalProtocolStore(this), authHeader)

        // Load existing messages
        lifecycleScope.launch {
            if (threadId == 0L && recipientId.isNotEmpty()) {
                val existingThread = db.getThreadByRecipient(recipientId)
                if (existingThread != null) {
                    threadId = existingThread.id
                } else {
                    threadId = db.insertThread(
                        ThreadEntity(recipientNumber = recipientId, lastMessageSnippet = "", timestamp = System.currentTimeMillis())
                    )
                }
            }

            db.getMessagesForThread(threadId).collectLatest { msgs ->
                val existingThread = db.getThreadById(threadId)
                val displayName = existingThread?.name?.takeIf { it.isNotBlank() } ?: recipientId
                
                withContext(Dispatchers.Main) {
                    supportActionBar?.title = displayName
                }

                messagesList.clear()
                msgs.forEach { msg ->
                    messagesList.add(ChatMessage(msg.id, if (msg.isOutgoing) "Me" else displayName, msg.body))
                }
                messageAdapter.notifyDataSetChanged()
                if (messagesList.isNotEmpty()) {
                    rvMessages.scrollToPosition(messagesList.size - 1)
                }
            }
        }

        btnSend.setOnClickListener {
            val text = etMessage.text.toString()
            if (text.isNotBlank()) {
                etMessage.text.clear()
                
                lifecycleScope.launch(Dispatchers.IO) {
                    // Send via MessageSender
                    try {
                        messageSender.sendMessage(recipientId, text)
                        
                        // Insert into DB
                        val time = System.currentTimeMillis()
                        db.updateSnippet(threadId, text, time)
                        db.insertMessage(
                            MessageEntity(
                                threadId = threadId,
                                senderId = phone,
                                body = text,
                                timestamp = time,
                                isOutgoing = true
                            )
                        )
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            messageAdapter.addMessage(ChatMessage(-1L, "System", "Failed to send: ${e.message}"))
                            rvMessages.scrollToPosition(messagesList.size - 1)
                        }
                    }
                }
            }
        }
        
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, 1, 0, "Set Contact Name")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == 1) {
            val input = EditText(this)
            input.hint = "Name"
            android.app.AlertDialog.Builder(this)
                .setTitle("Set Contact Name")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val newName = input.text.toString()
                    lifecycleScope.launch(Dispatchers.IO) {
                        val existingThread = db.getThreadById(threadId)
                        if (existingThread != null) {
                            db.updateThread(existingThread.copy(name = newName.ifBlank { null }))
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}