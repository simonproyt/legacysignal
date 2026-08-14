package com.simonproyt.legacysignal

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.simonproyt.legacysignal", appContext.packageName)
        
        val phone = CredentialsManager.getPhoneNumber(appContext) ?: return
        val pass = CredentialsManager.getPassword(appContext) ?: return
        
        val client = com.simonproyt.legacysignal.api.SignalClient(appContext, phone, pass)
        val profileAuth = android.util.Base64.encodeToString(("$phone:$pass").toByteArray(), android.util.Base64.NO_WRAP)
        val senderId = CredentialsManager.getAci(appContext) ?: ""
        val response = client.api.getProfile("Basic $profileAuth", senderId).execute()
        
        android.util.Log.e("ProfileTest", "Response code: ${response.code()}")
        if (response.isSuccessful && response.body() != null) {
            val jsonString = response.body()!!.string()
            android.util.Log.e("ProfileTest", "Raw JSON: $jsonString")
        } else {
            android.util.Log.e("ProfileTest", "Error: ${response.errorBody()?.string()}")
        }
    }
}