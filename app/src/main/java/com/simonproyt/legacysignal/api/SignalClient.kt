package com.simonproyt.legacysignal.api

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class SignalClient(private val context: Context, private val phoneNumber: String, private val password: String) {

    private val authHeader: String
        get() = okhttp3.Credentials.basic(phoneNumber, password)

    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val userAgentInterceptor = okhttp3.Interceptor { chain ->
            val originalRequest = chain.request()
            val requestWithUserAgent = originalRequest.newBuilder()
                .header("User-Agent", "Signal-Android/8.20.1 Android/29")
                .build()
            chain.proceed(requestWithUserAgent)
        }
        val builder = OkHttpClient.Builder()
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(logging)
        ConscryptHelper.configureOkHttp(context, builder)
        builder.build()
    }

    val api: SignalApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://chat.signal.org/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
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
