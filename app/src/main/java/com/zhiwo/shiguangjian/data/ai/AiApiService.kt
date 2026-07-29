package com.zhiwo.shiguangjian.data.ai

import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface AiApiService {
    @POST("chat/completions")
    suspend fun chat(@Body body: JsonObject): Response<JsonObject>
}
