package com.duytv.app.util

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Trung tâm dùng chung 1 ConnectionPool & Dispatcher duy nhất cho toàn bộ ứng dụng DuyTV.
 * Triết lý Karpathy Anti-Bloat cho TV 1.5GB RAM (Sharp Aquos Android 8.0):
 * - Thay vì tạo 9 OkHttpClient độc lập (9 connection pool + hàng chục thread dư thừa),
 *   mọi module chỉ gọi `SharedHttpClients.newClientBuilder()` để tái sử dụng kết nối & tiết kiệm RAM.
 */
object SharedHttpClients {
    private val sharedConnectionPool = ConnectionPool(6, 30, TimeUnit.SECONDS)

    private val sharedDispatcher = Dispatcher().apply {
        maxRequests = 32
        maxRequestsPerHost = 6
    }

    val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .dispatcher(sharedDispatcher)
            .dns(HlsAdBlockEngine.ipv4PreferredDns)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun newClientBuilder(): OkHttpClient.Builder = baseClient.newBuilder()
}
