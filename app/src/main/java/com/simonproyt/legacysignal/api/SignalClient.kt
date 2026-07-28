package com.simonproyt.legacysignal.api

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class SignalClient(private val context: Context, private val phoneNumber: String, private val password: String) {

    private val authHeader: String
        get() = okhttp3.Credentials.basic(phoneNumber, password)

    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val builder = OkHttpClient.Builder()
            .addInterceptor(logging)
        ConscryptHelper.configureOkHttp(context, builder)
        builder.build()
    }

    val api: SignalApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://chat.signal.org")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(SignalApi::class.java)
    }

    val webSocket: SignalWebSocket by lazy {
        SignalWebSocket(
            client = okHttpClient,
            url = "wss://chat.signal.org/v1/websocket/",
            authHeader = authHeader
        )
    }

    fun connect() {
        webSocket.connect()
    }

    fun disconnect() {
        webSocket.disconnect()
    }
}
