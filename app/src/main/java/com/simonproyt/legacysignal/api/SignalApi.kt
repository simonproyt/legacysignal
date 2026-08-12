package com.simonproyt.legacysignal.api

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

data class Capabilities(
    val storage: Boolean,
    val versionedExpirationTimer: Boolean,
    val attachmentBackfill: Boolean,
    val spqr: Boolean,
    val usernameChangeSyncMessage: Boolean
)

data class AccountAttributes(
    val voice: Boolean,
    val video: Boolean,
    val fetchesMessages: Boolean,
    val registrationId: Int,
    val pniRegistrationId: Int,
    val name: String?,
    val capabilities: Capabilities,
    val unidentifiedAccessKey: String?,
    val unrestrictedUnidentifiedAccess: Boolean,
    val discoverableByPhoneNumber: Boolean
)

data class SignalServiceProfileWrite(
    val version: String,
    val name: String,
    val about: String,
    val aboutEmoji: String,
    val paymentAddress: String?,
    val phoneNumberSharing: String,
    val avatar: Boolean,
    val sameAvatar: Boolean,
    val commitment: String,
    val badgeIds: List<String>
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

data class PreKey(
    val keyId: Int,
    val publicKey: String
)

data class PreKeyResponseItem(
    val deviceId: Int,
    val registrationId: Int,
    val signedPreKey: ECSignedPreKey?,
    val preKey: PreKey?,
    val pqPreKey: KEMSignedPreKey? // named pqPreKey in JSON
)

data class PreKeyResponse(
    val identityKey: String,
    val devices: List<PreKeyResponseItem>
)

data class OutgoingPushMessage(
    val type: Int,
    val destinationDeviceId: Int,
    val destinationRegistrationId: Int,
    val content: String
)

data class OutgoingPushMessageList(
    val destination: String,
    val timestamp: Long,
    val messages: List<OutgoingPushMessage>,
    val online: Boolean,
    val urgent: Boolean
)

data class PreKeyUploadRequest(
    val signedPreKey: ECSignedPreKey,
    val preKeys: List<PreKey>,
    val pqLastResortPreKey: KEMSignedPreKey? = null
)

data class SessionCreateRequest(val number: String)
data class SessionCreateResponse(val id: String)
data class CodeRequest(val transport: String = "sms", val client: String = "android")

data class VerifyResponse(val verified: Boolean)

data class AccountCreationResponse(
    val uuid: String,
    val number: String,
    val pni: String
)

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
    ): Call<AccountCreationResponse>

    @GET("/v1/devices")
    fun getDevices(
        @Header("Authorization") authHeader: String
    ): Call<Map<String, Any>>

    @PUT("/v2/keys")
    fun uploadPreKeys(
        @Header("Authorization") auth: String,
        @Query("identity") identity: String, // "aci" or "pni"
        @Body request: PreKeyUploadRequest
    ): Call<Void>
    
    @PUT("/v1/profile")
    fun uploadProfile(
        @Header("Authorization") auth: String,
        @Body profile: SignalServiceProfileWrite
    ): Call<Void>

    @GET("/v2/keys/{identifier}/*")
    fun getPreKeys(
        @Header("Authorization") auth: String,
        @Path("identifier") identifier: String
    ): Call<PreKeyResponse>

    @PUT("/v1/messages/{destination}")
    fun sendMessage(
        @Header("Authorization") auth: String,
        @Path("destination") destination: String,
        @Query("story") story: Boolean = false,
        @Body request: OutgoingPushMessageList
    ): Call<Void>
}
