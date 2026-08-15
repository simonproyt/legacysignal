package com.simonproyt.legacysignal.api

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

enum class ConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

class SignalWebSocket(
    private val client: OkHttpClient,
    private val url: String,
    private val authHeader: String,
    private val onMessageReceived: ((com.simonproyt.legacysignal.api.push.SignalServiceProtos.Envelope) -> Unit)? = null,
    var onStateChanged: ((ConnectionState) -> Unit)? = null
) {
    private var webSocket: WebSocket? = null
    private var manualDisconnect = false
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    fun connect() {
        manualDisconnect = false
        reconnectRunnable?.let { handler.removeCallbacks(it) }

        if (webSocket != null) {
            Log.d("SignalWebSocket", "Closing existing socket before reconnecting")
            try {
                webSocket?.close(1000, null)
            } catch (e: Exception) {
                // Ignore
            }
            webSocket = null
        }

        Log.d("SignalWebSocket", "Connecting to $url")
        onStateChanged?.invoke(ConnectionState.CONNECTING)
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", authHeader)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("SignalWebSocket", "Connected!")
                onStateChanged?.invoke(ConnectionState.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("SignalWebSocket", "Received message: $text")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d("SignalWebSocket", "Received bytes: ${bytes.size()} bytes")
                try {
                    val message = com.simonproyt.legacysignal.api.websocket.WebSocketProtos.WebSocketMessage.parseFrom(bytes.toByteArray())
                    
                    if (message.type == com.simonproyt.legacysignal.api.websocket.WebSocketProtos.WebSocketMessage.Type.REQUEST) {
                        val request = message.request
                        Log.d("SignalWebSocket", "Received REQUEST: ${request.verb} ${request.path}")
                        
                        // Send 200 OK back to the server so it doesn't think we disconnected
                        val response = com.simonproyt.legacysignal.api.websocket.WebSocketProtos.WebSocketResponseMessage.newBuilder()
                            .setId(request.id)
                            .setStatus(200)
                            .setMessage("OK")
                            .build()
                            
                        val responseMsg = com.simonproyt.legacysignal.api.websocket.WebSocketProtos.WebSocketMessage.newBuilder()
                            .setType(com.simonproyt.legacysignal.api.websocket.WebSocketProtos.WebSocketMessage.Type.RESPONSE)
                            .setResponse(response)
                            .build()
                            
                        webSocket.send(ByteString.of(*responseMsg.toByteArray()))
                        
                        // Handle actual message bodies (like Envelope) later via callback
                        if (request.path == "/api/v1/message" && !request.body.isEmpty) {
                            try {
                                val envelope = com.simonproyt.legacysignal.api.push.SignalServiceProtos.Envelope.parseFrom(request.body)
                                Log.i("SignalWebSocket", "Decoded Envelope from ${envelope.sourceServiceId}")
                                onMessageReceived?.invoke(envelope)
                            } catch (e: Exception) {
                                Log.e("SignalWebSocket", "Failed to parse Envelope", e)
                            }
                        }
                    } else if (message.type == com.simonproyt.legacysignal.api.websocket.WebSocketProtos.WebSocketMessage.Type.RESPONSE) {
                        Log.d("SignalWebSocket", "Received RESPONSE: status ${message.response.status}")
                    }
                } catch (e: Exception) {
                    Log.e("SignalWebSocket", "Failed to parse WebSocketMessage", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("SignalWebSocket", "Closing: $code / $reason")
                onStateChanged?.invoke(ConnectionState.DISCONNECTED)
                webSocket.close(1000, null)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("SignalWebSocket", "Closed: $code / $reason")
                onStateChanged?.invoke(ConnectionState.DISCONNECTED)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("SignalWebSocket", "Error: ${t.message}", t)
                onStateChanged?.invoke(ConnectionState.DISCONNECTED)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (manualDisconnect) return
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = Runnable {
            if (!manualDisconnect) {
                Log.d("SignalWebSocket", "Attempting automatic reconnect...")
                connect()
            }
        }
        handler.postDelayed(reconnectRunnable!!, 3000)
    }

    fun disconnect() {
        manualDisconnect = true
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        try {
            webSocket?.close(1000, "User disconnected")
        } catch (e: Exception) {
            // Ignore
        }
        webSocket = null
        onStateChanged?.invoke(ConnectionState.DISCONNECTED)
    }
}
