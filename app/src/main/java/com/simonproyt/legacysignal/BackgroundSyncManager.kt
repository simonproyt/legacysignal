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
                    val plaintext = decrypted.body
                    val senderId = decrypted.senderId
                    
                    if (decrypted.profileKey != null) {
                        val currentName = db.getContactName(senderId)
                        val b64Key = android.util.Base64.encodeToString(decrypted.profileKey, android.util.Base64.NO_WRAP)
                        if (currentName.isNullOrEmpty()) {
                            Log.i("BackgroundSyncManager", "Profile key found for new contact $senderId, fetching profile...")
                            db.saveContact(senderId, null, null, b64Key)
                            try {
                                val phone = com.simonproyt.legacysignal.CredentialsManager.getPhoneNumber(context) ?: ""
                                val pass = com.simonproyt.legacysignal.CredentialsManager.getPassword(context) ?: ""
                                val profileAuth = android.util.Base64.encodeToString(("$phone:$pass").toByteArray(), android.util.Base64.NO_WRAP)
                                
                                val profileKeyObj = org.signal.libsignal.zkgroup.profiles.ProfileKey(decrypted.profileKey)
                                val aciObj = org.signal.libsignal.protocol.ServiceId.Aci.parseFromString(senderId)
                                val versionStr = profileKeyObj.getProfileKeyVersion(aciObj).serialize()

                                val response = client!!.api.getProfile("Basic $profileAuth", senderId, versionStr).execute()
                                if (response.isSuccessful && response.body() != null) {
                                    val jsonString = response.body()!!.string()
                                    Log.i("BackgroundSyncManager", "Successfully fetched profile JSON for $senderId: $jsonString")
                                    val profileData = org.json.JSONObject(jsonString)
                                    var decryptedName: String? = null
                                    var decryptedAbout: String? = null

                                    try {
                                        val profileCipher = com.simonproyt.legacysignal.api.crypto.ProfileCipher(org.signal.libsignal.zkgroup.profiles.ProfileKey(decrypted.profileKey))

                                        if (profileData.has("name") && !profileData.isNull("name")) {
                                            val encName = android.util.Base64.decode(profileData.getString("name"), android.util.Base64.DEFAULT)
                                            decryptedName = String(profileCipher.decrypt(encName))
                                            Log.i("BackgroundSyncManager", "Decrypted name: $decryptedName")
                                        } else {
                                            decryptedName = "" // Mark as fetched but no name to prevent infinite fetch loop
                                        }

                                        if (profileData.has("about") && !profileData.isNull("about")) {
                                            val encAbout = android.util.Base64.decode(profileData.getString("about"), android.util.Base64.DEFAULT)
                                            decryptedAbout = String(profileCipher.decrypt(encAbout))
                                        }
                                    } catch (e: Exception) {
                                        Log.e("BackgroundSyncManager", "Failed to decrypt profile fields: ${e.message}", e)
                                        decryptedName = "" // Prevent retry loop on decrypt failure too
                                    }

                                    db.saveContact(senderId, decryptedName, decryptedAbout, b64Key)
                                    if (!decryptedName.isNullOrBlank()) {
                                        val thread = db.getThreadByRecipient(senderId)
                                        if (thread != null) {
                                            db.updateThread(thread.copy(name = decryptedName))
                                        }
                                    }
                                } else {
                                    Log.e("BackgroundSyncManager", "Failed to fetch profile. Code: ${response.code()} Body: ${response.errorBody()?.string()}")
                                }
                            } catch (e: Exception) {
                                Log.e("BackgroundSyncManager", "Failed to fetch/decrypt profile for $senderId", e)
                            }
                        } else {
                            Log.i("BackgroundSyncManager", "Profile key found but contact $senderId already exists")
                        }
                    }

                    if (plaintext == "[No Body/Receipt]" || plaintext == "[Receipt]" || plaintext == "[No Data Message]") {
                        Log.i("BackgroundSyncManager", "Skipping non-data message from $senderId")
                        return@launch
                    }
                    val contactName = db.getContactName(senderId)
                    
                    var senderThread = db.getThreadByRecipient(senderId)
                    if (senderThread == null) {
                        db.insertThread(ThreadEntity(recipientNumber = senderId, name = contactName, lastMessageSnippet = plaintext, timestamp = System.currentTimeMillis()))
                        senderThread = db.getThreadByRecipient(senderId)
                    } else {
                        senderThread.name = contactName
                        senderThread.lastMessageSnippet = plaintext
                        senderThread.timestamp = System.currentTimeMillis()
                        db.updateThread(senderThread)
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
