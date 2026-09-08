package com.example.xiangsugame.api

import android.content.Context
import com.example.xiangsugame.api.dto.ApiError
import com.example.xiangsugame.auth.AuthManager
import com.example.xiangsugame.data.LocalSettings
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Retrofit / OkHttp 单点:统一 baseUrl、超时、Bearer 认证注入、错误解析。
 *
 * - Authorization 头在每个请求发出时读取 AuthManager 当前 token(动态,登出即失效);
 * - LocalSettings.serverUrl 变化后 api() 自动重建(retrofit 实例按版本号缓存)。
 * - [safe] 把 Retrofit 挂起调用的异常统一映射成 Result:网络不可用 / 服务端错误文案。
 */
object ApiClient {

    private var appContext: Context? = null
    private var baseUrlRevision = -1L
    private var cachedApi: GameApi? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 纯展示用:当前服务器主机名(去掉协议与路径),如 192.168.1.5:8000。 */
    fun serverHost(): String {
        val raw = LocalSettings.serverUrl
            .removePrefix("https://").removePrefix("http://").trimEnd('/')
        return raw.ifEmpty { LocalSettings.DEFAULT_SERVER_URL }
    }

    private class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val token = AuthManager.session?.token
            val request = if (token == null) {
                chain.request()
            } else {
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            }
            return chain.proceed(request)
        }
    }

    @Synchronized
    fun api(): GameApi {
        val revision = LocalSettings.serverUrl.hashCode().toLong()
        if (cachedApi != null && revision == baseUrlRevision) return cachedApi!!
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor())
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(LocalSettings.serverUrl)
            .client(client)
            .addConverterFactory(SharedJson.asConverterFactory("application/json".toMediaType()))
            .build()
        cachedApi = retrofit.create(GameApi::class.java)
        baseUrlRevision = revision
        return cachedApi!!
    }

    /**
     * 统一错误包装:Result.failure 的 message 已适合直接展示。
     * IOException → "无法连接服务器,请检查网络或服务器地址";
     * HttpException → 解析服务端 {"error": ...} / {"detail": ...} 文案;
     * 401 → "登录已过期"。
     */
    suspend fun <T> safe(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: HttpException) {
        val message = runCatching {
            val body = e.response()?.errorBody()?.string()
            if (body.isNullOrBlank()) null else SharedJson.decodeFromString<ApiError>(body).message
        }.getOrNull() ?: when (e.code()) {
            401 -> "登录已过期,请重新登录"
            404 -> "请求的资源不存在"
            408, 502, 503, 504 -> "服务器暂时不可用,请稍后重试"
            else -> "请求失败(${e.code()})"
        }
        Result.failure(if (e.code() == 401) UnauthorizedException(message) else ApiException(message))
    } catch (e: IOException) {
        Result.failure(ApiException("无法连接服务器,请检查网络或设置页的服务器地址"))
    } catch (e: Exception) {
        Result.failure(ApiException(e.message ?: "请求失败"))
    }

    /** 401:导航层可借此强制回登录页。 */
    class UnauthorizedException(message: String) : ApiException(message)
    open class ApiException(message: String) : Exception(message)
}
