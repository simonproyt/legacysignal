package com.simonproyt.legacysignal

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simonproyt.legacysignal.api.SignalClient

class MainActivity : AppCompatActivity() {

    private lateinit var signalClient: SignalClient
    private lateinit var messageAdapter: MessageAdapter
    private val messagesList = mutableListOf<ChatMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Read credentials from CredentialsManager
        val phone = CredentialsManager.getPhoneNumber(this) ?: ""
        val pass = CredentialsManager.getPassword(this) ?: ""
        signalClient = SignalClient(this, phone, pass)
        
        messageAdapter = MessageAdapter(messagesList)
        val rvMessages = findViewById<RecyclerView>(R.id.rvMessages)
        rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rvMessages.adapter = messageAdapter

        val etMessage = findViewById<EditText>(R.id.etMessage)
        val btnSend = findViewById<Button>(R.id.btnSend)
        
        signalClient.onMessageReceived = { envelope ->
            runOnUiThread {
                val isEncrypted = envelope.type == com.simonproyt.legacysignal.api.push.SignalServiceProtos.Envelope.Type.CIPHERTEXT
                        || envelope.type == com.simonproyt.legacysignal.api.push.SignalServiceProtos.Envelope.Type.PREKEY_BUNDLE
                val text = if (isEncrypted) "[Encrypted Message]" else "[Unknown Type]"
                messageAdapter.addMessage(ChatMessage(envelope.source, text))
                rvMessages.scrollToPosition(messagesList.size - 1)
            }
        }
        
        btnSend.setOnClickListener {
            val text = etMessage.text.toString()
            if (text.isNotBlank()) {
                messageAdapter.addMessage(ChatMessage("Me", text))
                rvMessages.scrollToPosition(messagesList.size - 1)
                etMessage.text.clear()
                // TODO: Send via SignalClient API
            }
        }
        
        // Connect automatically
        signalClient.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        signalClient.disconnect()
    }
}