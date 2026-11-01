package com.example.citruscollect.data

import com.example.citruscollect.network.ApiErrors
import com.example.citruscollect.network.ApiException
import com.example.citruscollect.network.AuthExpiredException
import com.example.citruscollect.network.Backend
import com.example.citruscollect.network.BootstrapResponse
import com.example.citruscollect.network.ProfileResponse
import com.example.citruscollect.network.ProfilePatch
import com.example.citruscollect.network.PhoneCodeRequest
import com.example.citruscollect.network.PhoneCodeResponse
import com.example.citruscollect.network.PhoneLoginRequest
import com.example.citruscollect.network.LoginResponse
import com.example.citruscollect.network.LoginRequest
import com.example.citruscollect.network.RefreshRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import com.example.citruscollect.network.CitrusJson
import java.io.IOException

@Serializable
private data class CachedBootstrap(val savedAt: Long, val data: BootstrapResponse)

/**
 * 认证与配置仓库：真实网络登录、刷新、退出、bootstrap。
 *
 * 会话生命周期用「会话代际 epoch + 只覆盖短临界区的 Mutex」保护：登录提交、
 * 退出、失效清理递增代际；刷新/登录/恢复等异步结果写回前校验代际，迟到的旧
 * 结果不得恢复已退出会话、覆盖或清理新会话。Mutex 仅保护检查-提交等短临界区，
 * 不跨网络请求持有，本地退出无需等待在途网络。
 */
class AuthRepository(
    private val backend: Backend,
    private val session: SessionStore
) {
    val username: Flow<String?> = session.username
    val token: Flow<String?> = session.token
    val ownerKey: Flow<String?> = session.account.distinctUntilChanged()
    suspend fun currentOwnerKey(): String? = stateMutex.withLock {
        if (session.accessToken() == null) null else session.baseUrlValue()?.let { url ->
            session.username.first()?.let { accountKey(url, it) }
        }
    }
    suspend fun <T> authorizedFor(owner: String, block: suspend (Backend.Services, String) -> T): T =
        authorizedServices(expectedOwner = owner, block = block)
    companion object {
        fun accountKey(url: String, username: String): String = url.trim().trimEnd('/') + "/\n" + username
    }

    private sealed interface RefreshOutcome {
        data class Renewed(val accessToken: String) : RefreshOutcome

        /** 刷新令牌被明确拒绝（无效/已轮换/撤销、账号停用）：会话永久失效。 */
        object Terminal : RefreshOutcome

        /** IO 错误、429、5xx 等临时故障：保留凭据，调用方保留原错误。 */
        data class Temporary(val error: Exception? = null) : RefreshOutcome
    }

    private val stateMutex = Mutex()
    private val profileMutex = Mutex()
    private var epoch = 0L
    private var loginSequence = 0L
    private var restoreDone = false
    private var refreshFlight: Pair<Long, CompletableDeferred<RefreshOutcome>>? = null

    private suspend fun currentEpoch(): Long = stateMutex.withLock { epoch }

    /**
     * 启动恢复（幂等）：持久会话首次请求之前确定其对应的全局服务地址；
     * 若用户已先行登录提交了新地址/新会话（epoch 已前进），则跳过，不覆盖新选择。
     */
    suspend fun restorePersistedBaseUrl() {
        stateMutex.withLock {
            if (restoreDone) return
            restoreDone = true
            if (epoch != 0L) return
            if (session.refreshToken() == null) return
            val url = session.baseUrlValue() ?: return
            try {
                backend.setBaseUrl(url)
            } catch (_: IllegalArgumentException) {
                // 持久地址非法（旧数据）：忽略，登录时会重新提交
            }
        }
    }

    /**
     * 登录：用指定地址的服务快照发起请求（不改全局地址，失败不会留下旧令牌配
     * 新主机的状态）；成功且仍属于当前代际时才一并提交地址与会话。
     */
    suspend fun login(baseUrl: String, username: String, password: String) =
        loginUsing(baseUrl) { it.auth.login(LoginRequest(username, password)) }

    suspend fun sendPhoneCode(baseUrl: String, phone: String): PhoneCodeResponse = try {
        backend.servicesFor(baseUrl).auth.sendPhoneCode(PhoneCodeRequest(phone.trim()))
    } catch (e: HttpException) {
        val error = ApiErrors.parse(e)
        throw ApiException(e.code(), error?.code, error?.message ?: "验证码发送失败", error?.request_id)
    }

    suspend fun loginPhone(baseUrl: String, phone: String, challenge: String, code: String) =
        loginUsing(baseUrl) { it.auth.loginPhone(PhoneLoginRequest(phone.trim(), challenge, code)) }

    private suspend fun loginUsing(baseUrl: String, action: suspend (Backend.Services) -> LoginResponse) {
        val services = backend.servicesFor(baseUrl)
        val (myEpoch, operation) = stateMutex.withLock { epoch to ++loginSequence }
        val response = try {
            action(services)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val error = ApiErrors.parse(e)
            throw ApiException(e.code(), error?.code, error?.message ?: "请求失败（HTTP ${e.code()}）", error?.request_id)
        }
        if (response.user.role != "collector") {
            throw ApiException(200, "FORBIDDEN_ROLE", "该账号不是采集账号，无法登录采集端")
        }
        if (response.access_token.isBlank() || response.refresh_token.isBlank() || response.user.username.isBlank()) {
            throw ApiException(200, "SESSION_INVALID", "登录响应缺少必要的会话字段")
        }
        stateMutex.withLock {
            if (myEpoch != epoch || operation != loginSequence) return
            backend.setBaseUrl(baseUrl) // 成功才切换全局地址
            session.saveLogin(
                baseUrl = backend.baseUrl(),
                username = response.user.username,
                role = response.user.role,
                accessToken = response.access_token,
                refreshToken = response.refresh_token,
                expiresIn = response.expires_in
            )
            epoch += 1
        }
    }

    /**
     * 退出：先同步失效本地会话（进入新代际，pending 刷新/登录/恢复全部作废），
     * 再用退出前捕获的旧凭据与其对应地址的服务快照尽力撤销服务端会话；
     * 失败忽略，取消向上传播。
     */
    suspend fun logout() {
        val (access, baseUrl) = stateMutex.withLock {
            val captured = session.accessToken() to session.baseUrlValue()
            epoch += 1
            session.clear()
            captured
        }
        if (access.isNullOrBlank()) return
        val service = try {
            baseUrl?.let { backend.servicesFor(it) } ?: backend.services()
        } catch (_: IllegalArgumentException) {
            return
        }
        try {
            service.auth.logout("Bearer $access")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 服务端撤销尽力而为，失败不影响已完成的本地清理
        }
    }

    /** 显式刷新一次当前会话令牌；临时失败或永久失效（已清理）返回 false。 */
    suspend fun refresh(): Boolean {
        restorePersistedBaseUrl()
        val myEpoch = currentEpoch()
        return when (val outcome = refreshShared(myEpoch)) {
            is RefreshOutcome.Renewed -> true
            RefreshOutcome.Terminal -> {
                clearSessionIfCurrent(myEpoch)
                false
            }
            is RefreshOutcome.Temporary -> false
        }
    }

    private suspend fun profileIdentity(): Pair<Long, String> = stateMutex.withLock {
        val url = session.baseUrlValue() ?: throw AuthExpiredException("请先登录")
        val name = session.username.first() ?: throw AuthExpiredException("请先登录")
        if (session.accessToken() == null) throw AuthExpiredException("请先登录")
        epoch to accountKey(url, name)
    }

    suspend fun cachedProfile(): ProfileResponse? {
        val (generation, owner) = profileIdentity()
        val value = session.loadProfileCache(owner) ?: return null
        val result = runCatching { CitrusJson.json.decodeFromString<ProfileResponse>(value) }.getOrNull()
        return if (generation == currentEpoch()) result else null
    }

    suspend fun profile(): ProfileResponse = profileMutex.withLock { loadProfile() }

    private suspend fun loadProfile(): ProfileResponse {
        restorePersistedBaseUrl()
        val (generation, owner) = profileIdentity()
        val loaded = authorizedServices(expectedOwner = owner) { services, token -> services.auth.profile("Bearer $token") }
        stateMutex.withLock {
            if (generation != epoch) throw AuthExpiredException("会话已切换，请重新操作")
            session.saveProfileCache(owner, CitrusJson.json.encodeToString(loaded))
        }
        return loaded
    }

    suspend fun updateNickname(nickname: String): ProfileResponse = profileMutex.withLock { saveNickname(nickname) }

    private suspend fun saveNickname(nickname: String): ProfileResponse {
        restorePersistedBaseUrl()
        val (generation, owner) = profileIdentity()
        val loaded = try {
            authorizedServices(expectedOwner = owner) { services, token ->
                services.auth.updateProfile("Bearer $token", ProfilePatch(nickname.trim()))
            }
        } catch (e: HttpException) {
            val error = ApiErrors.parse(e)
            throw ApiException(e.code(), error?.code, error?.message ?: "昵称保存失败", error?.request_id)
        }
        stateMutex.withLock {
            if (generation != epoch) throw AuthExpiredException("会话已切换，请重新操作")
            session.saveProfileCache(owner, CitrusJson.json.encodeToString(loaded))
        }
        return loaded
    }

    suspend fun bootstrap(forceRefresh: Boolean = false): BootstrapResponse {
        restorePersistedBaseUrl()
        val startEpoch = currentEpoch()
        val key = "${session.baseUrlValue()}\n${session.username.first()}"
        val cached = session.loadBootstrapCache(key)?.let {
            runCatching { CitrusJson.json.decodeFromString<CachedBootstrap>(it) }.getOrNull()
        }
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cached.savedAt in 0..300_000L && currentEpoch() == startEpoch) return cached.data
        try {
            val loaded = authorizedServices { services, token -> services.config.bootstrap("Bearer $token") }
            if (currentEpoch() != startEpoch) throw AuthExpiredException("会话已切换，请重新操作")
            session.saveBootstrapCache(key, CitrusJson.json.encodeToString(CachedBootstrap(System.currentTimeMillis(), loaded)))
            return loaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val transient = e is IOException || (e is HttpException && (e.code() == 429 || e.code() >= 500))
            if (transient && cached != null && currentEpoch() == startEpoch) return cached.data
            throw e
        }
    }

    suspend fun <T> authorized(block: suspend (token: String) -> T): T =
        authorizedServices { _, token -> block(token) }

    /**
     * 携带当前会话凭据执行一次请求；请求使用会话对应地址的服务快照，不在
     * 挂起后读取已切换的全局主机。明确 AUTH_INVALID reason=expired 时同代际
     * 共享刷新一次并重放一次；迟到旧 token 的 401 直接复用同会话已轮换的令牌。
     * 永久认证拒绝（非过期 AUTH_INVALID、账号停用、重放后再拒）仅清理匹配的
     * 当前会话；普通 403 不清理；临时刷新失败保留凭据并保留原错误。
     */
    suspend fun <T> authorizedServices(expectedOwner: String? = null, block: suspend (services: Backend.Services, token: String) -> T): T {
        restorePersistedBaseUrl()
        val (myEpoch, baseUrl, initialToken) = stateMutex.withLock {
            val url = session.baseUrlValue()
            val token = session.accessToken() ?: throw AuthExpiredException("未登录")
            if (expectedOwner != null) {
                val user = session.username.first()
                if (url == null || user == null || accountKey(url, user) != expectedOwner) throw AuthExpiredException("账号已切换，原账号任务已暂停")
            }
            Triple(epoch, url, token)
        }
        val services = serviceSnapshotFor(baseUrl)

        try {
            val result = block(services, initialToken)
            if (currentEpoch() != myEpoch) throw AuthExpiredException("会话已切换，请重新操作")
            return result
        } catch (e: HttpException) {
            if (e.code() == 401 && ApiErrors.isExpired(e)) {
                if (currentEpoch() != myEpoch) throw e // 旧会话请求：不刷新、不重放、不清理新会话
                // 迟到的旧 token 过期 401：同会话已有新 token 时直接复用，不再刷新
                val replayToken = stateMutex.withLock {
                    if (epoch != myEpoch) null
                    else session.accessToken()?.takeIf { it.isNotBlank() && it != initialToken }
                } ?: run {
                    when (val outcome = refreshShared(myEpoch, initialToken)) {
                        is RefreshOutcome.Renewed -> outcome.accessToken
                        RefreshOutcome.Terminal -> {
                            clearSessionIfCurrent(myEpoch)
                            throw AuthExpiredException("登录已过期，请重新登录")
                        }
                        is RefreshOutcome.Temporary -> throw (outcome.error ?: e)
                    }
                }
                if (currentEpoch() != myEpoch) throw e // 刷新期间退出/换号：不得用新会话凭据重放
                return try {
                    val result = block(services, replayToken)
                    if (currentEpoch() != myEpoch) throw AuthExpiredException("会话已切换，请重新操作")
                    result
                } catch (e2: HttpException) {
                    if (isAuthRejection(e2)) {
                        clearSessionIfCurrent(myEpoch)
                        throw AuthExpiredException("登录已过期，请重新登录")
                    }
                    throw e2
                }
            }
            if (isAuthRejection(e)) {
                clearSessionIfCurrent(myEpoch) // 永久 401/撤销/账号停用：仅清理匹配的当前会话
            }
            throw e
        }
    }

    /** 明确的认证拒绝：401 AUTH_INVALID 或 403 ACCOUNT_DISABLED；普通权限 403 不算。 */
    private fun isAuthRejection(e: HttpException): Boolean {
        val body = ApiErrors.parse(e) ?: return false
        return (e.code() == 401 && body.code == "AUTH_INVALID") ||
            (e.code() == 403 && body.code == "ACCOUNT_DISABLED")
    }

    /** 会话地址的只读服务快照；无地址或地址非法时回退全局当前地址。 */
    private fun serviceSnapshotFor(baseUrl: String?): Backend.Services = try {
        baseUrl?.let { backend.servicesFor(it) } ?: backend.services()
    } catch (_: IllegalArgumentException) {
        backend.services()
    }

    /** 仅当仍处于该代际时清理会话；旧会话的失败不得清空新会话。 */
    private suspend fun clearSessionIfCurrent(myEpoch: Long) {
        stateMutex.withLock {
            if (epoch != myEpoch) return
            epoch += 1
            session.clear()
        }
    }

    /** 同代际并发过期请求共用一次刷新；不同代际互不共用。 */
    private suspend fun refreshShared(myEpoch: Long, rejectedToken: String? = null): RefreshOutcome {
        val (deferred, created) = stateMutex.withLock {
            if (epoch != myEpoch) return RefreshOutcome.Temporary()
            // 令牌检查与创建共享刷新在同一临界区，避免检查后另一个刷新刚好完成。
            if (rejectedToken != null) {
                session.accessToken()?.takeIf { it.isNotBlank() && it != rejectedToken }?.let {
                    return RefreshOutcome.Renewed(it)
                }
            }
            val existing = refreshFlight
            if (existing != null && existing.first == myEpoch) {
                existing.second to false
            } else {
                val d = CompletableDeferred<RefreshOutcome>()
                refreshFlight = myEpoch to d
                d to true
            }
        }
        if (!created) return deferred.await()
        try {
            val outcome = doRefresh(myEpoch)
            deferred.complete(outcome)
            return outcome
        } catch (e: CancellationException) {
            deferred.complete(RefreshOutcome.Temporary()) // 等待方保留会话，下一次可重新刷新
            throw e
        } catch (e: Exception) {
            deferred.complete(RefreshOutcome.Temporary(e))
            throw e
        } finally {
            withContext(NonCancellable) {
                stateMutex.withLock {
                    if (refreshFlight?.second === deferred) refreshFlight = null
                }
            }
        }
    }

    private suspend fun doRefresh(myEpoch: Long): RefreshOutcome {
        val (refreshToken, baseUrl) = stateMutex.withLock {
            if (epoch != myEpoch) return RefreshOutcome.Temporary()
            val rt = session.refreshToken() ?: return RefreshOutcome.Terminal
            rt to session.baseUrlValue()
        }
        val service = serviceSnapshotFor(baseUrl)
        val response = try {
            service.auth.refresh(RefreshRequest(refreshToken))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val body = ApiErrors.parse(e)
            return when {
                e.code() == 401 && body?.code == "AUTH_INVALID" -> RefreshOutcome.Terminal
                e.code() == 403 && body?.code == "ACCOUNT_DISABLED" -> RefreshOutcome.Terminal
                else -> RefreshOutcome.Temporary(e) // 429、5xx 等
            }
        } catch (e: Exception) {
            return RefreshOutcome.Temporary(e)
        }
        // 成功刷新先校验非空令牌，无效响应不覆盖持久会话
        if (response.access_token.isBlank() || response.refresh_token.isBlank()) {
            return RefreshOutcome.Temporary(ApiException(502, null, "刷新响应缺少有效令牌"))
        }
        return stateMutex.withLock {
            if (epoch != myEpoch) return RefreshOutcome.Temporary() // 会话已切换：结果不写回
            session.saveTokens(response.access_token, response.refresh_token, response.expires_in)
            RefreshOutcome.Renewed(response.access_token)
        }
    }
}
