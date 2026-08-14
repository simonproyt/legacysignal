package com.simonproyt.legacysignal

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.simonproyt.legacysignal.api.SignalClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.UUID
import android.util.Log
import com.simonproyt.legacysignal.api.SessionCreateRequest
import com.simonproyt.legacysignal.api.SessionCreateResponse
import com.simonproyt.legacysignal.api.CodeRequest
import com.simonproyt.legacysignal.api.VerifyResponse

class LoginActivity : AppCompatActivity() {

    private var currentSessionId: String? = null

    private val signalClient: SignalClient by lazy {
        SignalClient(this, "", "")
    }

    companion object {
        const val CAPTCHA_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Auto-login if credentials already exist
        if (CredentialsManager.hasCredentials(this)) {
            Log.d("LoginActivity", "Found existing credentials, bypassing login.")
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }

        if (savedInstanceState != null) {
            currentSessionId = savedInstanceState.getString("currentSessionId")
        }

        setContentView(R.layout.activity_login)
        val viewFlipper = findViewById<android.widget.ViewFlipper>(R.id.viewFlipper)
        val etPhoneNumber = findViewById<EditText>(R.id.etPhoneNumber)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnRequestSms = findViewById<Button>(R.id.btnRequestSms)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val tvCodeSentTo = findViewById<android.widget.TextView>(R.id.tvCodeSentTo)

        btnRequestSms.setOnClickListener {
            val phone = etPhoneNumber.text.toString().trim()
            if (phone.length < 5) {
                Toast.makeText(this, "Enter a valid phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnRequestSms.isEnabled = false
            Toast.makeText(this, "Creating session...", Toast.LENGTH_SHORT).show()
            signalClient.api.createSession(SessionCreateRequest(phone)).enqueue(object : Callback<SessionCreateResponse> {
                override fun onResponse(call: Call<SessionCreateResponse>, response: Response<SessionCreateResponse>) {
                    if (response.isSuccessful && response.body() != null) {
                        currentSessionId = response.body()!!.id
                        Toast.makeText(this@LoginActivity, "Session created, requesting SMS...", Toast.LENGTH_SHORT).show()
                        requestSms(phone, viewFlipper, tvCodeSentTo)
                    } else {
                        btnRequestSms.isEnabled = true
                        Toast.makeText(this@LoginActivity, "Session error: ${response.code()}", Toast.LENGTH_SHORT).show()
                        Log.e("LoginActivity", "Session request failed: ${response.code()} ${response.message()}")
                    }
                }
                override fun onFailure(call: Call<SessionCreateResponse>, t: Throwable) {
                    btnRequestSms.isEnabled = true
                    Toast.makeText(this@LoginActivity, "Network error on session", Toast.LENGTH_SHORT).show()
                }
            })
        }

        btnLogin.setOnClickListener {
            val code = etPassword.text.toString().trim()
            val phone = etPhoneNumber.text.toString().trim()
            if (code.isEmpty() || currentSessionId == null) {
                Toast.makeText(this, "Request SMS first and enter code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnLogin.isEnabled = false
            Toast.makeText(this, "Verifying code...", Toast.LENGTH_SHORT).show()
            signalClient.api.verifySmsCode(currentSessionId!!, mapOf("code" to code)).enqueue(object : Callback<VerifyResponse> {
                override fun onResponse(call: Call<VerifyResponse>, response: Response<VerifyResponse>) {
                    if (response.isSuccessful) {
                        val verified = response.body()?.verified ?: false
                        if (verified) {
                            val secret = ByteArray(16)
                            java.security.SecureRandom().nextBytes(secret)
                            val generatedPassword = android.util.Base64.encodeToString(secret, android.util.Base64.NO_WRAP)
                            val basicAuth = okhttp3.Credentials.basic(phone, generatedPassword)

                            val identityKeyPair = org.signal.libsignal.protocol.IdentityKeyPair.generate()
                            val aciIdentityKeyPair = identityKeyPair
                            val pniIdentityKeyPair = identityKeyPair

                            val signedPreKeyKeyPair = org.signal.libsignal.protocol.ecc.ECKeyPair.generate()
                            val signedPreKeySignature = identityKeyPair.privateKey.calculateSignature(signedPreKeyKeyPair.publicKey.serialize())
                            val aciSignedPreKeyKeyPair = signedPreKeyKeyPair
                            val pniSignedPreKeyKeyPair = signedPreKeyKeyPair
                            val aciSignedPreKeySignature = signedPreKeySignature
                            val pniSignedPreKeySignature = signedPreKeySignature

                            val kyberKeyPair = org.signal.libsignal.protocol.kem.KEMKeyPair.generate(org.signal.libsignal.protocol.kem.KEMKeyType.KYBER_1024)
                            val kyberSignature = identityKeyPair.privateKey.calculateSignature(kyberKeyPair.publicKey.serialize())
                            val aciKyberKeyPair = kyberKeyPair
                            val pniKyberKeyPair = kyberKeyPair
                            val aciKyberSignature = kyberSignature
                            val pniKyberSignature = kyberSignature

                            val profileKeyBytes = ByteArray(32)
                            java.security.SecureRandom().nextBytes(profileKeyBytes)

                            val regId = (Math.random() * 16384).toInt()
                            val pniRegId = (Math.random() * 16384).toInt()
                            val regData = com.simonproyt.legacysignal.api.RegistrationRequest(
                                sessionId = currentSessionId!!,
                                skipDeviceTransfer = true,
                                aciIdentityKey = android.util.Base64.encodeToString(aciIdentityKeyPair.publicKey.serialize(), android.util.Base64.NO_WRAP),
                                pniIdentityKey = android.util.Base64.encodeToString(pniIdentityKeyPair.publicKey.serialize(), android.util.Base64.NO_WRAP),
                                accountAttributes = com.simonproyt.legacysignal.api.AccountAttributes(
                                    voice = true,
                                    video = true,
                                    fetchesMessages = true,
                                    registrationId = regId,
                                    pniRegistrationId = pniRegId,
                                    name = null,
                                    capabilities = com.simonproyt.legacysignal.api.Capabilities(
                                        storage = true,
                                        versionedExpirationTimer = true,
                                        attachmentBackfill = true,
                                        spqr = true,
                                        usernameChangeSyncMessage = true
                                    ),
                                    unidentifiedAccessKey = android.util.Base64.encodeToString(com.simonproyt.legacysignal.api.crypto.ProfileCipher.deriveUnidentifiedAccessKey(profileKeyBytes), android.util.Base64.NO_WRAP),
                                    unrestrictedUnidentifiedAccess = true,
                                    discoverableByPhoneNumber = true
                                ),
                                aciSignedPreKey = com.simonproyt.legacysignal.api.ECSignedPreKey(
                                    keyId = 1,
                                    publicKey = android.util.Base64.encodeToString(aciSignedPreKeyKeyPair.publicKey.serialize(), android.util.Base64.NO_WRAP),
                                    signature = android.util.Base64.encodeToString(aciSignedPreKeySignature, android.util.Base64.NO_WRAP)
                                ),
                                pniSignedPreKey = com.simonproyt.legacysignal.api.ECSignedPreKey(
                                    keyId = 1,
                                    publicKey = android.util.Base64.encodeToString(pniSignedPreKeyKeyPair.publicKey.serialize(), android.util.Base64.NO_WRAP),
                                    signature = android.util.Base64.encodeToString(pniSignedPreKeySignature, android.util.Base64.NO_WRAP)
                                ),
                                aciPqLastResortPreKey = com.simonproyt.legacysignal.api.KEMSignedPreKey(
                                    keyId = 1,
                                    publicKey = android.util.Base64.encodeToString(aciKyberKeyPair.publicKey.serialize(), android.util.Base64.NO_WRAP),
                                    signature = android.util.Base64.encodeToString(aciKyberSignature, android.util.Base64.NO_WRAP)
                                ),
                                pniPqLastResortPreKey = com.simonproyt.legacysignal.api.KEMSignedPreKey(
                                    keyId = 1,
                                    publicKey = android.util.Base64.encodeToString(pniKyberKeyPair.publicKey.serialize(), android.util.Base64.NO_WRAP),
                                    signature = android.util.Base64.encodeToString(pniKyberSignature, android.util.Base64.NO_WRAP)
                                )
                            )

                            Toast.makeText(this@LoginActivity, "Setting up account...", Toast.LENGTH_SHORT).show()
                            signalClient.api.registerAccount(basicAuth, regData).enqueue(object : Callback<com.simonproyt.legacysignal.api.AccountCreationResponse> {
                                override fun onResponse(call: Call<com.simonproyt.legacysignal.api.AccountCreationResponse>, response2: Response<com.simonproyt.legacysignal.api.AccountCreationResponse>) {
                                    if (response2.isSuccessful) {
                                        val uuid = response2.body()?.uuid ?: phone
                                        val pni = response2.body()?.pni ?: phone
                                        CredentialsManager.saveCredentials(this@LoginActivity, uuid, generatedPassword)
                                        val aciKyberRecord = org.signal.libsignal.protocol.state.KyberPreKeyRecord(1, System.currentTimeMillis(), aciKyberKeyPair, aciKyberSignature)
                                        val pniKyberRecord = org.signal.libsignal.protocol.state.KyberPreKeyRecord(1, System.currentTimeMillis(), pniKyberKeyPair, pniKyberSignature)
                                        CredentialsManager.saveSignalKeys(this@LoginActivity,
                                            android.util.Base64.encodeToString(aciIdentityKeyPair.serialize(), android.util.Base64.NO_WRAP),
                                            android.util.Base64.encodeToString(pniIdentityKeyPair.serialize(), android.util.Base64.NO_WRAP),
                                            android.util.Base64.encodeToString(aciKyberRecord.serialize(), android.util.Base64.NO_WRAP),
                                            android.util.Base64.encodeToString(pniKyberRecord.serialize(), android.util.Base64.NO_WRAP)
                                        )
                                        CredentialsManager.saveProfileKey(this@LoginActivity, android.util.Base64.encodeToString(profileKeyBytes, android.util.Base64.NO_WRAP))

                                        val store = com.simonproyt.legacysignal.crypto.SharedPrefsSignalProtocolStore(this@LoginActivity)
                                        store.saveIdentityKeyPair(aciIdentityKeyPair)
                                        store.saveLocalRegistrationId(regId)
                                        val aciSignedPreKeyRecord = org.signal.libsignal.protocol.state.SignedPreKeyRecord(1, System.currentTimeMillis(), aciSignedPreKeyKeyPair, aciSignedPreKeySignature)
                                        store.storeSignedPreKey(1, aciSignedPreKeyRecord)
                                        store.storeKyberPreKey(1, aciKyberRecord)

                                        // Upload 100 One-Time PreKeys for ACI
                                        Toast.makeText(this@LoginActivity, "Uploading ACI PreKeys...", Toast.LENGTH_SHORT).show()
                                        val aciPreKeysList = mutableListOf<com.simonproyt.legacysignal.api.PreKey>()
                                        val pniPreKeysList = mutableListOf<com.simonproyt.legacysignal.api.PreKey>()
                                        for (i in 1..100) {
                                            val keyPair = org.signal.libsignal.protocol.ecc.ECKeyPair.generate()
                                            val preKeyRecord = org.signal.libsignal.protocol.state.PreKeyRecord(i, keyPair)
                                            store.storePreKey(i, preKeyRecord)
                                            val preKey = com.simonproyt.legacysignal.api.PreKey(
                                                keyId = i,
                                                publicKey = android.util.Base64.encodeToString(keyPair.publicKey.serialize(), android.util.Base64.NO_WRAP)
                                            )
                                            aciPreKeysList.add(preKey)
                                            pniPreKeysList.add(preKey)
                                        }

                                        val aciPreKeyUploadRequest = com.simonproyt.legacysignal.api.PreKeyUploadRequest(
                                            signedPreKey = com.simonproyt.legacysignal.api.ECSignedPreKey(
                                                keyId = 1,
                                                publicKey = android.util.Base64.encodeToString(aciSignedPreKeyKeyPair.publicKey.serialize(), android.util.Base64.NO_WRAP),
                                                signature = android.util.Base64.encodeToString(aciSignedPreKeySignature, android.util.Base64.NO_WRAP)
                                            ),
                                            preKeys = aciPreKeysList,
                                            pqLastResortPreKey = com.simonproyt.legacysignal.api.KEMSignedPreKey(
                                                keyId = 1,
                                                publicKey = android.util.Base64.encodeToString(aciKyberKeyPair.publicKey.serialize(), android.util.Base64.NO_WRAP),
                                                signature = android.util.Base64.encodeToString(aciKyberSignature, android.util.Base64.NO_WRAP)
                                            )
                                        )

                                        val pniPreKeyUploadRequest = com.simonproyt.legacysignal.api.PreKeyUploadRequest(
                                            signedPreKey = com.simonproyt.legacysignal.api.ECSignedPreKey(
                                                keyId = 1,
                                                publicKey = android.util.Base64.encodeToString(pniSignedPreKeyKeyPair.publicKey.serialize(), android.util.Base64.NO_WRAP),
                                                signature = android.util.Base64.encodeToString(pniSignedPreKeySignature, android.util.Base64.NO_WRAP)
                                            ),
                                            preKeys = pniPreKeysList,
                                            pqLastResortPreKey = com.simonproyt.legacysignal.api.KEMSignedPreKey(
                                                keyId = 1,
                                                publicKey = android.util.Base64.encodeToString(pniKyberKeyPair.publicKey.serialize(), android.util.Base64.NO_WRAP),
                                                signature = android.util.Base64.encodeToString(pniKyberSignature, android.util.Base64.NO_WRAP)
                                            )
                                        )

                                        val uuidAuth = okhttp3.Credentials.basic(uuid, generatedPassword)

                                        signalClient.api.uploadPreKeys(uuidAuth, "aci", aciPreKeyUploadRequest).enqueue(object : Callback<Void> {
                                            override fun onResponse(call: Call<Void>, response3: Response<Void>) {
                                                if (response3.isSuccessful) {
                                                    Toast.makeText(this@LoginActivity, "Uploading PNI PreKeys...", Toast.LENGTH_SHORT).show()
                                                    signalClient.api.uploadPreKeys(uuidAuth, "pni", pniPreKeyUploadRequest).enqueue(object : Callback<Void> {
                                                        override fun onResponse(call: Call<Void>, response4: Response<Void>) {
                                                            if (response4.isSuccessful) {
                                                                Toast.makeText(this@LoginActivity, "Uploading Profile...", Toast.LENGTH_SHORT).show()
                                                                val setupIntent = Intent(this@LoginActivity, ProfileSetupActivity::class.java).apply {
                                                                    putExtra("UUID", uuid)
                                                                    putExtra("AUTH", uuidAuth)
                                                                    putExtra("PROFILE_KEY_BYTES", android.util.Base64.encodeToString(profileKeyBytes, android.util.Base64.NO_WRAP))
                                                                }
                                                                startActivity(setupIntent)
                                                                finish()
                                                            } else {
                                                                btnLogin.isEnabled = true
                                                                Toast.makeText(this@LoginActivity, "PNI PreKey upload failed: ${response4.code()}", Toast.LENGTH_SHORT).show()
                                                                Log.e("LoginActivity", "PNI PreKey upload failed: ${response4.code()} ${response4.message()}")
                                                            }
                                                        }
                                                        override fun onFailure(call: Call<Void>, t: Throwable) {
                                                            btnLogin.isEnabled = true
                                                            Toast.makeText(this@LoginActivity, "Network error during PNI PreKey upload", Toast.LENGTH_SHORT).show()
                                                        }
                                                    })
                                                } else {
                                                    btnLogin.isEnabled = true
                                                    Toast.makeText(this@LoginActivity, "ACI PreKey upload failed: ${response3.code()}", Toast.LENGTH_SHORT).show()
                                                    Log.e("LoginActivity", "ACI PreKey upload failed: ${response3.code()} ${response3.message()}")
                                                }
                                            }
                                            override fun onFailure(call: Call<Void>, t: Throwable) {
                                                btnLogin.isEnabled = true
                                                Toast.makeText(this@LoginActivity, "Network error during ACI PreKey upload", Toast.LENGTH_SHORT).show()
                                            }
                                        })

                                    } else {
                                        btnLogin.isEnabled = true
                                        Toast.makeText(this@LoginActivity, "Account setup failed: ${response2.code()}", Toast.LENGTH_SHORT).show()
                                        Log.e("LoginActivity", "Account setup failed: ${response2.code()} ${response2.message()}")
                                    }
                                }
                                override fun onFailure(call: Call<com.simonproyt.legacysignal.api.AccountCreationResponse>, t: Throwable) {
                                    btnLogin.isEnabled = true
                                    Toast.makeText(this@LoginActivity, "Network error during setup", Toast.LENGTH_SHORT).show()
                                }
                            })
                        } else {
                            btnLogin.isEnabled = true
                            Toast.makeText(this@LoginActivity, "Incorrect verification code", Toast.LENGTH_SHORT).show()
                            Log.e("LoginActivity", "Code verification failed (verified=false)")
                        }
                    } else {
                        btnLogin.isEnabled = true
                        Toast.makeText(this@LoginActivity, "Verification failed: ${response.code()}", Toast.LENGTH_SHORT).show()
                        Log.e("LoginActivity", "Verify failed: ${response.code()} ${response.message()}")
                    }
                }
                override fun onFailure(call: Call<VerifyResponse>, t: Throwable) {
                    btnLogin.isEnabled = true
                    Toast.makeText(this@LoginActivity, "Network error", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentSessionId", currentSessionId)
    }

    private fun requestSms(phone: String, viewFlipper: android.widget.ViewFlipper? = null, tvCodeSentTo: android.widget.TextView? = null) {
        signalClient.api.requestSmsCode(currentSessionId!!, CodeRequest()).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response2: Response<Void>) {
                if (response2.isSuccessful) {
                    Toast.makeText(this@LoginActivity, "SMS requested!", Toast.LENGTH_SHORT).show()
                    if (viewFlipper != null && tvCodeSentTo != null) {
                        tvCodeSentTo.text = "Enter the code we sent to $phone"
                        viewFlipper.displayedChild = 1
                    }
                } else if (response2.code() == 409 || response2.code() == 402) {
                    // CAPTCHA or missing info required
                    Toast.makeText(this@LoginActivity, "Captcha required...", Toast.LENGTH_SHORT).show()
                    Log.w("LoginActivity", "Captcha required. Launching CaptchaActivity.")
                    val intent = Intent(this@LoginActivity, CaptchaActivity::class.java)
                    val url = response2.headers()["x-signal-captcha-url"]
                    if (url != null) {
                        intent.putExtra("CAPTCHA_URL", url)
                    }
                    startActivityForResult(intent, CAPTCHA_REQUEST_CODE)
                } else {
                    Toast.makeText(this@LoginActivity, "SMS error: ${response2.code()}", Toast.LENGTH_SHORT).show()
                    Log.e("LoginActivity", "SMS request failed: ${response2.code()} ${response2.message()}")
                }
            }
            override fun onFailure(call: Call<Void>, t: Throwable) {
                Toast.makeText(this@LoginActivity, "Network error on SMS", Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAPTCHA_REQUEST_CODE && resultCode == RESULT_OK) {
            val token = data?.getStringExtra("captcha_token")
            if (token != null && currentSessionId != null) {
                Toast.makeText(this, "Captcha solved, submitting...", Toast.LENGTH_SHORT).show()
                signalClient.api.patchSession(currentSessionId!!, mapOf("captcha" to token)).enqueue(object : Callback<SessionCreateResponse> {
                    override fun onResponse(call: Call<SessionCreateResponse>, response: Response<SessionCreateResponse>) {
                        if (response.isSuccessful) {
                            val viewFlipper = findViewById<android.widget.ViewFlipper>(R.id.viewFlipper)
                            val tvCodeSentTo = findViewById<android.widget.TextView>(R.id.tvCodeSentTo)
                            val etPhoneNumber = findViewById<android.widget.EditText>(R.id.etPhoneNumber)
                            requestSms(etPhoneNumber.text.toString().trim(), viewFlipper, tvCodeSentTo)
                        } else {
                            Toast.makeText(this@LoginActivity, "Captcha rejected: ${response.code()}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onFailure(call: Call<SessionCreateResponse>, t: Throwable) {
                        Toast.makeText(this@LoginActivity, "Network error on captcha", Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }
    }
}
