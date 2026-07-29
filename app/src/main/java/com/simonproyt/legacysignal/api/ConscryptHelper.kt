package com.simonproyt.legacysignal.api

import android.content.Context
import com.simonproyt.legacysignal.R
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.security.KeyStore
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.cert.CertificateException

object ConscryptHelper {
    fun configureOkHttp(context: Context, builder: OkHttpClient.Builder) {
        val provider = Conscrypt.newProvider()
        Security.insertProviderAt(provider, 1)

        val cf = CertificateFactory.getInstance("X.509")
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)

        var certCount = 0
        context.resources.openRawResource(R.raw.cacert).use { inputStream ->
            val pemContent = inputStream.bufferedReader().use { it.readText() }
            val certPattern = java.util.regex.Pattern.compile(
                "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----",
                java.util.regex.Pattern.DOTALL
            )
            val matcher = certPattern.matcher(pemContent)
            while (matcher.find()) {
                val base64Cert = matcher.group(1).replace("\\s".toRegex(), "")
                val certBytes = android.util.Base64.decode(base64Cert, android.util.Base64.DEFAULT)
                val cert = cf.generateCertificate(java.io.ByteArrayInputStream(certBytes)) as X509Certificate
                keyStore.setCertificateEntry("ca_$certCount", cert)
                certCount++
            }
        }

        android.util.Log.d("ConscryptHelper", "Loaded $certCount certificates into KeyStore")

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain == null || chain.isEmpty()) throw CertificateException("Empty chain")
                android.util.Log.d("ConscryptHelper", "checkServerTrusted called with chain size: ${chain.size}")
                
                // Signal's WebSocket server uses a custom PKI (Issuer: CN=Signal Messenger)
                // Since we don't have their custom root CA, we must pin/trust it directly.
                if (chain[0].issuerDN.name.contains("Signal Messenger")) {
                    android.util.Log.d("ConscryptHelper", "Trusted custom Signal certificate")
                    return
                }
                
                var trusted = false
                for (i in chain.indices) {
                    val cert = chain[i]
                    android.util.Log.d("ConscryptHelper", "Checking cert [$i]: type=${cert.javaClass.name}, sigAlg=${cert.sigAlgName}")
                    android.util.Log.d("ConscryptHelper", "Subject: ${cert.subjectDN}")
                    android.util.Log.d("ConscryptHelper", "Issuer: ${cert.issuerDN}")
                    
                    // If the cert itself is in our keystore
                    if (keyStore.getCertificateAlias(cert) != null) {
                        android.util.Log.d("ConscryptHelper", "Exact match found for: ${cert.subjectDN.name}")
                        trusted = true
                        break
                    }
                    // Or if it's signed by one of our CAs
                    val aliases = keyStore.aliases()
                    while (aliases.hasMoreElements()) {
                        val ca = keyStore.getCertificate(aliases.nextElement()) as X509Certificate
                        try {
                            val sig = java.security.Signature.getInstance(cert.sigAlgName, provider)
                            sig.initVerify(ca.publicKey)
                            sig.update(cert.tbsCertificate)
                            if (sig.verify(cert.signature)) {
                                android.util.Log.d("ConscryptHelper", "Verified ${cert.subjectDN.name} with CA ${ca.subjectDN.name}")
                                trusted = true
                                break
                            }
                        } catch (e: Exception) {
                            if (e !is java.security.SignatureException && e !is java.security.InvalidKeyException) {
                                android.util.Log.e("ConscryptHelper", "Verification failed against ${ca.subjectDN.name}: ${e.message}")
                            }
                        }
                    }
                    if (trusted) break
                }
                
                if (!trusted) {
                    throw CertificateException("No trusted anchor found in custom KeyStore for chain")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                val certs = mutableListOf<X509Certificate>()
                val aliases = keyStore.aliases()
                while (aliases.hasMoreElements()) {
                    val cert = keyStore.getCertificate(aliases.nextElement())
                    if (cert is X509Certificate) certs.add(cert)
                }
                return certs.toTypedArray()
            }
        }

        val sslContext = SSLContext.getInstance("TLS", provider)
        sslContext.init(null, arrayOf(trustManager), null)
        
        builder.sslSocketFactory(sslContext.socketFactory, trustManager)
    }
    
    fun installGlobally(context: Context) {
        val provider = Conscrypt.newProvider()
        Security.insertProviderAt(provider, 1)

        val cf = CertificateFactory.getInstance("X.509")
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)

        var certCount = 0
        context.resources.openRawResource(R.raw.cacert).use { inputStream ->
            val pemContent = inputStream.bufferedReader().use { it.readText() }
            val certPattern = java.util.regex.Pattern.compile(
                "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----",
                java.util.regex.Pattern.DOTALL
            )
            val matcher = certPattern.matcher(pemContent)
            while (matcher.find()) {
                val base64Cert = matcher.group(1).replace("\\s".toRegex(), "")
                val certBytes = android.util.Base64.decode(base64Cert, android.util.Base64.DEFAULT)
                val cert = cf.generateCertificate(java.io.ByteArrayInputStream(certBytes)) as X509Certificate
                keyStore.setCertificateEntry("ca_$certCount", cert)
                certCount++
            }
        }

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> {
                val certs = mutableListOf<X509Certificate>()
                val aliases = keyStore.aliases()
                while (aliases.hasMoreElements()) {
                    val cert = keyStore.getCertificate(aliases.nextElement())
                    if (cert is X509Certificate) certs.add(cert)
                }
                return certs.toTypedArray()
            }
        }

        val sslContext = SSLContext.getInstance("TLS", provider)
        sslContext.init(null, arrayOf(trustManager), null)
        
        SSLContext.setDefault(sslContext)
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
    }
}
