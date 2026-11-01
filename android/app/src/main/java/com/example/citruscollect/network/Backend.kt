package com.example.citruscollect.network

import com.example.citruscollect.util.UuidV7
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.util.Collections
import com.example.citruscollect.BuildConfig
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

// ---------------- 认证 / 配置 DTO（字段与服务端一致） ----------------

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class PhoneCodeRequest(val phone: String)

@Serializable
data class PhoneCodeResponse(val challenge_id: String, val resend_after_seconds: Int, val expires_in: Int)

@Serializable
data class PhoneLoginRequest(val phone: String, val challenge_id: String, val code: String)


@Serializable
data class AuthUser(
    val user_id: String,
    val username: String,
    val display_name: String,
    val role: String
)

@Serializable
data class LoginResponse(
    val access_token: String,
    val refresh_token: String,
    val token_type: String = "Bearer",
    val expires_in: Int,
    val user: AuthUser
)

@Serializable
data class RefreshRequest(val refresh_token: String)

@Serializable
data class RefreshResponse(
    val access_token: String,
    val refresh_token: String,
    val token_type: String = "Bearer",
    val expires_in: Int
)

@Serializable
data class MeResponse(
    val user_id: String,
    val username: String,
    val display_name: String,
    val role: String
)

@Serializable
data class ProfileResponse(
    val user_id: String,
    val username: String,
    val display_name: String,
    val role: String,
    val phone_masked: String? = null,
    val phone_verified: Boolean = false
)

@Serializable
data class ProfilePatch(val display_name: String)

@Serializable
data class Cultivar(
    val cultivar_id: String,
    val name: String,
    val is_active: Boolean,
    val version: Int
)

@Serializable
data class ClassEntry(
    val class_id: String,
    val name: String,
    val parent_id: String? = null,
    val is_active: Boolean,
    val is_provisional: Boolean,
    val introduced_taxonomy_version: Int,
    val deprecated_taxonomy_version: Int? = null,
    val version: Int
)

@Serializable
data class BootstrapResponse(
    val cultivars: List<Cultivar>,
    val classes: List<ClassEntry>,
    val taxonomy_version: Int,
    val policy_version: Int,
    val server_time: String
)

// ---------------- 上传协议 DTO（snake_case，与服务端一致） ----------------

@Serializable
data class ImageDeclaration(
    val image_id: String,
    val view_type: String,
    val captured_at: String,
    val device_timezone: String,
    val device_model: String,
    val mime_type: String,
    val width: Int,
    val height: Int,
    val file_size: Long,
    val sha256: String
)

@Serializable
data class UploadDeclaration(
    val sample_id: String,
    val schema_version: Int,
    val tree_id: String? = null,
    val cultivar_id: String,
    val part: String,
    val class_id: String,
    val symptoms: String? = null,
    val note: String? = null,
    val images: List<ImageDeclaration>,
    val batch_id: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val location_accuracy_m: Double? = null
)

@Serializable
data class UploadStatus(
    val upload_id: String,
    val sample_id: String,
    val state: String,
    val declared_at: String,
    val received_images: List<String> = emptyList(),
    val missing_images: List<String> = emptyList(),
    val invalid_images: List<String> = emptyList()
)

@Serializable
data class UploadImageReceipt(
    val upload_id: String,
    val image_id: String,
    val receive_status: String,
    val sha256: String,
    val file_size: Long
)

@Serializable
data class UploadCompleteResponse(
    val upload_id: String,
    val sample_id: String,
    val state: String,
    val upload_completed_at: String,
    val image_count: Int
)

// ---------------- 错误信封 ----------------

@Serializable
data class ErrorEnvelope(val error: ErrorBody)

@Serializable
data class ErrorBody(
    val code: String,
    val message: String,
    val details: JsonObject? = null,
    @SerialName("request_id") val request_id: String? = null
)

// ---------------- 异常 ----------------

/** 携带服务端错误码/消息的业务异常（供 UI 明确展示）；requestId 来自错误体或响应头。 */
class ApiException(
    val status: Int,
    val code: String?,
    message: String,
    val requestId: String? = null
) : Exception(message)

/** 会话过期且刷新失败：应回到登录。 */
class AuthExpiredException(message: String) : Exception(message)

object ApiErrors {
    // HttpException 的 errorBody 只能读取一次；按异常对象弱缓存解析结果，
    // 同一异常多次检查（isExpired 之后再 parse/messageOf）不丢失服务端
    // message/code/details。键为弱引用，异常释放后条目随之回收，不会永久积累。
    private val parsedBodies: MutableMap<HttpException, ErrorBody?> =
        Collections.synchronizedMap(WeakHashMap())

    fun parse(e: HttpException): ErrorBody? = synchronized(parsedBodies) {
        if (parsedBodies.containsKey(e)) return@synchronized parsedBodies[e]
        val raw = try {
            e.response()?.errorBody()?.string()
        } catch (_: Exception) {
            null
        }
        val body = raw?.let {
            try {
                CitrusJson.json.decodeFromString<ErrorEnvelope>(it).error
            } catch (_: Exception) {
                null
            }
        }
        // request_id 优先取错误体；缺失时回退响应头 X-Request-Id。
        val headerId = try {
            e.response()?.raw()?.headers?.get("X-Request-Id")
        } catch (_: Exception) {
            null
        }
        val enriched = if (body != null && body.request_id == null && !headerId.isNullOrBlank()) {
            body.copy(request_id = headerId)
        } else {
            body
        }
        parsedBodies[e] = enriched
        enriched
    }

    fun messageOf(e: HttpException): String {
        val body = parse(e)
        if (e.code() == 413 && (body?.message.isNullOrBlank() || body?.message == "Request body exceeds gateway limit")) {
            return "图片超过服务允许的大小，请缩小图片或重新拍摄"
        }
        return body?.message?.takeIf { it.isNotBlank() } ?: "请求失败（HTTP ${e.code()}）"
    }

    /** 401 + AUTH_INVALID + details.reason=expired 才触发刷新，其他 401 不伪装成过期。 */
    fun isExpired(e: HttpException): Boolean {
        if (e.code() != 401) return false
        val body = parse(e) ?: return false
        if (body.code != "AUTH_INVALID") return false
        return body.details?.get("reason")?.jsonPrimitive?.contentOrNull == "expired"
    }
}

// ---------------- Retrofit 服务 ----------------

interface AuthService {
    @GET("me/profile")
    suspend fun profile(@Header("Authorization") authorization: String): ProfileResponse

    @retrofit2.http.PATCH("me/profile")
    suspend fun updateProfile(@Header("Authorization") authorization: String, @Body body: ProfilePatch): ProfileResponse

    @POST("auth/phone/code")
    suspend fun sendPhoneCode(@Body body: PhoneCodeRequest): PhoneCodeResponse

    @POST("auth/phone/login")
    suspend fun loginPhone(@Body body: PhoneLoginRequest): LoginResponse

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): RefreshResponse

    @POST("auth/logout")
    suspend fun logout(@Header("Authorization") authorization: String): retrofit2.Response<Unit>

    @GET("me")
    suspend fun me(@Header("Authorization") authorization: String): MeResponse

    @GET("config/bootstrap")
    suspend fun bootstrap(@Header("Authorization") authorization: String): BootstrapResponse
}

interface UploadService {
    @POST("samples/{sampleId}/withdraw")
    suspend fun withdraw(@Header("Authorization") authorization: String,
                         @Path("sampleId") sampleId: String): WithdrawalResponse
    @POST("uploads")
    suspend fun declare(
        @Header("Authorization") authorization: String,
        @Body body: UploadDeclaration
    ): UploadStatus

    @GET("uploads/{uploadId}")
    suspend fun status(
        @Header("Authorization") authorization: String,
        @Path("uploadId") uploadId: String
    ): UploadStatus

    @PUT("uploads/{uploadId}/images/{imageId}")
    suspend fun uploadImage(
        @Header("Authorization") authorization: String,
        @Path("uploadId") uploadId: String,
        @Path("imageId") imageId: String,
        @Header("X-Content-SHA256") sha256: String,
        @Body body: RequestBody
    ): UploadImageReceipt

    @POST("uploads/{uploadId}/complete")
    suspend fun complete(
        @Header("Authorization") authorization: String,
        @Path("uploadId") uploadId: String
    ): UploadCompleteResponse
}

@Serializable
data class WithdrawalResponse(val sample_id: String, val state: String)

interface BootstrapService {
    @GET("config/bootstrap")
    suspend fun bootstrap(@Header("Authorization") authorization: String): BootstrapResponse
}

@Serializable
data class RecognitionCandidate(val label: String, val name: String, val score: Double, val class_id: String? = null)

@Serializable
data class RecognitionResult(val image_id: String, val sha256: String, val model: String,
    val model_version: String, val scope: String, val candidates: List<RecognitionCandidate>,
    val warning: String, val created_at: String, val accepted: Boolean = false, val accepted_index: Int? = null)

interface RecognitionService {
    @POST("recognitions")
    suspend fun recognize(@Header("Authorization") authorization: String,
        @Header("X-Image-ID") imageId: String, @Body image: RequestBody): RecognitionResult
}

// ---------------- Json 与后端工厂 ----------------

object CitrusJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun asConverterFactory(mediaType: okhttp3.MediaType) = json.asConverterFactory(mediaType)
}

/**
 * 按 baseUrl 构建 Retrofit 服务；baseUrl 可在登录时更换。
 * 服务实例按地址单槽缓存，避免每请求重复反射；地址与缓存在同一把锁下读写，
 * 切换地址后不会把旧地址的服务当作当前服务返回。
 * 地址仅接受 http/https（运行时明文限制仍由 network_security_config 决定，此处不放开）。
 */
class Backend(
    baseUrl: String = DEFAULT_BASE_URL,
    client: OkHttpClient? = null
) {
    companion object {
        // 默认连接已部署的 HTTPS 服务；登录页仍可切换服务地址。
        const val DEFAULT_BASE_URL = BuildConfig.API_BASE_URL

        // 与服务端约定一致的客户端标识；版本号跟随 app/build.gradle.kts 的 versionName。
        const val CLIENT_VERSION = "android/0.1.3"
    }

    data class Services(val auth: AuthService, val upload: UploadService, val config: BootstrapService,
        val recognition: RecognitionService, val collection: CollectionService? = null, val diagnosis: DiagnosisService? = null)

    private val lock = Any()

    @Volatile
    private var url: String = normalize(baseUrl)

    // 基础客户端：调用者传入的实例（连同其配置与拦截器）或默认超时配置；
    // 派生客户端一律通过 newBuilder 构建，保留调用者已有设置。
    private val baseClient: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // 单槽缓存（由 lock 保护）：cachedUrl 与 cachedServices 恒指向同一地址。
    private var cachedUrl: String? = null
    private var cachedServices: Services? = null

    fun baseUrl(): String = url

    fun setBaseUrl(value: String) {
        val normalized = normalize(value)
        synchronized(lock) {
            if (normalized != url) {
                url = normalized
                cachedUrl = null
                cachedServices = null
            }
        }
    }

    fun services(): Services = servicesFor(url)

    /** 指定地址的服务快照：只读取/构建该地址的服务，不改变当前全局地址。 */
    fun servicesFor(baseUrl: String): Services {
        val normalized = normalize(baseUrl)
        synchronized(lock) {
            if (normalized == cachedUrl) {
                cachedServices?.let { return it }
            }
            val built = buildServices(normalized)
            cachedUrl = normalized
            cachedServices = built
            return built
        }
    }

    // 统一请求元数据：X-Client-Version 与 X-Request-Id（UUIDv7）。
    // 只追加请求头，不读取也不记录令牌、密码或请求体。
    private val metadataInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .header("X-Client-Version", CLIENT_VERSION)
            .header("X-Request-Id", UuidV7.generate())
            .build()
        chain.proceed(request)
    }

    private fun buildServices(baseUrl: String): Services {
        val http = baseClient.newBuilder()
            // 禁止 API 客户端自动跟随重定向：携带 Authorization 的请求不得被透明转发到其他地址；
            // 关闭连接失败静默重试：认证/刷新等非幂等请求不被底层透明重放（也不新增自动重试拦截器）。
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addInterceptor(metadataInterceptor)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(http)
            .addConverterFactory(CitrusJson.asConverterFactory("application/json".toMediaType()))
            .build()
        return Services(
            auth = retrofit.create(AuthService::class.java),
            collection = retrofit.create(CollectionService::class.java),
            diagnosis = retrofit.create(DiagnosisService::class.java),
            upload = retrofit.create(UploadService::class.java),
            recognition = retrofit.create(RecognitionService::class.java),
            // Only this read-only service may replay a failed connection. Auth and uploads keep their original policy.
            config = Retrofit.Builder().baseUrl(baseUrl)
                .client(http.newBuilder().retryOnConnectionFailure(true).build())
                .addConverterFactory(CitrusJson.asConverterFactory("application/json".toMediaType()))
                .build().create(BootstrapService::class.java)
        )
    }

    /**
     * 校验并规范化服务地址：拒绝空/非法地址、用户名密码、查询参数与锚点；
     * 保留显式 API 路径与根路径（不强制 /api/v1），仅补齐结尾斜杠（Retrofit baseUrl 要求）。
     */
    private fun normalize(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("服务地址不能为空")
        val parsed = trimmed.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("服务地址不合法：$trimmed（应为 http(s)://主机[:端口][/路径]）")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw IllegalArgumentException("服务地址不能包含用户名密码：$trimmed")
        }
        if (parsed.query != null) throw IllegalArgumentException("服务地址不能包含查询参数：$trimmed")
        if (parsed.fragment != null) throw IllegalArgumentException("服务地址不能包含锚点：$trimmed")
        val normalized = parsed.toString()
        return if (normalized.endsWith("/")) normalized else "$normalized/"
    }
}
