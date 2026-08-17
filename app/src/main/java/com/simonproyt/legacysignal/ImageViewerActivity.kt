package com.simonproyt.legacysignal

import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class ImageViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_image_viewer)

        val imagePath = intent.getStringExtra("IMAGE_PATH")
        val ivFullScreen = findViewById<ImageView>(R.id.ivFullScreen)
        val btnBack = findViewById<Button>(R.id.btnBack)
        val btnSave = findViewById<Button>(R.id.btnSave)

        if (!imagePath.isNullOrBlank()) {
            val file = File(imagePath)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    ivFullScreen.setImageBitmap(bitmap)
                } else {
                    Toast.makeText(this, "Failed to decode image", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnBack.setOnClickListener {
            finish()
        }

        btnSave.setOnClickListener {
            if (imagePath.isNullOrBlank()) {
                Toast.makeText(this, "No image to save", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            try {
                val srcFile = File(imagePath)
                if (!srcFile.exists()) {
                    Toast.makeText(this, "Source file not found", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val appDir = File(picturesDir, "LegacySignal")
                if (!appDir.exists()) appDir.mkdirs()

                val destFile = File(appDir, "signal_${System.currentTimeMillis()}.jpg")
                srcFile.copyTo(destFile, overwrite = true)

                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(destFile.absolutePath),
                    arrayOf("image/jpeg")
                ) { path, uri ->
                    // Scanned into media store
                }

                Toast.makeText(this, "Saved to Pictures/LegacySignal!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error saving image: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
