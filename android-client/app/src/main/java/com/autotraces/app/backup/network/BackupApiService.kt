package com.autotraces.app.backup.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 备份结果上报接口。
 *
 * 这是一个独立的 Retrofit service，避免和你现有的通用业务 ApiService 混在一起。
 */
interface BackupApiService {
    @POST("/api/backup/report")
    suspend fun reportBackupResult(
        @Body request: UploadBackupResultRequest,
    ): Response<UploadBackupResultResponse>

    /**
     * 云端任务状态轮询。
     *
     * 由服务端返回：
     * - pending / running / success / failed
     * - detail 方便页面展示
     */
    @POST("/api/backup/task/status")
    suspend fun queryCloudTaskStatus(
        @Body request: CloudTaskStatusRequest,
    ): Response<CloudTaskStatusResponse>
}
