package com.autotraces.app.backup.network

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 备份模块专用 Retrofit 客户端。
 *
 * 说明：
 * - 这里先写成独立实例，便于你后续按模块拆分调试
 * - baseUrl 请替换成你自己的服务端地址
 */
object BackupRetrofitClient {

    private val gson = GsonBuilder().setLenient().create()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    val service: BackupApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://your-server.example.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(BackupApiService::class.java)
    }
}
