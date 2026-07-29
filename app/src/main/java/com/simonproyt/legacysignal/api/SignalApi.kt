package com.simonproyt.legacysignal.api

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Header
import retrofit2.http.Path

data class RegistrationData(
    val sessionId: String?,
    val signalingKey: String,
    val supportsSms: Boolean,
    val fetchesMessages: Boolean,
    val registrationId: Int,
    val name: String
)

data class SessionCreateRequest(val number: String)
data class SessionCreateResponse(val id: String)
data class CodeRequest(val transport: String = "sms", val client: String = "android")

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
    ): Call<Void>

    @PUT("/v1/accounts/attributes")
    fun updateAccountAttributes(
        @Header("Authorization") authHeader: String,
        @Body attributes: RegistrationData
    ): Call<Void>

    @GET("/v1/devices")
    fun getDevices(
        @Header("Authorization") authHeader: String
    ): Call<Map<String, Any>>
}
