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
    private val authHeader: String,
    private val onMessageReceived: ((com.simonproyt.legacysignal.api.push.SignalServiceProtos.Envelope) -> Unit)? = null
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
