package com.autotraces.app.network

import com.autotraces.app.util.Config
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit网络请求客户端
 */
object RetrofitClient {
    private val gson: Gson = GsonBuilder().setLenient().create()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Config.NETWORK_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(Config.NETWORK_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(Config.NETWORK_TIMEOUT, TimeUnit.SECONDS)
        .build()

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(Config.SERVER_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    val instance: Retrofit get() = retrofit
}