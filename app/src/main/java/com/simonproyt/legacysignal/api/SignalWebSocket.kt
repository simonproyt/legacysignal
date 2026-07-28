package com.simonproyt.legacysignal.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class SignalWebSocket(
    private val client: OkHttpClient,
    private val url: String,
    private val authHeader: String
) {
    private var webSocket: WebSocket? = null

    fun connect() {
        Log.d("SignalWebSocket", "Connecting to $url")
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", authHeader)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("SignalWebSocket", "Connected!")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("SignalWebSocket", "Received message: $text")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d("SignalWebSocket", "Received bytes: ${bytes.hex()}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("SignalWebSocket", "Closing: $code / $reason")
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("SignalWebSocket", "Error: ${t.message}", t)
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }
}
