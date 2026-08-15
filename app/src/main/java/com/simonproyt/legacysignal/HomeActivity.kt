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
        
        val serviceIntent = Intent(this, SyncService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val myName = getSharedPreferences("SignalPrefs", android.content.Context.MODE_PRIVATE).getString("my_given_name", "Unknown User")
        val myUuid = CredentialsManager.getPhoneNumber(this) ?: "Unknown"
        val tvMyUuid = findViewById<TextView>(R.id.tvMyUuid)
        
        var uuidVisible = false
        
        fun updateProfileText() {
            val uuidDisplay = if (uuidVisible) myUuid else "(Tap to show UUID)"
            tvMyUuid.text = "My Profile:\n$myName\n$uuidDisplay"
        }
        
        updateProfileText()
        tvMyUuid.setOnClickListener {
            uuidVisible = !uuidVisible
            updateProfileText()
        }

        threadAdapter = ThreadAdapter(emptyList()) { thread ->
            val intent = Intent(this, ChatActivity::class.java).apply {
                putExtra("THREAD_ID", thread.id)
                putExtra("RECIPIENT_ID", thread.recipientNumber)
            }
            startActivity(intent)
        }

        val rvThreads = findViewById<RecyclerView>(R.id.rvThreads)
        val llEmptyState = findViewById<android.view.View>(R.id.llEmptyState)
        rvThreads.layoutManager = LinearLayoutManager(this)
        rvThreads.adapter = threadAdapter

        val fabNewChat = findViewById<FloatingActionButton>(R.id.fabNewChat)
        fabNewChat.setOnClickListener {
            showNewChatDialog()
        }

        lifecycleScope.launch {
            BackgroundSyncManager.statusText.collectLatest { status ->
                supportActionBar?.subtitle = status
            }
        }

        lifecycleScope.launch {
            db.getAllThreads().collectLatest { threads ->
                threadAdapter.updateThreads(threads)
                if (threads.isEmpty()) {
                    llEmptyState.visibility = android.view.View.VISIBLE
                    rvThreads.visibility = android.view.View.GONE
                } else {
                    llEmptyState.visibility = android.view.View.GONE
                    rvThreads.visibility = android.view.View.VISIBLE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("SignalPrefs", android.content.Context.MODE_PRIVATE)
        if (prefs.getInt("sync_interval_mins", 0) == 0) {
            val serviceIntent = Intent(this, SyncService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            BackgroundSyncManager.start(this)
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_home, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showSyncSettingsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSyncSettingsDialog() {
        val prefs = getSharedPreferences("SignalPrefs", android.content.Context.MODE_PRIVATE)
        val currentInterval = prefs.getInt("sync_interval_mins", 0)
        
        val options = arrayOf("Persistent Connection (High Battery)", "Poll every 15 minutes", "Poll every 30 minutes", "Poll every 1 hour")
        val values = intArrayOf(0, 15, 30, 60)
        val checkedItem = values.indexOf(currentInterval).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle("Sync Settings")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newInterval = values[which]
                prefs.edit().putInt("sync_interval_mins", newInterval).apply()
                
                // Restart service to apply settings
                val serviceIntent = Intent(this, SyncService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
