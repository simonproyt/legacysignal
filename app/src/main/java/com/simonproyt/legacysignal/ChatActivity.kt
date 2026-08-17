package com.simonproyt.legacysignal

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simonproyt.legacysignal.api.SignalClient
import com.simonproyt.legacysignal.crypto.MessageSender
import com.simonproyt.legacysignal.crypto.SharedPrefsSignalProtocolStore
import com.simonproyt.legacysignal.data.DatabaseHelper
import com.simonproyt.legacysignal.data.MessageEntity
import com.simonproyt.legacysignal.data.ThreadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ChatActivity : AppCompatActivity() {

    private lateinit var signalClient: SignalClient
    private var messageSender: MessageSender? = null
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
        
        messageAdapter = MessageAdapter(
            messages = messagesList,
            onMessageLongClick = { msgId ->
                android.app.AlertDialog.Builder(this)
                    .setTitle("Delete Message")
                    .setMessage("Are you sure you want to delete this message?")
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            if (msgId > 0) {
                                db.deleteMessage(msgId)
                            } else {
                                withContext(Dispatchers.Main) {
                                    messagesList.removeAll { it.id == msgId }
                                    messageAdapter.notifyDataSetChanged()
                                }
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onImageClick = { imagePath ->
                val intent = Intent(this, ImageViewerActivity::class.java).apply {
                    putExtra("IMAGE_PATH", imagePath)
                }
                startActivity(intent)
            }
        )

        val rvMessages = findViewById<RecyclerView>(R.id.rvMessages)
        rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rvMessages.adapter = messageAdapter

        val etMessage = findViewById<EditText>(R.id.etMessage)
        val btnSend = findViewById<Button>(R.id.btnSend)
        val btnAttach = findViewById<Button>(R.id.btnAttach)
        
        val authHeader = "Basic " + Base64.encodeToString("$phone:$pass".toByteArray(), Base64.NO_WRAP)
        messageSender = MessageSender(signalClient.api, SharedPrefsSignalProtocolStore(this), authHeader)

        // Update connection status in subtitle
        lifecycleScope.launch {
            BackgroundSyncManager.statusText.collectLatest { status ->
                supportActionBar?.subtitle = status
            }
        }

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
                    messagesList.add(
                        ChatMessage(
                            id = msg.id,
                            sender = if (msg.isOutgoing) "Me" else displayName,
                            text = msg.body,
                            timestamp = msg.timestamp,
                            isOutgoing = msg.isOutgoing,
                            imagePath = msg.imagePath
                        )
                    )
                }
                messageAdapter.notifyDataSetChanged()
                if (messagesList.isNotEmpty()) {
                    rvMessages.scrollToPosition(messagesList.size - 1)
                }
            }
        }

        btnAttach.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "Select Picture"), REQUEST_IMAGE_PICK)
        }

        btnSend.setOnClickListener {
            val text = etMessage.text.toString()
            if (text.isNotBlank()) {
                etMessage.text.clear()
                
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val sender = messageSender ?: return@launch
                        sender.sendMessage(recipientId, text)
                        
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
                            messageAdapter.addMessage(
                                ChatMessage(
                                    id = -1L,
                                    sender = "System",
                                    text = "Failed to send: ${e.message}",
                                    timestamp = System.currentTimeMillis(),
                                    isOutgoing = false
                                )
                            )
                            rvMessages.scrollToPosition(messagesList.size - 1)
                        }
                    }
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE_PICK && resultCode == Activity.RESULT_OK && data?.data != null) {
            val uri = data.data!!
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes() ?: return@launch
                    inputStream.close()

                    val attachDir = File(filesDir, "attachments")
                    if (!attachDir.exists()) attachDir.mkdirs()
                    val localFile = File(attachDir, "outgoing_${System.currentTimeMillis()}.jpg")
                    localFile.writeBytes(bytes)

                    val phone = CredentialsManager.getPhoneNumber(this@ChatActivity) ?: ""
                    val time = System.currentTimeMillis()
                    
                    db.updateSnippet(threadId, "📷 Photo", time)
                    db.insertMessage(
                        MessageEntity(
                            threadId = threadId,
                            senderId = phone,
                            body = "",
                            timestamp = time,
                            isOutgoing = true,
                            imagePath = localFile.absolutePath
                        )
                    )

                    // Transmit attachment to recipient over Signal protocol
                    val sender = messageSender
                    if (sender != null) {
                        val success = sender.sendImageMessage(recipientId, bytes, "", time)
                        if (!success) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@ChatActivity, "Failed to send photo over network", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ChatActivity, "Failed to send photo: ${e.message}", Toast.LENGTH_SHORT).show()
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

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("SignalPrefs", android.content.Context.MODE_PRIVATE)
        if (prefs.getInt("sync_interval_mins", 0) == 0) {
            BackgroundSyncManager.start(this)
        }
    }

    companion object {
        private const val REQUEST_IMAGE_PICK = 1001
    }
}