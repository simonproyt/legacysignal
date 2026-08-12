package com.simonproyt.legacysignal

import android.content.Context
import android.util.Log
import com.simonproyt.legacysignal.api.SignalClient
import com.simonproyt.legacysignal.crypto.MessageReceiver
import com.simonproyt.legacysignal.data.DatabaseHelper
import com.simonproyt.legacysignal.data.MessageEntity
import com.simonproyt.legacysignal.data.ThreadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object BackgroundSyncManager {
    private var isRunning = false
    private var client: SignalClient? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var messageReceiver: MessageReceiver? = null
    
    fun start(context: Context) {
        if (isRunning) return
        val phone = CredentialsManager.getPhoneNumber(context) ?: return
        val pass = CredentialsManager.getPassword(context) ?: return

        Log.i("BackgroundSyncManager", "Starting background sync for $phone")
        client = SignalClient(context.applicationContext, phone, pass)
        val db = DatabaseHelper.getInstance(context.applicationContext)
        messageReceiver = MessageReceiver(context.applicationContext)
        
        client?.onMessageReceived = { envelope ->
            scope.launch {
                try {
                    val decrypted = messageReceiver?.decryptMessage(envelope) ?: return@launch
                    if (decrypted.body == "[No Body/Receipt]" || decrypted.body == "[Receipt]") {
                        Log.i("BackgroundSyncManager", "Skipping receipt from ${decrypted.senderId}")
                        return@launch
                    }
                    val plaintext = decrypted.body
                    val senderId = decrypted.senderId
                    
                    var senderThread = db.getThreadByRecipient(senderId)
                    if (senderThread == null) {
                        db.insertThread(ThreadEntity(recipientNumber = senderId, lastMessageSnippet = plaintext, timestamp = System.currentTimeMillis()))
                        senderThread = db.getThreadByRecipient(senderId)
                    } else {
                        db.updateSnippet(senderThread.id, plaintext, System.currentTimeMillis())
                    }
                    db.insertMessage(MessageEntity(threadId = senderThread!!.id, senderId = senderId, body = plaintext, isOutgoing = false, timestamp = System.currentTimeMillis()))
                    Log.i("BackgroundSyncManager", "Saved message from $senderId")
                } catch (e: Exception) {
                    Log.e("BackgroundSyncManager", "Failed to process message", e)
                }
            }
        }
        client?.connect()
        isRunning = true
    }
    
    fun getClient(): SignalClient? {
        return client
    }
}
