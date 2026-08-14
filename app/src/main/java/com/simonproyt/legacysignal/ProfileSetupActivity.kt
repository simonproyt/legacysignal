package com.simonproyt.legacysignal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.simonproyt.legacysignal.api.SignalClient
import com.simonproyt.legacysignal.api.SignalServiceProfileWrite
import com.simonproyt.legacysignal.api.crypto.ProfileCipher
import org.signal.libsignal.protocol.ServiceId
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ProfileSetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_setup)

        val etGivenName = findViewById<EditText>(R.id.etGivenName)
        val etFamilyName = findViewById<EditText>(R.id.etFamilyName)
        val etAbout = findViewById<EditText>(R.id.etAbout)
        val btnSaveProfile = findViewById<Button>(R.id.btnSaveProfile)

        val uuid = intent.getStringExtra("UUID")
        val auth = intent.getStringExtra("AUTH")
        val profileKeyBytesBase64 = intent.getStringExtra("PROFILE_KEY_BYTES")

        if (uuid == null || auth == null || profileKeyBytesBase64 == null) {
            Toast.makeText(this, "Missing setup parameters", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val profileKeyBytes = Base64.decode(profileKeyBytesBase64, Base64.NO_WRAP)

        btnSaveProfile.setOnClickListener {
            val givenName = etGivenName.text.toString().trim()
            val familyName = etFamilyName.text.toString().trim()
            val about = etAbout.text.toString().trim()

            if (givenName.isEmpty()) {
                Toast.makeText(this, "Given name is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val fullName = if (familyName.isNotEmpty()) "$givenName $familyName" else givenName

            btnSaveProfile.isEnabled = false

            try {
                val profileKey = ProfileKey(profileKeyBytes)
                val profileCipher = ProfileCipher(profileKey)
                
                val aciObj = ServiceId.Aci.parseFromString(uuid)
                val version = profileKey.getProfileKeyVersion(aciObj)
                val commitment = profileKey.getCommitment(aciObj)

                val encName = Base64.encodeToString(profileCipher.encryptString(fullName, 53), Base64.NO_WRAP)
                val encAbout = Base64.encodeToString(profileCipher.encryptString(if (about.isEmpty()) "" else about, 128), Base64.NO_WRAP)

                val profileWrite = SignalServiceProfileWrite(
                    version = version.serialize(),
                    name = encName,
                    about = encAbout,
                    aboutEmoji = Base64.encodeToString(profileCipher.encryptString("", 32), Base64.NO_WRAP),
                    paymentAddress = null,
                    phoneNumberSharing = Base64.encodeToString(profileCipher.encryptBoolean(true), Base64.NO_WRAP),
                    avatar = false,
                    sameAvatar = false,
                    commitment = Base64.encodeToString(commitment.serialize(), Base64.NO_WRAP),
                    badgeIds = emptyList()
                )

                val phone = CredentialsManager.getPhoneNumber(this) ?: ""
                val pass = CredentialsManager.getPassword(this) ?: ""
                val client = SignalClient(this, phone, pass)

                client.api.uploadProfile(auth, profileWrite).enqueue(object : Callback<Void> {
                    override fun onResponse(call: Call<Void>, response: Response<Void>) {
                        if (response.isSuccessful) {
                            android.util.Log.i("ProfileSetupActivity", "Profile upload successful!")
                            getSharedPreferences("SignalPrefs", Context.MODE_PRIVATE).edit()
                                .putString("my_given_name", givenName)
                                .putString("my_family_name", familyName)
                                .putString("my_about", about)
                                .apply()

                            val intent = Intent(this@ProfileSetupActivity, HomeActivity::class.java)
                            startActivity(intent)
                            finish()
                        } else {
                            android.util.Log.e("ProfileSetupActivity", "Profile upload failed: ${response.code()}")
                            btnSaveProfile.isEnabled = true
                            Toast.makeText(this@ProfileSetupActivity, "Profile upload failed: ${response.code()}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onFailure(call: Call<Void>, t: Throwable) {
                        btnSaveProfile.isEnabled = true
                        Toast.makeText(this@ProfileSetupActivity, "Network error", Toast.LENGTH_SHORT).show()
                    }
                })

            } catch (e: Exception) {
                btnSaveProfile.isEnabled = true
                Toast.makeText(this, "Encryption error: ${e.message}", Toast.LENGTH_LONG).show()
                Log.e("ProfileSetup", "Encryption error", e)
            }
        }
    }
}
