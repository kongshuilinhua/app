package com.example.citruscollect.network

import com.example.citruscollect.util.UuidV7
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import java.util.concurrent.TimeUnit

/** Backend 网络基础层：地址验证、并发一致的服务缓存、请求元数据与错误解析。 */
class BackendTest {
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        other = MockWebServer()
        other.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        other.shutdown()
    }

    private fun json(code: Int, body: String, headers: Map<String, String> = emptyMap()) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .apply { headers.forEach { (k, v) -> setHeader(k, v) } }
        .setBody(body)

    private fun loginOk() = json(
        200,
        """{"access_token":"a","refresh_token":"r","token_type":"Bearer","expires_in":1800,
           "user":{"user_id":"019a0000-0000-7000-8000-000000000003","username":"c1",
           "display_name":"采集员","role":"collector"}}"""
    )

    private fun meOk() = json(
        200,
        """{"user_id":"019a0000-0000-7000-8000-000000000003","username":"c1",
           "display_name":"采集员","role":"collector"}"""
    )

    private fun dispatcher(response: (RecordedRequest) -> MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = response(request)
    }

    private fun expectRejected(url: String) {
        var caught = false
        try {
            Backend(url)
        } catch (_: IllegalArgumentException) {
            caught = true
        }
        assertTrue("应拒绝非法服务地址：$url", caught)
    }

    // ---- 服务地址验证 ----

    @Test
    fun rejectsInvalidBaseUrls() {
        expectRejected("")
        expectRejected("   ")
        expectRejected("not-a-url")
        expectRejected("http://")
        expectRejected("ftp://example.com/api/v1")
        expectRejected("http://user:pass@example.com/api/v1/")
        expectRejected("http://example.com/api/v1?debug=1")
        expectRejected("http://example.com/api/v1#section")
    }

    @Test
    fun keepsExplicitPathRootAndDefaultWithoutForcingApiPrefix() {
        assertEquals("http://example.com:18000/api/v1/", Backend("http://example.com:18000/api/v1").baseUrl())
        assertEquals("http://example.com:18000/api/v1/", Backend("  http://example.com:18000/api/v1/ ").baseUrl())
        assertEquals("http://example.com:18000/", Backend("http://example.com:18000/").baseUrl())
        assertEquals("http://example.com:18000/", Backend("http://example.com:18000").baseUrl())
        assertEquals(Backend.DEFAULT_BASE_URL, Backend().baseUrl())
    }

    // ---- 地址切换与并发一致的服务缓存 ----

    @Test
    fun switchingBaseUrlServesMatchingBackendAndSnapshotKeepsGlobal() = runBlocking {
        server.dispatcher = dispatcher { loginOk() }
        other.dispatcher = dispatcher { loginOk() }
        val backend = Backend(server.url("/").toString())

        val s1 = backend.services()
        assertTrue(s1 === backend.services()) // 同地址命中缓存

        backend.services().auth.login(LoginRequest("c1", "12345678"))
        assertEquals(1, server.requestCount)

        backend.setBaseUrl(other.url("/").toString())
        backend.services().auth.login(LoginRequest("c1", "12345678"))
        assertEquals(1, other.requestCount) // 切换后请求落到新地址
        assertEquals(other.url("/").toString(), backend.baseUrl())

        // servicesFor：取指定地址快照，不改变当前全局地址
        backend.servicesFor(server.url("/").toString()).auth.login(LoginRequest("c1", "12345678"))
        assertEquals(2, server.requestCount)
        assertEquals(1, other.requestCount)
        assertEquals(other.url("/").toString(), backend.baseUrl())
    }

    // ---- 请求元数据与调用者配置保留 ----

    @Test
    fun addsClientMetadataHeadersPerRequest() = runBlocking {
        server.dispatcher = dispatcher { loginOk() }
        val backend = Backend(server.url("/").toString())
        backend.services().auth.login(LoginRequest("c1", "12345678"))
        backend.services().auth.login(LoginRequest("c1", "12345678"))
        val first = server.takeRequest(2, TimeUnit.SECONDS)!!
        val second = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(Backend.CLIENT_VERSION, first.getHeader("X-Client-Version"))
        assertTrue(UuidV7.isValid(first.getHeader("X-Request-Id")!!))
        assertTrue(UuidV7.isValid(second.getHeader("X-Request-Id")!!))
        assertTrue(first.getHeader("X-Request-Id") != second.getHeader("X-Request-Id")) // 每请求独立标识
    }

    @Test
    fun derivesFromCallerClientAndKeepsItsInterceptors() = runBlocking {
        server.dispatcher = dispatcher { loginOk() }
        val caller = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Test-Custom", "yes").build())
            }
            .build()
        val backend = Backend(server.url("/").toString(), caller)
        backend.services().auth.login(LoginRequest("c1", "12345678"))
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("yes", recorded.getHeader("X-Test-Custom")) // 调用者拦截器保留
        assertEquals(Backend.CLIENT_VERSION, recorded.getHeader("X-Client-Version"))
    }

    // ---- 重定向不泄露认证信息 ----

    @Test
    fun redirectIsNotFollowedAndCredentialsNotForwarded() = runBlocking {
        server.dispatcher = dispatcher {
            MockResponse().setResponseCode(307).setHeader("Location", other.url("/me"))
        }
        other.dispatcher = dispatcher { meOk() }
        val backend = Backend(server.url("/").toString())
        var code = 0
        try {
            backend.services().auth.me("Bearer secret-token")
        } catch (e: HttpException) {
            code = e.code()
        }
        assertEquals(307, code) // 3xx 原样交给调用方，不透明跟随
        assertEquals(1, server.requestCount)
        assertEquals(0, other.requestCount) // 认证凭据未转发到重定向目标
        assertEquals("Bearer secret-token", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    // ---- 错误解析 ----

    @Test
    fun repeatedErrorParsingKeepsMessageAndRequestId() = runBlocking {
        server.dispatcher = dispatcher {
            json(
                401,
                """{"error":{"code":"AUTH_INVALID","message":"登录已过期",
                   "details":{"reason":"expired"},"request_id":"019a0000-0000-7000-8000-0000000000aa"}}"""
            )
        }
        val backend = Backend(server.url("/").toString())
        var e: HttpException? = null
        try {
            backend.services().auth.me("Bearer t")
        } catch (ex: HttpException) {
            e = ex
        }
        assertTrue(e != null)
        val first = ApiErrors.parse(e!!)
        assertEquals("登录已过期", first?.message)
        assertEquals("AUTH_INVALID", first?.code)
        assertEquals("019a0000-0000-7000-8000-0000000000aa", first?.request_id)
        // errorBody 只能读一次：后续检查（isExpired 内部也解析）不得丢失 message/request_id
        assertTrue(ApiErrors.isExpired(e!!))
        assertEquals("登录已过期", ApiErrors.messageOf(e!!))
        assertEquals(first, ApiErrors.parse(e!!))
    }

    @Test
    fun requestIdFallsBackToHeaderAndGatewayErrorsStayUnderstandable() = runBlocking {
        // 错误体无 request_id：回退响应头 X-Request-Id
        server.dispatcher = dispatcher {
            json(
                401,
                """{"error":{"code":"AUTH_INVALID","message":"令牌无效","details":{"reason":"invalid"}}}""",
                headers = mapOf("X-Request-Id" to "019a0000-0000-7000-8000-0000000000bb")
            )
        }
        val backend = Backend(server.url("/").toString())
        var e: HttpException? = null
        try {
            backend.services().auth.me("Bearer t")
        } catch (ex: HttpException) {
            e = ex
        }
        assertEquals("019a0000-0000-7000-8000-0000000000bb", ApiErrors.parse(e!!)?.request_id)

        // 非 JSON 网关错误：不把 HTML 当消息，使用可理解的 HTTP 状态回退
        other.dispatcher = dispatcher {
            MockResponse().setResponseCode(502).setHeader("Content-Type", "text/html").setBody("<html>Bad Gateway</html>")
        }
        val backend2 = Backend(other.url("/").toString())
        var gateway: HttpException? = null
        try {
            backend2.services().auth.me("Bearer t")
        } catch (ex: HttpException) {
            gateway = ex
        }
        assertTrue(gateway != null)
        assertNull(ApiErrors.parse(gateway!!))
        assertEquals("请求失败（HTTP 502）", ApiErrors.messageOf(gateway!!))
    }

    @Test
    fun apiExceptionKeepsOldCallCompatibility() {
        val legacy = ApiException(401, "AUTH_INVALID", "消息")
        assertNull(legacy.requestId)
        assertEquals("AUTH_INVALID", legacy.code)
        val withId = ApiException(401, "AUTH_INVALID", "消息", "019a0000-0000-7000-8000-0000000000cc")
        assertEquals("019a0000-0000-7000-8000-0000000000cc", withId.requestId)
    }

    @Test fun oversizedImagesHaveReadableMessageWithoutHidingPixelLimit() = runBlocking {
        for ((message, expected) in listOf(
            "Request body exceeds gateway limit" to "图片超过服务允许的大小，请缩小图片或重新拍摄",
            "图片像素过大，请使用不超过2400万像素的图片" to "图片像素过大，请使用不超过2400万像素的图片"
        )) {
            server.enqueue(json(413, """{"error":{"code":"PAYLOAD_TOO_LARGE","message":"$message"}}"""))
            var caught: HttpException? = null
            try { Backend(server.url("/").toString()).services().auth.me("Bearer t") }
            catch (e: HttpException) { caught = e }
            assertTrue(caught != null)
            assertEquals(expected, ApiErrors.messageOf(caught!!))
        }
    }
}
