package com.simonproyt.legacysignal

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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
import java.io.ByteArrayOutputStream
import java.io.File

class ChatActivity : AppCompatActivity() {

    private lateinit var signalClient: SignalClient
    private var messageSender: MessageSender? = null
    private lateinit var messageAdapter: MessageAdapter
    private val messagesList = mutableListOf<ChatMessage>()
    private val db by lazy { DatabaseHelper.getInstance(this) }
    
    private var recipientId: String = ""
    private var threadId: Long = 0
    private var currentPhotoPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recipientId = intent.getStringExtra("RECIPIENT_ID") ?: savedInstanceState?.getString("RECIPIENT_ID") ?: ""
        threadId = intent.getLongExtra("THREAD_ID", 0)
        if (threadId == 0L) {
            threadId = savedInstanceState?.getLong("THREAD_ID", 0) ?: 0L
        }
        currentPhotoPath = savedInstanceState?.getString("CURRENT_PHOTO_PATH")

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
            val options = arrayOf("📷 Take Photo", "🖼️ Choose from Gallery")
            android.app.AlertDialog.Builder(this)
                .setTitle("Send Photo")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> checkCameraPermissionAndTakePhoto()
                        1 -> pickFromGallery()
                    }
                }
                .show()
        }

        btnSend.setOnClickListener {
            val text = etMessage.text.toString()
            if (text.isNotBlank()) {
                etMessage.text.clear()
                
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val sender = messageSender ?: run {
                            Log.e("ChatActivity", "messageSender is null!")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@ChatActivity, "Not connected - cannot send", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                        Log.i("ChatActivity", "Sending message to $recipientId: ${text.take(50)}...")
                        val success = sender.sendMessage(recipientId, text)
                        
                        if (success) {
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
                            Log.i("ChatActivity", "Message sent and saved successfully")
                        } else {
                            Log.e("ChatActivity", "sendMessage returned false for $recipientId")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@ChatActivity, "Failed to send message", Toast.LENGTH_SHORT).show()
                                messageAdapter.addMessage(
                                    ChatMessage(
                                        id = -1L,
                                        sender = "System",
                                        text = "⚠️ Failed to send: \"${text.take(50)}\"",
                                        timestamp = System.currentTimeMillis(),
                                        isOutgoing = false
                                    )
                                )
                                rvMessages.scrollToPosition(messagesList.size - 1)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ChatActivity", "Exception sending message", e)
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

    private fun checkCameraPermissionAndTakePhoto() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
                return
            }
        }
        takePhoto()
    }

    private fun takePhoto() {
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (takePictureIntent.resolveActivity(packageManager) != null) {
            val photoFile: File? = try {
                createImageFile()
            } catch (ex: Exception) {
                Log.e("ChatActivity", "Error creating image file: ${ex.message}", ex)
                null
            }

            if (photoFile != null) {
                val photoURI: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        FileProvider.getUriForFile(
                            this,
                            "$packageName.fileprovider",
                            photoFile
                        )
                    } catch (e: Exception) {
                        Uri.fromFile(photoFile)
                    }
                } else {
                    Uri.fromFile(photoFile)
                }

                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                takePictureIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

                val resInfoList = packageManager.queryIntentActivities(takePictureIntent, PackageManager.MATCH_DEFAULT_ONLY)
                for (resolveInfo in resInfoList) {
                    val pkgName = resolveInfo.activityInfo.packageName
                    grantUriPermission(pkgName, photoURI, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
            } else {
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
            }
        } else {
            Toast.makeText(this, "No camera application found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createImageFile(): File {
        val storageDir = if (Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                ?: getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: cacheDir
        } else {
            cacheDir
        }
        if (!storageDir.exists()) storageDir.mkdirs()
        val image = File(storageDir, "camera_${System.currentTimeMillis()}.jpg")
        currentPhotoPath = image.absolutePath
        Log.i("ChatActivity", "Created photo capture target file: $currentPhotoPath")
        return image
    }

    private fun pickFromGallery() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Select Picture"), REQUEST_IMAGE_PICK)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open image picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun compressImageIfNeeded(rawBytes: ByteArray): ByteArray {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) return rawBytes

            val maxDim = 2048
            var inSampleSize = 1
            if (origWidth > maxDim || origHeight > maxDim) {
                val halfHeight = origHeight / 2
                val halfWidth = origWidth / 2
                while ((halfHeight / inSampleSize) >= maxDim || (halfWidth / inSampleSize) >= maxDim) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions) ?: return rawBytes

            // Handle EXIF orientation (rotate if needed)
            val exifOrientation = try {
                val exif = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(rawBytes))
                exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
            } catch (e: Exception) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            }

            val matrix = android.graphics.Matrix()
            when (exifOrientation) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            }

            val scale = minOf(1f, maxDim.toFloat() / maxOf(bitmap.width, bitmap.height))
            if (scale < 1f) {
                matrix.postScale(scale, scale)
            }

            val finalBitmap = if (!matrix.isIdentity) {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                    if (it != bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }

            val outputStream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            finalBitmap.recycle()
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.w("ChatActivity", "Error compressing image: ${e.message}", e)
            rawBytes
        }
    }

    private fun sendImageBytes(rawBytes: ByteArray) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = compressImageIfNeeded(rawBytes)
                val attachDir = File(filesDir, "attachments")
                if (!attachDir.exists()) attachDir.mkdirs()
                val localFile = File(attachDir, "outgoing_${System.currentTimeMillis()}.jpg")
                localFile.writeBytes(bytes)

                val phone = CredentialsManager.getPhoneNumber(this@ChatActivity) ?: ""
                val time = System.currentTimeMillis()

                if (threadId == 0L && recipientId.isNotEmpty()) {
                    val existingThread = db.getThreadByRecipient(recipientId)
                    threadId = existingThread?.id ?: db.insertThread(
                        ThreadEntity(recipientNumber = recipientId, lastMessageSnippet = "", timestamp = time)
                    )
                }

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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                takePhoto()
            } else {
                Toast.makeText(this, "Camera permission is required to take photos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return

        when (requestCode) {
            REQUEST_IMAGE_PICK -> {
                val uri = data?.data ?: return
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val inputStream = contentResolver.openInputStream(uri)
                        val bytes = inputStream?.readBytes()
                        inputStream?.close()
                        if (bytes != null && bytes.isNotEmpty()) {
                            sendImageBytes(bytes)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@ChatActivity, "Failed to read photo: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            REQUEST_IMAGE_CAPTURE -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        var photoBytes: ByteArray? = null

                        // Check saved file from camera URI
                        val path = currentPhotoPath
                        if (path != null) {
                            val file = File(path)
                            if (file.exists() && file.length() > 0) {
                                photoBytes = file.readBytes()
                                Log.i("ChatActivity", "Read camera photo from file: $path (${photoBytes.size} bytes)")
                            } else {
                                Log.w("ChatActivity", "Camera photo target file not found or empty: $path (exists=${file.exists()}, length=${file.length()})")
                            }
                        }

                        // Fallback to data URI if available
                        if (photoBytes == null && data?.data != null) {
                            try {
                                contentResolver.openInputStream(data.data!!)?.use { stream ->
                                    photoBytes = stream.readBytes()
                                }
                            } catch (e: Exception) {
                                Log.w("ChatActivity", "Error reading data.data: ${e.message}")
                            }
                        }

                        // Fallback to thumbnail bitmap if returned in extras
                        if (photoBytes == null && data?.extras?.get("data") is Bitmap) {
                            val bitmap = data.extras!!.get("data") as Bitmap
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                            photoBytes = stream.toByteArray()
                        }

                        val finalBytes = photoBytes
                        if (finalBytes != null && finalBytes.isNotEmpty()) {
                            sendImageBytes(finalBytes)
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@ChatActivity, "No photo data captured", Toast.LENGTH_SHORT).show()
                            }
                        }
                        
                        // Clean up temporary camera capture file
                        if (currentPhotoPath != null) {
                            val f = File(currentPhotoPath!!)
                            if (f.exists()) f.delete()
                            currentPhotoPath = null
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@ChatActivity, "Failed to capture photo: ${e.message}", Toast.LENGTH_SHORT).show()
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

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("SignalPrefs", android.content.Context.MODE_PRIVATE)
        if (prefs.getInt("sync_interval_mins", 0) == 0) {
            BackgroundSyncManager.start(this)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("RECIPIENT_ID", recipientId)
        outState.putLong("THREAD_ID", threadId)
        outState.putString("CURRENT_PHOTO_PATH", currentPhotoPath)
    }

    companion object {
        private const val REQUEST_IMAGE_PICK = 1001
        private const val REQUEST_IMAGE_CAPTURE = 1002
        private const val REQUEST_CAMERA_PERMISSION = 2001
    }
}