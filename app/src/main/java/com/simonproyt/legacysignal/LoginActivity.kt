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
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
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
                            val generatedPassword = android.util.Base64.encodeToString(secret, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                            val basicAuth = okhttp3.Credentials.basic(phone, generatedPassword)
                            
                            val aciIdentityKeyPair = org.signal.libsignal.protocol.IdentityKeyPair.generate()
                            val pniIdentityKeyPair = org.signal.libsignal.protocol.IdentityKeyPair.generate()

                            val aciSignedPreKeyKeyPair = org.signal.libsignal.protocol.ecc.ECKeyPair.generate()
                            val aciSignedPreKeySignature = aciIdentityKeyPair.privateKey.calculateSignature(aciSignedPreKeyKeyPair.publicKey.serialize())

                            val pniSignedPreKeyKeyPair = org.signal.libsignal.protocol.ecc.ECKeyPair.generate()
                            val pniSignedPreKeySignature = pniIdentityKeyPair.privateKey.calculateSignature(pniSignedPreKeyKeyPair.publicKey.serialize())

                            val aciKyberKeyPair = org.signal.libsignal.protocol.kem.KEMKeyPair.generate(org.signal.libsignal.protocol.kem.KEMKeyType.KYBER_1024)
                            val aciKyberSignature = aciIdentityKeyPair.privateKey.calculateSignature(aciKyberKeyPair.publicKey.serialize())

                            val pniKyberKeyPair = org.signal.libsignal.protocol.kem.KEMKeyPair.generate(org.signal.libsignal.protocol.kem.KEMKeyType.KYBER_1024)
                            val pniKyberSignature = pniIdentityKeyPair.privateKey.calculateSignature(pniKyberKeyPair.publicKey.serialize())

                            val regId = (Math.random() * 16384).toInt()
                            val regData = com.simonproyt.legacysignal.api.RegistrationRequest(
                                sessionId = currentSessionId!!,
                                skipDeviceTransfer = true,
                                aciIdentityKey = android.util.Base64.encodeToString(aciIdentityKeyPair.publicKey.serialize(), android.util.Base64.NO_WRAP),
                                pniIdentityKey = android.util.Base64.encodeToString(pniIdentityKeyPair.publicKey.serialize(), android.util.Base64.NO_WRAP),
                                accountAttributes = com.simonproyt.legacysignal.api.AccountAttributes(
                                    fetchesMessages = true,
                                    registrationId = regId,
                                    name = "",
                                    capabilities = listOf("spqr")
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
                            signalClient.api.registerAccount(basicAuth, regData).enqueue(object : Callback<Void> {
                                override fun onResponse(call: Call<Void>, response2: Response<Void>) {
                                    if (response2.isSuccessful) {
                                        CredentialsManager.saveCredentials(this@LoginActivity, phone, generatedPassword)
                                        val aciKyberRecord = org.signal.libsignal.protocol.state.KyberPreKeyRecord(1, System.currentTimeMillis(), aciKyberKeyPair, aciKyberSignature)
                                        val pniKyberRecord = org.signal.libsignal.protocol.state.KyberPreKeyRecord(1, System.currentTimeMillis(), pniKyberKeyPair, pniKyberSignature)
                                        CredentialsManager.saveSignalKeys(this@LoginActivity, 
                                            android.util.Base64.encodeToString(aciIdentityKeyPair.serialize(), android.util.Base64.NO_WRAP),
                                            android.util.Base64.encodeToString(pniIdentityKeyPair.serialize(), android.util.Base64.NO_WRAP),
                                            android.util.Base64.encodeToString(aciKyberRecord.serialize(), android.util.Base64.NO_WRAP),
                                            android.util.Base64.encodeToString(pniKyberRecord.serialize(), android.util.Base64.NO_WRAP)
                                        )
                                        Toast.makeText(this@LoginActivity, "Registered successfully!", Toast.LENGTH_LONG).show()
                                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                                        finish()
                                    } else {
                                        btnLogin.isEnabled = true
                                        Toast.makeText(this@LoginActivity, "Account setup failed: ${response2.code()}", Toast.LENGTH_SHORT).show()
                                        Log.e("LoginActivity", "Account setup failed: ${response2.code()} ${response2.message()}")
                                    }
                                }
                                override fun onFailure(call: Call<Void>, t: Throwable) {
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
