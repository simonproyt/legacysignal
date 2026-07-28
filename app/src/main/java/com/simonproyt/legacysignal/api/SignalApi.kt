package com.simonproyt.legacysignal.api

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Header

interface SignalApi {
    
    @PUT("/v1/accounts/attributes")
    fun updateAccountAttributes(
        @Header("Authorization") authHeader: String,
        @Body attributes: Map<String, String>
    ): Call<Void>

    @GET("/v1/devices")
    fun getDevices(
        @Header("Authorization") authHeader: String
    ): Call<Map<String, Any>>
}
