package com.example.citruscollect.data

import com.example.citruscollect.network.ApiErrors
import com.example.citruscollect.network.ApiException
import com.example.citruscollect.network.Backend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FakeSessionStore : SessionStore {
    private val bootstrapCache = mutableMapOf<String, String>()
    private val profileCache = mutableMapOf<String, String>()
    override suspend fun loadProfileCache(key: String): String? = profileCache[key]
    override suspend fun saveProfileCache(key: String, value: String) { profileCache[key] = value }

    override suspend fun loadBootstrapCache(key: String): String? = bootstrapCache[key]
    override suspend fun saveBootstrapCache(key: String, value: String) { bootstrapCache[key] = value }
    private val _token = MutableStateFlow<String?>(null)
    private val _username = MutableStateFlow<String?>(null)
    private val _role = MutableStateFlow<String?>(null)
    private val _baseUrl = MutableStateFlow<String?>(null)

    override val username: Flow<String?> = _username
    override val token: Flow<String?> = _token
    override val role: Flow<String?> = _role
    override val baseUrl: Flow<String?> = _baseUrl

    var access: String? = null
    var refresh: String? = null

    override suspend fun accessToken(): String? = access
    override suspend fun refreshToken(): String? = refresh
    override suspend fun baseUrlValue(): String? = _baseUrl.value

    override suspend fun saveLogin(
        baseUrl: String,
        username: String,
        role: String,
        accessToken: String,
        refreshToken: String,
        expiresIn: Int
    ) {
        _baseUrl.value = baseUrl
        _username.value = username
        _role.value = role
        access = accessToken
        refresh = refreshToken
        _token.value = accessToken
    }

    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Int) {
        access = accessToken
        refresh = refreshToken
        _token.value = accessToken
    }

    override suspend fun clear() {
        bootstrapCache.clear()
        profileCache.clear()
        access = null
        refresh = null
        _token.value = null
    }
}

class AuthRepositoryTest {
    private fun bootstrapJson(version: Int = 1) = """{"cultivars":[{"cultivar_id":"unknown","name":"不确定","is_active":true,"version":1}],"classes":[],"taxonomy_version":$version,"policy_version":1,"server_time":"2026-10-25T12:00:00Z"}"""

    @Test fun bootstrapCachesPersistsAndUsesCachedDataOnTransientFailure() = runBlocking {
        server.enqueue(json(200, loginResponse("collector")))
        repository.login(server.url("/").toString(), "c1", "password")
        server.enqueue(json(200, bootstrapJson()))
        assertEquals(1, repository.bootstrap().taxonomy_version)
        assertEquals(1, repository.bootstrap().taxonomy_version)
        val restored = AuthRepository(Backend(), session)
        assertEquals(1, restored.bootstrap().taxonomy_version)
        assertEquals(2, server.requestCount)
        server.enqueue(json(503, "{}"))
        assertEquals(1, restored.bootstrap(forceRefresh = true).taxonomy_version)
        server.enqueue(json(200, bootstrapJson(2)))
        assertEquals(2, restored.bootstrap(forceRefresh = true).taxonomy_version)
        assertEquals(2, restored.bootstrap().taxonomy_version)
        assertEquals(4, server.requestCount)
    }

    @Test fun bootstrapCacheCannotCrossServerAndDoesNotHideAuthRejection() = runBlocking {
        server.enqueue(json(200, loginResponse("collector")))
        repository.login(server.url("/").toString(), "c1", "password")
        server.enqueue(json(200, bootstrapJson()))
        assertEquals(1, repository.bootstrap().taxonomy_version)
        other.enqueue(json(200, loginResponse("collector")))
        repository.login(other.url("/").toString(), "c1", "password")
        other.enqueue(json(200, bootstrapJson(9)))
        assertEquals(9, repository.bootstrap().taxonomy_version)
        other.enqueue(json(401, """{"error":{"code":"AUTH_INVALID","message":"revoked","details":{"reason":"revoked"}}}"""))
        var rejected = false
        try { repository.bootstrap(forceRefresh = true) } catch (_: HttpException) { rejected = true }
        assertTrue(rejected)
        assertNull(session.access)
    }

    @Test fun bootstrapSafelyRetriesDroppedReadConnection() = runBlocking {
        server.enqueue(json(200, loginResponse("collector")))
        repository.login(server.url("/").toString(), "c1", "password")
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(json(200, bootstrapJson(3)))
        assertEquals(3, repository.bootstrap().taxonomy_version)
        assertEquals(3, server.requestCount)
    }
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer
    private lateinit var session: FakeSessionStore
    private lateinit var backend: Backend
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        other = MockWebServer()
        other.start()
        session = FakeSessionStore()
        backend = Backend(server.url("/").toString())
        repository = AuthRepository(backend, session)
    }

    @After
    fun tearDown() {
        server.shutdown()
        other.shutdown()
    }

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun dispatcher(response: (RecordedRequest) -> MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = response(request)
    }

    private fun loginResponse(role: String) =
        """{"access_token":"acc","refresh_token":"ref","token_type":"Bearer","expires_in":1800,
           "user":{"user_id":"019a0000-0000-7000-8000-000000000003","username":"c1",
           "display_name":"采集员","role":"$role"}}"""

    private fun loginOk(access: String, refresh: String) = json(
        200,
        """{"access_token":"$access","refresh_token":"$refresh","token_type":"Bearer","expires_in":1800,
           "user":{"user_id":"019a0000-0000-7000-8000-000000000004","username":"c2",
           "display_name":"采集员2","role":"collector"}}"""
    )

    private fun refreshOk(access: String, refresh: String) =
        json(200, """{"access_token":"$access","refresh_token":"$refresh","token_type":"Bearer","expires_in":1800}""")

    private fun meOk() = json(
        200,
        """{"user_id":"019a0000-0000-7000-8000-000000000003","username":"c1",
           "display_name":"采集员","role":"collector"}"""
    )

    private fun expired401() = json(
        401,
        """{"error":{"code":"AUTH_INVALID","message":"登录已过期",
           "details":{"reason":"expired"},"request_id":"019a0000-0000-7000-8000-0000000000aa"}}"""
    )

    private suspend fun seedSession() {
        session.saveLogin(server.url("/").toString(), "c1", "collector", "acc-old", "ref-old", 1800)
    }

    @Test(timeout = 15000)
    fun laterLoginIntentWinsEvenWhenFirstResponseReturnsEarlier() = runBlocking {
        seedSession()
        val firstArrived = CompletableDeferred<Unit>()
        val secondArrived = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        server.dispatcher = dispatcher {
            firstArrived.complete(Unit)
            runBlocking { releaseFirst.await() }
            loginOk("acc-first", "ref-first")
        }
        other.dispatcher = dispatcher {
            secondArrived.complete(Unit)
            runBlocking { releaseSecond.await() }
            loginOk("acc-second", "ref-second")
        }
        coroutineScope {
            val first = async { repository.login(server.url("/").toString(), "c1", "12345678") }
            firstArrived.await()
            val second = async { repository.login(other.url("/").toString(), "c2", "12345678") }
            secondArrived.await()
            releaseFirst.complete(Unit)
            first.await()
            assertEquals("acc-old", session.accessToken())
            releaseSecond.complete(Unit)
            second.await()
        }
        assertEquals("acc-second", session.accessToken())
        assertEquals(other.url("/").toString(), session.baseUrlValue())
    }

    @Test(timeout = 15000)
    fun suspendedAuthorizedWorkKeepsItsServerSnapshotAfterAccountChanges() = runBlocking {
        seedSession()
        server.dispatcher = dispatcher { meOk() }
        other.dispatcher = dispatcher { loginOk("acc-b", "ref-b") }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            val pending = async {
                runCatching {
                    repository.authorizedServices { services, token ->
                        entered.complete(Unit)
                        release.await()
                        services.auth.me("Bearer $token")
                    }
                }
            }
            entered.await()
            repository.login(other.url("/").toString(), "c2", "12345678")
            release.complete(Unit)
            assertTrue(pending.await().exceptionOrNull() is com.example.citruscollect.network.AuthExpiredException)
        }
        assertEquals(1, other.requestCount) // 新主机仅收到登录，不收到旧会话工作
        assertEquals("Bearer acc-old", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!.getHeader("Authorization"))
        assertEquals("acc-b", session.accessToken())
    }

    @Test
    fun collectorCanLogin() = runBlocking {
        server.dispatcher = dispatcher { json(200, loginResponse("collector")) }
        repository.login(server.url("/").toString(), "c1", "12345678")
        assertEquals("acc", session.accessToken())
        assertEquals("ref", session.refreshToken())
        assertEquals("collector", session.role.first())
    }

    @Test
    fun adminIsRejectedAsCollectorAccount() = runBlocking {
        server.dispatcher = dispatcher { json(200, loginResponse("admin")) }
        var caught: ApiException? = null
        try {
            repository.login(server.url("/").toString(), "admin", "12345678")
        } catch (e: ApiException) {
            caught = e
        }
        assertTrue(caught != null)
        assertEquals("FORBIDDEN_ROLE", caught!!.code)
        assertNull(session.accessToken())
    }

    @Test
    fun loginErrorSurfacesMessage() = runBlocking {
        server.dispatcher = dispatcher {
            json(
                401,
                """{"error":{"code":"AUTH_INVALID","message":"账号或密码错误",
                   "details":{"reason":"bad_credentials"},"request_id":"019a0000-0000-7000-8000-0000000000aa"}}"""
            )
        }
        var caught: ApiException? = null
        try {
            repository.login(server.url("/").toString(), "c1", "wrong")
        } catch (e: ApiException) {
            caught = e
        }
        assertTrue(caught != null)
        assertEquals("账号或密码错误", caught!!.message)
        assertNull(session.accessToken())
    }

    @Test
    fun expiredAccessRefreshesOnceAndRetries() = runBlocking {
        seedSession()
        var meCalls = 0
        var refreshCalls = 0

        server.dispatcher = dispatcher { request ->
            when (request.path) {
                "/auth/refresh" -> {
                    refreshCalls++
                    refreshOk("acc-new", "ref-new")
                }
                "/me" -> {
                    meCalls++
                    if (meCalls == 1) expired401() else meOk()
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val me = repository.authorized { token ->
            backend.services().auth.me("Bearer $token")
        }

        assertEquals("c1", me.username)
        assertEquals(2, meCalls)
        assertEquals(1, refreshCalls)
        assertEquals("acc-new", session.accessToken())
    }

    @Test
    fun refreshFailureClearsSession() = runBlocking {
        seedSession()
        server.dispatcher = dispatcher { request ->
            when (request.path) {
                "/auth/refresh" -> json(401, """{"error":{"code":"AUTH_INVALID","message":"已撤销",
                    "details":{"reason":"revoked"},"request_id":"019a0000-0000-7000-8000-0000000000aa"}}""")
                "/me" -> expired401()
                else -> MockResponse().setResponseCode(404)
            }
        }

        var failed = false
        try {
            repository.authorized { token -> backend.services().auth.me("Bearer $token") }
        } catch (e: com.example.citruscollect.network.AuthExpiredException) {
            failed = true
        }
        assertTrue(failed)
        assertNull(session.accessToken())
    }

    @Test
    fun concurrentExpiredRequestsShareSingleRefresh() = runBlocking {
        seedSession()
        val refreshCalls = AtomicInteger()
        server.dispatcher = dispatcher { request ->
            when {
                request.path == "/auth/refresh" -> {
                    refreshCalls.incrementAndGet()
                    refreshOk("acc-new", "ref-new")
                }
                request.path == "/me" && request.getHeader("Authorization") == "Bearer acc-old" -> expired401()
                request.path == "/me" -> meOk()
                else -> MockResponse().setResponseCode(404)
            }
        }
        coroutineScope {
            val a = async { repository.authorized { t -> backend.services().auth.me("Bearer $t") } }
            val b = async { repository.authorized { t -> backend.services().auth.me("Bearer $t") } }
            assertEquals("c1", a.await().username)
            assertEquals("c1", b.await().username)
        }
        assertEquals(1, refreshCalls.get())
    }

    @Test
    fun lateExpired401ReusesRotatedTokenWithoutSecondRefresh() = runBlocking {
        seedSession()
        val firstMeArrived = CompletableDeferred<Unit>()
        val releaseFirstMe = CompletableDeferred<Unit>()
        val firstGated = AtomicBoolean(false)
        val refreshCalls = AtomicInteger()
        server.dispatcher = dispatcher { request ->
            when {
                request.path == "/auth/refresh" -> {
                    refreshCalls.incrementAndGet()
                    refreshOk("acc-new", "ref-new")
                }
                request.path == "/me" && request.getHeader("Authorization") == "Bearer acc-old" -> {
                    if (firstGated.compareAndSet(false, true)) {
                        firstMeArrived.complete(Unit)
                        runBlocking { releaseFirstMe.await() } // 第一个请求的旧 401 迟到
                    }
                    expired401()
                }
                request.path == "/me" -> meOk()
                else -> MockResponse().setResponseCode(404)
            }
        }
        coroutineScope {
            val first = async {
                runCatching { repository.authorized { t -> backend.services().auth.me("Bearer $t") } }
            }
            firstMeArrived.await()
            val second = async {
                runCatching { repository.authorized { t -> backend.services().auth.me("Bearer $t") } }
            }
            assertEquals("c1", second.await().getOrNull()?.username) // 401 → 刷新 → acc-new 成功
            releaseFirstMe.complete(Unit)                             // 第一请求此刻才收到旧 401
            assertEquals("c1", first.await().getOrNull()?.username)  // 复用 acc-new 重放，不再刷新
        }
        assertEquals(1, refreshCalls.get())
    }

    @Test
    fun pendingRefreshThenLogoutDoesNotRestoreSession() = runBlocking {
        seedSession()
        val refreshArrived = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        var logoutAuth: String? = null
        server.dispatcher = dispatcher { request ->
            when {
                request.path == "/auth/refresh" -> {
                    refreshArrived.complete(Unit)
                    runBlocking { releaseRefresh.await() }
                    refreshOk("acc-new", "ref-new")
                }
                request.path == "/auth/logout" -> {
                    logoutAuth = request.getHeader("Authorization")
                    MockResponse().setResponseCode(204)
                }
                request.path == "/me" -> expired401()
                else -> MockResponse().setResponseCode(404)
            }
        }
        coroutineScope {
            val pending = async {
                runCatching { repository.authorized { t -> backend.services().auth.me("Bearer $t") } }
            }
            refreshArrived.await()
            repository.logout() // 刷新在途时退出：本地立即失效
            releaseRefresh.complete(Unit)
            val failure = pending.await().exceptionOrNull()
            assertTrue(failure is HttpException) // 迟到刷新不恢复会话，按原 401 失败
            assertEquals(401, (failure as HttpException).code())
        }
        assertEquals("Bearer acc-old", logoutAuth) // 退出用捕获的旧凭据尽力撤销
        assertNull(session.accessToken())
        assertNull(session.refreshToken())
    }

    @Test
    fun pendingRefreshThenReLoginDoesNotOverwriteNewSession() = runBlocking {
        seedSession()
        val refreshArrived = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        server.dispatcher = dispatcher { request ->
            when (request.path) {
                "/auth/refresh" -> {
                    refreshArrived.complete(Unit)
                    runBlocking { releaseRefresh.await() }
                    refreshOk("acc-x", "ref-x")
                }
                "/me" -> expired401()
                else -> MockResponse().setResponseCode(404)
            }
        }
        other.dispatcher = dispatcher { loginOk("acc-b", "ref-b") }
        coroutineScope {
            val pending = async {
                runCatching { repository.authorized { t -> backend.services().auth.me("Bearer $t") } }
            }
            refreshArrived.await()
            repository.login(other.url("/").toString(), "c2", "12345678") // 换号：立即提交新会话
            releaseRefresh.complete(Unit)
            assertTrue(pending.await().exceptionOrNull() is HttpException) // 旧请求按原 401 失败
        }
        assertEquals("acc-b", session.accessToken()) // 旧刷新结果未覆盖新会话
        assertEquals("ref-b", session.refreshToken())
        assertEquals(other.url("/").toString(), backend.baseUrl())
    }

    @Test
    fun pendingLoginThenLogoutDiscardsLateLogin() = runBlocking {
        seedSession()
        val loginArrived = CompletableDeferred<Unit>()
        val releaseLogin = CompletableDeferred<Unit>()
        other.dispatcher = dispatcher { request ->
            if (request.path == "/auth/login") {
                loginArrived.complete(Unit)
                runBlocking { releaseLogin.await() }
                loginOk("acc-b", "ref-b")
            } else {
                MockResponse().setResponseCode(404)
            }
        }
        server.dispatcher = dispatcher { request ->
            if (request.path == "/auth/logout") MockResponse().setResponseCode(204) else MockResponse().setResponseCode(404)
        }
        coroutineScope {
            val pendingLogin = async {
                runCatching { repository.login(other.url("/").toString(), "c2", "12345678") }
            }
            loginArrived.await()
            repository.logout() // 登录在途时退出：本地立即清理
            releaseLogin.complete(Unit)
            assertTrue(pendingLogin.await().isSuccess) // 迟到登录被静默丢弃，不抛错
        }
        assertNull(session.accessToken())
        assertNull(session.refreshToken())
        assertEquals(server.url("/").toString(), backend.baseUrl()) // 未切到新主机
    }

    @Test
    fun temporaryRefreshFailureKeepsSessionWithOriginalError() = runBlocking {
        seedSession()
        server.dispatcher = dispatcher { request ->
            when (request.path) {
                "/auth/refresh" -> json(503, """{"error":{"code":"UNAVAILABLE","message":"服务暂不可用"}}""")
                "/me" -> expired401()
                else -> MockResponse().setResponseCode(404)
            }
        }
        var caught: Exception? = null
        try {
            repository.authorized { token -> backend.services().auth.me("Bearer $token") }
        } catch (e: Exception) {
            caught = e
        }
        assertTrue(caught is HttpException) // 不统一转成 AuthExpiredException
        assertEquals(503, (caught as HttpException).code()) // 保留真正的刷新失败原因
        assertEquals("服务暂不可用", ApiErrors.messageOf(caught))
        assertEquals("acc-old", session.accessToken()) // 临时失败保留凭据
        assertEquals("ref-old", session.refreshToken())
    }

    @Test
    fun permanentRejectionClearsSessionButPlainForbiddenKeepsIt() = runBlocking {
        seedSession()
        var meResp = json(403, """{"error":{"code":"FORBIDDEN","message":"无权限执行该操作"}}""")
        server.dispatcher = dispatcher { meResp }
        var caught: Exception? = null
        try {
            repository.authorized { token -> backend.services().auth.me("Bearer $token") }
        } catch (e: Exception) {
            caught = e
        }
        assertTrue(caught is HttpException)
        assertEquals("acc-old", session.accessToken()) // 普通权限 403 不清理
        meResp = json(403, """{"error":{"code":"ACCOUNT_DISABLED","message":"账号已停用"}}""")
        caught = null
        try {
            repository.authorized { token -> backend.services().auth.me("Bearer $token") }
        } catch (e: Exception) {
            caught = e
        }
        assertTrue(caught is HttpException)
        assertNull(session.accessToken()) // 账号停用清理当前会话
        assertNull(session.refreshToken())
    }

    @Test
    fun restoreUsesPersistedAddressWithoutOverridingNewLogin() = runBlocking {
        // 恢复：持久会话首次请求之前确定其对应地址
        session.saveLogin(other.url("/api/v1/").toString(), "c1", "collector", "acc", "ref", 1800)
        repository.restorePersistedBaseUrl()
        assertEquals(other.url("/api/v1/").toString(), backend.baseUrl())

        // 旧恢复不覆盖用户新登录地址（新仓库模拟“登录先于恢复完成”）
        val session2 = FakeSessionStore()
        val repository2 = AuthRepository(backend, session2)
        server.dispatcher = dispatcher { json(200, loginResponse("collector")) }
        repository2.login(server.url("/").toString(), "c1", "12345678")
        repository2.restorePersistedBaseUrl()
        assertEquals(server.url("/").toString(), backend.baseUrl())
    }

    @Test
    fun failedLoginToOtherHostKeepsSessionAndAddress() = runBlocking {
        seedSession()
        other.dispatcher = dispatcher {
            json(401, """{"error":{"code":"AUTH_INVALID","message":"账号或密码错误","details":{"reason":"bad_credentials"}}}""")
        }
        var caught: Exception? = null
        try {
            repository.login(other.url("/").toString(), "c1", "wrong")
        } catch (e: ApiException) {
            caught = e
        }
        assertTrue(caught != null)
        assertEquals(server.url("/").toString(), backend.baseUrl()) // 未切到新主机
        assertEquals("acc-old", session.accessToken()) // 旧令牌未与新主机错配
        assertEquals("ref-old", session.refreshToken())
    }

    @Test fun phoneLoginUsesCanonicalIdentityAndCodeSendDoesNotSwitchHost() = runBlocking {
        seedSession()
        other.enqueue(json(200, """{"challenge_id":"challenge","resend_after_seconds":60,"expires_in":300}"""))
        val challenge = repository.sendPhoneCode(other.url("/").toString(), "13800000000")
        assertEquals("challenge", challenge.challenge_id)
        assertEquals(server.url("/").toString(), backend.baseUrl())
        assertEquals("acc-old", session.accessToken())
        assertEquals("/auth/phone/code", other.takeRequest().path)
        other.enqueue(json(200, loginResponse("collector")))
        repository.loginPhone(other.url("/").toString(), "13800000000", "challenge", "123456")
        val request = other.takeRequest()
        assertEquals("/auth/phone/login", request.path)
        assertTrue(request.body.readUtf8().contains("\"phone\":\"13800000000\""))
        assertEquals(other.url("/").toString(), backend.baseUrl())
        assertEquals("c1", session.username.first())
    }

    @Test fun failedPhoneLoginKeepsOriginalSessionAndHost() = runBlocking {
        seedSession()
        other.enqueue(json(401, """{"error":{"code":"SMS_CODE_INVALID","message":"验证码无效"}}"""))
        var failure: ApiException? = null
        try { repository.loginPhone(other.url("/").toString(), "13800000000", "challenge", "000000") }
        catch (e: ApiException) { failure = e }
        assertTrue(failure != null)
        assertEquals(server.url("/").toString(), backend.baseUrl())
        assertEquals("acc-old", session.accessToken())
    }

    private fun profileJson(name: String) = """{"user_id":"user-1","username":"c1","display_name":"$name","role":"collector","phone_masked":"138****2468","phone_verified":true}"""

    @Test fun nicknameUpdatePreservesOwnerAndCachesOnlyCurrentAccount() = runBlocking {
        seedSession()
        val owner = repository.currentOwnerKey()
        server.enqueue(json(200, profileJson("原昵称")))
        assertEquals("原昵称", repository.profile().display_name)
        assertEquals("原昵称", repository.cachedProfile()?.display_name)
        server.takeRequest()
        server.enqueue(json(200, profileJson("新昵称")))
        assertEquals("新昵称", repository.updateNickname(" 新昵称 ").display_name)
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/me/profile", patch.path)
        assertTrue(patch.body.readUtf8().contains("新昵称"))
        assertEquals(owner, repository.currentOwnerKey())
        assertEquals("新昵称", repository.cachedProfile()?.display_name)
        other.enqueue(json(200, loginResponse("collector")))
        repository.login(other.url("/").toString(), "c1", "password")
        assertNull(repository.cachedProfile())
    }

    @Test fun failedNicknameSaveKeepsCachedProfileAndSession() = runBlocking {
        seedSession()
        server.enqueue(json(200, profileJson("原昵称"))); repository.profile()
        server.enqueue(json(503, """{"error":{"code":"UNAVAILABLE","message":"暂时不可用"}}"""))
        var failure: ApiException? = null
        try { repository.updateNickname("新昵称") } catch (e: ApiException) { failure = e }
        assertTrue(failure != null)
        assertEquals("原昵称", repository.cachedProfile()?.display_name)
        assertEquals("acc-old", session.accessToken())
    }

    @Test fun lateProfileResponseCannotOverwriteSwitchedAccount() = runBlocking {
        seedSession()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = dispatcher {
            entered.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            json(200, profileJson("旧账号昵称"))
        }
        val request = async(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { repository.profile() }
        }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        other.enqueue(json(200, loginResponse("collector")))
        repository.login(other.url("/").toString(), "c1", "password")
        release.countDown()
        assertTrue(request.await().isFailure)
        assertNull(repository.cachedProfile())
        assertEquals(other.url("/").toString(), backend.baseUrl())
    }
}
