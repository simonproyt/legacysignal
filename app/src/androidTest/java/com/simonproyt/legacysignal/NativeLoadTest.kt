package com.simonproyt.legacysignal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.util.KeyHelper
import org.signal.libsignal.protocol.IdentityKeyPair

@RunWith(AndroidJUnit4::class)
class NativeLoadTest {
    @Test
    fun testNativeInitialization() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(appContext)

        try {
            // Try to generate an Identity Key Pair to exercise JNI
            val identityKeyPair = IdentityKeyPair.generate()
            assertNotNull(identityKeyPair)

            println("Native initialization and JNI calls succeeded!")
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }
}
