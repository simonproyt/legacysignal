package com.simonproyt.legacysignal

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.simonproyt.legacysignal.api.SignalClient

class MainActivity : AppCompatActivity() {

    private lateinit var signalClient: SignalClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Read credentials from CredentialsManager
        val phone = CredentialsManager.getPhoneNumber(this) ?: ""
        val pass = CredentialsManager.getPassword(this) ?: ""
        signalClient = SignalClient(this, phone, pass)

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            signalClient.connect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        signalClient.disconnect()
    }
}