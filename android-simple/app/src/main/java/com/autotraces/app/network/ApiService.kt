package com.autotraces.app.network

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Body

/**
 * 云端接口定义
 */
interface ApiService {
    /**
     * 拉取目标用户名
     */
    @GET("/api/pull_username")
    suspend fun pullUsername(): Response<UsernameResponse>

    /**
     * 上报执行结果/异常
     */
    @POST("/api/report_result")
    suspend fun reportResult(@Body request: ReportRequest): Response<BaseResponse>
}

// 数据模型类 - 用户名响应
data class UsernameResponse(
    val code: Int,
    val msg: String,
    val usernames: List<String>
)

// 数据模型类 - 基础响应
data class BaseResponse(
    val code: Int,
    val msg: String
)

// 数据模型类 - 上报请求（与Executor中一致）
data class ReportRequest(
    val deviceId: String,
    val alias: String,
    val result: String
)