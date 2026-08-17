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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.simonproyt.legacysignal.api.ConnectionState
import com.simonproyt.legacysignal.crypto.AttachmentCipher

object BackgroundSyncManager {
    private var isRunning = false
    private var client: SignalClient? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var messageReceiver: MessageReceiver? = null
    private var appContext: Context? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _statusText = MutableStateFlow("Offline")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    fun start(context: Context) {
        val phone = CredentialsManager.getPhoneNumber(context) ?: return
        val pass = CredentialsManager.getPassword(context) ?: return

        if (isRunning && client != null) {
            if (_connectionState.value == ConnectionState.DISCONNECTED) {
                Log.i("BackgroundSyncManager", "Already running but disconnected, reconnecting...")
                client?.connect()
            }
            return
        }

        Log.i("BackgroundSyncManager", "Starting background sync for $phone")
        appContext = context.applicationContext
        client = SignalClient(appContext!!, phone, pass)
        val db = DatabaseHelper.getInstance(appContext!!)
        messageReceiver = MessageReceiver(appContext!!)

        client?.onStateChanged = { state ->
            _connectionState.value = state
            val interval = appContext?.getSharedPreferences("SignalPrefs", Context.MODE_PRIVATE)?.getInt("sync_interval_mins", 0) ?: 0
            _statusText.value = when (state) {
                ConnectionState.CONNECTED -> "● Connected"
                ConnectionState.CONNECTING -> "Connecting..."
                ConnectionState.DISCONNECTED -> if (interval > 0) "🕒 Polling (${interval}m)" else "● Offline"
            }
        }

        // Automatically fetch missing avatars for existing contacts in background
        scope.launch {
            try {
                db.getAllThreads().collectLatest { threads ->
                    for (thread in threads) {
                        val avatarPath = db.getContactAvatar(thread.recipientNumber)
                        val b64Key = db.getContactProfileKey(thread.recipientNumber)
                        if (avatarPath.isNullOrBlank() && !b64Key.isNullOrBlank()) {
                            try {
                                val keyBytes = android.util.Base64.decode(b64Key, android.util.Base64.NO_WRAP)
                                fetchAndSaveProfile(thread.recipientNumber, keyBytes)
                            } catch (e: Exception) {
                                Log.e("BackgroundSyncManager", "Error refreshing profile for ${thread.recipientNumber}", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        client?.onMessageReceived = { envelope ->
            scope.launch {
                try {
                    val decrypted = messageReceiver?.decryptMessage(envelope) ?: return@launch
                    val plaintext = decrypted.body
                    val senderId = decrypted.senderId

                    if (decrypted.profileKey != null) {
                        val currentName = db.getContactName(senderId)
                        val currentAvatar = db.getContactAvatar(senderId)
                        if (currentName.isNullOrEmpty() || currentAvatar.isNullOrEmpty()) {
                            fetchAndSaveProfile(senderId, decrypted.profileKey)
                        }
                    }

                    val attachments = decrypted.attachments
                    var downloadedImagePath: String? = null

                    if (attachments.isNotEmpty()) {
                        Log.i("BackgroundSyncManager", "Processing ${attachments.size} attachments from $senderId")
                        val phone = CredentialsManager.getPhoneNumber(context) ?: ""
                        val pass = CredentialsManager.getPassword(context) ?: ""
                        val authHeader = "Basic " + android.util.Base64.encodeToString(("$phone:$pass").toByteArray(), android.util.Base64.NO_WRAP)

                        for (attachment in attachments) {
                            val keyBytes = attachment.key?.toByteArray()
                            Log.i("BackgroundSyncManager", "Attachment: key size=${keyBytes?.size}, cdnKey=${if (attachment.hasCdnKey()) attachment.cdnKey else "none"}, cdnId=${if (attachment.hasCdnId()) attachment.cdnId else "none"}, cdnNum=${if (attachment.hasCdnNumber()) attachment.cdnNumber else "none"}")

                            if (keyBytes != null && keyBytes.isNotEmpty()) {
                                val urlsToTry = mutableListOf<String>()
                                val cdnNum = if (attachment.hasCdnNumber()) attachment.cdnNumber else 2

                                if (attachment.hasCdnKey() && attachment.cdnKey.isNotBlank()) {
                                    val key = attachment.cdnKey
                                    urlsToTry.add("https://cdn${cdnNum}.signal.org/attachments/$key")
                                    urlsToTry.add("https://cdn2.signal.org/attachments/$key")
                                    urlsToTry.add("https://cdn.signal.org/attachments/$key")
                                    urlsToTry.add("https://cdn3.signal.org/attachments/$key")
                                    urlsToTry.add("https://cdn2.signal.org/$key")
                                    urlsToTry.add("https://cdn.signal.org/$key")
                                }
                                if (attachment.hasCdnId()) {
                                    val id = attachment.cdnId
                                    urlsToTry.add("https://cdn${cdnNum}.signal.org/attachments/$id")
                                    urlsToTry.add("https://cdn2.signal.org/attachments/$id")
                                    urlsToTry.add("https://cdn.signal.org/attachments/$id")
                                    urlsToTry.add("https://chat.signal.org/v1/attachments/$id")
                                }

                                for (url in urlsToTry) {
                                    try {
                                        Log.i("BackgroundSyncManager", "Attempting attachment download from: $url")
                                        // Try unauthenticated first (standard for Signal CDN)
                                        var response = client!!.api.downloadAttachment(url).execute()
                                        if (!response.isSuccessful || response.body() == null) {
                                            // Fallback to authenticated
                                            response = client!!.api.downloadAvatar(authHeader, url).execute()
                                        }

                                        if (response.isSuccessful && response.body() != null) {
                                            val encBytes = response.body()!!.bytes()
                                            Log.i("BackgroundSyncManager", "Downloaded ${encBytes.size} bytes from $url, decrypting...")
                                            val decBytes = AttachmentCipher.decrypt(encBytes, keyBytes)
                                            Log.i("BackgroundSyncManager", "Decrypted ${decBytes.size} bytes successfully!")

                                            val attachDir = java.io.File(appContext!!.filesDir, "attachments")
                                            if (!attachDir.exists()) attachDir.mkdirs()
                                            val filename = "${java.util.UUID.randomUUID()}.jpg"
                                            val attachFile = java.io.File(attachDir, filename)
                                            attachFile.writeBytes(decBytes)
                                            downloadedImagePath = attachFile.absolutePath
                                            Log.i("BackgroundSyncManager", "Saved decrypted attachment to: $downloadedImagePath")
                                            break
                                        } else {
                                            Log.w("BackgroundSyncManager", "Download failed from $url with code ${response.code()}")
                                        }
                                    } catch (e: Exception) {
                                        Log.e("BackgroundSyncManager", "Error downloading/decrypting from $url: ${e.message}")
                                    }
                                }
                                if (downloadedImagePath != null) break
                            }
                        }
                    }

                    if (plaintext == "[No Body/Receipt]" || plaintext == "[Receipt]" || plaintext == "[No Data Message]") {
                        if (downloadedImagePath == null) {
                            Log.i("BackgroundSyncManager", "Skipping non-data message from $senderId")
                            return@launch
                        }
                    }

                    val messageSnippet = if (downloadedImagePath != null) {
                        if (plaintext != "[Attachment]" && plaintext != "[No Body/Receipt]" && plaintext.isNotBlank()) {
                            "📷 $plaintext"
                        } else {
                            "📷 Photo"
                        }
                    } else {
                        plaintext
                    }

                    val contactName = db.getContactName(senderId)

                    var senderThread = db.getThreadByRecipient(senderId)
                    if (senderThread == null) {
                        db.insertThread(ThreadEntity(recipientNumber = senderId, name = contactName, lastMessageSnippet = messageSnippet, timestamp = System.currentTimeMillis()))
                        senderThread = db.getThreadByRecipient(senderId)
                    } else {
                        senderThread.name = contactName
                        senderThread.lastMessageSnippet = messageSnippet
                        senderThread.timestamp = System.currentTimeMillis()
                        db.updateThread(senderThread)
                    }

                    val messageBody = if (plaintext == "[Attachment]" || plaintext == "[No Body/Receipt]") "" else plaintext
                    db.insertMessage(
                        MessageEntity(
                            threadId = senderThread!!.id,
                            senderId = senderId,
                            body = messageBody,
                            isOutgoing = false,
                            timestamp = System.currentTimeMillis(),
                            imagePath = downloadedImagePath
                        )
                    )
                    Log.i("BackgroundSyncManager", "Saved message from $senderId (imagePath: $downloadedImagePath)")

                    appContext?.let { ctx ->
                        showNotification(ctx, contactName ?: "Unknown", messageSnippet, senderThread.id, senderId)
                    }
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

    fun stop() {
        if (!isRunning) return
        Log.i("BackgroundSyncManager", "Stopping background sync")
        client?.onMessageReceived = null
        client?.disconnect()
        isRunning = false
        _connectionState.value = ConnectionState.DISCONNECTED
        val interval = appContext?.getSharedPreferences("SignalPrefs", Context.MODE_PRIVATE)?.getInt("sync_interval_mins", 0) ?: 0
        _statusText.value = if (interval > 0) "🕒 Polling (${interval}m)" else "● Offline"
    }

    fun fetchAndSaveProfile(senderId: String, profileKey: ByteArray) {
        val ctx = appContext ?: return
        val cli = client ?: return
        val db = DatabaseHelper.getInstance(ctx)

        scope.launch {
            try {
                val b64Key = android.util.Base64.encodeToString(profileKey, android.util.Base64.NO_WRAP)
                val phone = CredentialsManager.getPhoneNumber(ctx) ?: ""
                val pass = CredentialsManager.getPassword(ctx) ?: ""
                val profileAuth = android.util.Base64.encodeToString(("$phone:$pass").toByteArray(), android.util.Base64.NO_WRAP)

                val profileKeyObj = org.signal.libsignal.zkgroup.profiles.ProfileKey(profileKey)
                val aciObj = org.signal.libsignal.protocol.ServiceId.Aci.parseFromString(senderId)
                val versionStr = profileKeyObj.getProfileKeyVersion(aciObj).serialize()

                Log.i("BackgroundSyncManager", "Fetching profile for $senderId with version $versionStr")
                val response = cli.api.getProfile("Basic $profileAuth", senderId, versionStr).execute()
                if (response.isSuccessful && response.body() != null) {
                    val jsonString = response.body()!!.string()
                    Log.i("BackgroundSyncManager", "Profile JSON for $senderId: $jsonString")
                    val profileData = org.json.JSONObject(jsonString)
                    val profileCipher = com.simonproyt.legacysignal.api.crypto.ProfileCipher(profileKeyObj)

                    var decryptedName: String? = null
                    var decryptedAbout: String? = null

                    if (profileData.has("name") && !profileData.isNull("name")) {
                        try {
                            val encName = android.util.Base64.decode(profileData.getString("name"), android.util.Base64.DEFAULT)
                            decryptedName = String(profileCipher.decrypt(encName))
                            Log.i("BackgroundSyncManager", "Decrypted name: $decryptedName")
                        } catch (e: Exception) {
                            Log.e("BackgroundSyncManager", "Failed to decrypt name", e)
                        }
                    }

                    if (profileData.has("about") && !profileData.isNull("about")) {
                        try {
                            val encAbout = android.util.Base64.decode(profileData.getString("about"), android.util.Base64.DEFAULT)
                            decryptedAbout = String(profileCipher.decrypt(encAbout))
                        } catch (e: Exception) {
                            Log.e("BackgroundSyncManager", "Failed to decrypt about", e)
                        }
                    }

                    db.saveContact(senderId, decryptedName, decryptedAbout, b64Key)
                    if (!decryptedName.isNullOrBlank()) {
                        val thread = db.getThreadByRecipient(senderId)
                        if (thread != null) {
                            db.updateThread(thread.copy(name = decryptedName))
                        }
                    }

                    if (profileData.has("avatar") && !profileData.isNull("avatar")) {
                        val avatarUrlRel = profileData.getString("avatar")
                        Log.i("BackgroundSyncManager", "Avatar path found: $avatarUrlRel")
                        if (avatarUrlRel.isNotBlank()) {
                            val urlsToTry = mutableListOf<String>()
                            if (avatarUrlRel.startsWith("http")) {
                                urlsToTry.add(avatarUrlRel)
                            } else {
                                if (avatarUrlRel.startsWith("/")) {
                                    urlsToTry.add("https://chat.signal.org$avatarUrlRel")
                                } else {
                                    urlsToTry.add("https://chat.signal.org/v1/profile/$avatarUrlRel")
                                    urlsToTry.add("https://chat.signal.org/$avatarUrlRel")
                                    urlsToTry.add("https://cdn.signal.org/$avatarUrlRel")
                                    urlsToTry.add("https://chat.signal.org/v1/profile/avatar/$avatarUrlRel")
                                    urlsToTry.add("https://chat.signal.org/v1/profile/$senderId/avatar/$avatarUrlRel")
                                }
                            }

                            for (url in urlsToTry) {
                                try {
                                    Log.i("BackgroundSyncManager", "Trying avatar download from: $url")
                                    val avatarResp = cli.api.downloadAvatar("Basic $profileAuth", url).execute()
                                    Log.i("BackgroundSyncManager", "Avatar download code: ${avatarResp.code()}")
                                    if (avatarResp.isSuccessful && avatarResp.body() != null) {
                                        val encBytes = avatarResp.body()!!.bytes()
                                        Log.i("BackgroundSyncManager", "Downloaded ${encBytes.size} enc avatar bytes")
                                        val decBytes = profileCipher.decrypt(encBytes)
                                        Log.i("BackgroundSyncManager", "Decrypted ${decBytes.size} avatar bytes")

                                        val avatarDir = java.io.File(ctx.filesDir, "avatars")
                                        if (!avatarDir.exists()) avatarDir.mkdirs()
                                        val avatarFile = java.io.File(avatarDir, "${senderId}.png")
                                        java.io.FileOutputStream(avatarFile).use { it.write(decBytes) }
                                        db.saveContactAvatar(senderId, avatarFile.absolutePath)
                                        Log.i("BackgroundSyncManager", "Successfully saved avatar to: ${avatarFile.absolutePath}")
                                        break
                                    }
                                } catch (e: Exception) {
                                    Log.e("BackgroundSyncManager", "Avatar download failed for $url: ${e.message}")
                                }
                            }
                        }
                    }
                } else {
                    Log.e("BackgroundSyncManager", "Profile request failed: ${response.code()} ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                Log.e("BackgroundSyncManager", "fetchAndSaveProfile failed for $senderId", e)
            }
        }
    }

    private fun showNotification(context: Context, senderName: String, messageText: String, threadId: Long, recipientId: String) {
        val intent = android.content.Intent(context, ChatActivity::class.java).apply {
            putExtra("THREAD_ID", threadId)
            putExtra("RECIPIENT_ID", recipientId)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = android.app.PendingIntent.getActivity(context, threadId.toInt(), intent, pFlags)
    
        val builder = androidx.core.app.NotificationCompat.Builder(context, "legacy_signal_messages")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(senderName)
            .setContentText(messageText)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(android.app.Notification.DEFAULT_ALL)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(threadId.toInt(), builder.build())
    }
}
