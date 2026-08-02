package com.simonproyt.legacysignal

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.AllowOrDeny

class CaptchaActivity : Activity() {

    private lateinit var geckoView: GeckoView
    private lateinit var progressBar: ProgressBar
    private var geckoSession: GeckoSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            com.simonproyt.legacysignal.api.ConscryptHelper.installGlobally(this)
        } catch (e: Exception) {
            Log.e("CaptchaActivity", "Failed to install Conscrypt globally", e)
        }
        
        setContentView(R.layout.activity_captcha)

        geckoView = findViewById(R.id.geckoView)
        progressBar = findViewById(R.id.progressBar)

        val runtime = GeckoRuntime.create(this)
        geckoSession = GeckoSession()
        
        geckoSession?.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                progressBar.visibility = View.GONE
            }
        }
        
        geckoSession?.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                val url = request.uri
                if (url.startsWith("signalcaptcha://")) {
                    Log.d("CaptchaActivity", "Intercepted captcha callback: $url")
                    val token = url.removePrefix("signalcaptcha://")
                    
                    val resultIntent = Intent()
                    resultIntent.putExtra("captcha_token", token)
                    setResult(RESULT_OK, resultIntent)
                    finish()
                    
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
        }

        geckoSession?.open(runtime)
        geckoView.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        geckoView.setSession(geckoSession!!)

        geckoSession?.loadUri("https://signalcaptchas.org/registration/generate.html")
    }
}
