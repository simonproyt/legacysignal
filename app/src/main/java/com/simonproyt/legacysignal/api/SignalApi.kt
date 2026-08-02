package com.simonproyt.legacysignal.api

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Header
import retrofit2.http.Path

data class AccountAttributes(
    val fetchesMessages: Boolean,
    val registrationId: Int,
    val name: String,
    val capabilities: List<String>
)

data class ECSignedPreKey(
    val keyId: Int,
    val publicKey: String,
    val signature: String
)

data class KEMSignedPreKey(
    val keyId: Int,
    val publicKey: String,
    val signature: String
)

data class RegistrationRequest(
    val sessionId: String,
    val skipDeviceTransfer: Boolean,
    val aciIdentityKey: String,
    val pniIdentityKey: String,
    val accountAttributes: AccountAttributes,
    val aciSignedPreKey: ECSignedPreKey,
    val pniSignedPreKey: ECSignedPreKey,
    val aciPqLastResortPreKey: KEMSignedPreKey,
    val pniPqLastResortPreKey: KEMSignedPreKey
)

data class SessionCreateRequest(val number: String)
data class SessionCreateResponse(val id: String)
data class CodeRequest(val transport: String = "sms", val client: String = "android")

data class VerifyResponse(val verified: Boolean)

interface SignalApi {
    
    @POST("/v1/verification/session")
    fun createSession(
        @Body request: SessionCreateRequest
    ): Call<SessionCreateResponse>

    @retrofit2.http.PATCH("/v1/verification/session/{sessionId}")
    fun patchSession(
        @Path("sessionId") sessionId: String,
        @Body request: Map<String, String> // {"captcha": token}
    ): Call<SessionCreateResponse>

    @POST("/v1/verification/session/{sessionId}/code")
    fun requestSmsCode(
        @Path("sessionId") sessionId: String,
        @Body request: CodeRequest
    ): Call<Void>

    @PUT("/v1/verification/session/{sessionId}/code")
    fun verifySmsCode(
        @Path("sessionId") sessionId: String,
        @Body request: Map<String, String> // {"verificationCode": code}
    ): Call<VerifyResponse>

    @POST("/v1/registration")
    fun registerAccount(
        @Header("Authorization") authHeader: String,
        @Body request: RegistrationRequest
    ): Call<Void>

    @GET("/v1/devices")
    fun getDevices(
        @Header("Authorization") authHeader: String
    ): Call<Map<String, Any>>
}
