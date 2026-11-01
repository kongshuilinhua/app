package com.example.citruscollect.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 会话持久化抽象：真实实现用 DataStore；测试可用内存实现，避免 JVM 依赖。 */
interface SessionStore {
    val username: Flow<String?>
    val token: Flow<String?>
    val role: Flow<String?>
    val account: Flow<String?> get() = kotlinx.coroutines.flow.combine(baseUrl, username, token) { url, user, access ->
        if (url == null || user == null || access == null) null else AuthRepository.accountKey(url, user)
    }
    val baseUrl: Flow<String?>

    suspend fun accessToken(): String?
    suspend fun refreshToken(): String?
    suspend fun baseUrlValue(): String?

    suspend fun saveLogin(
        baseUrl: String,
        username: String,
        role: String,
        accessToken: String,
        refreshToken: String,
        expiresIn: Int
    )

    suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Int)
    suspend fun clear()
    suspend fun loadProfileCache(key: String): String? = null
    suspend fun saveProfileCache(key: String, value: String) { }
    suspend fun loadBootstrapCache(key: String): String? = null
    suspend fun saveBootstrapCache(key: String, value: String) { }
}

private val Context.dataStore by preferencesDataStore(name = "session")

class DataStoreSessionStore(private val context: Context) : SessionStore {
    override suspend fun loadProfileCache(key: String): String? {
        val data = context.dataStore.data.first()
        return data[KEY_PROFILE_VALUE]?.takeIf { data[KEY_PROFILE_ID] == key }
    }
    override suspend fun saveProfileCache(key: String, value: String) {
        context.dataStore.edit { it[KEY_PROFILE_ID] = key; it[KEY_PROFILE_VALUE] = value }
    }
    override suspend fun loadBootstrapCache(key: String): String? {
        val data = context.dataStore.data.first()
        return data[KEY_BOOTSTRAP_VALUE]?.takeIf { data[KEY_BOOTSTRAP_ID] == key }
    }
    override suspend fun saveBootstrapCache(key: String, value: String) {
        context.dataStore.edit { it[KEY_BOOTSTRAP_ID] = key; it[KEY_BOOTSTRAP_VALUE] = value }
    }
    @Volatile private var memoryAccess: String? = null
    @Volatile private var memoryRefresh: String? = null
    @Volatile private var memoryBaseUrl: String? = null

    // 读缓存回填与写入/清空串行化：clear 之后再完成的迟到读不会把旧值重新填回内存缓存。
    private val storeMutex = Mutex()

    override val account: Flow<String?> = context.dataStore.data.map { values ->
        val url = values[KEY_BASE_URL]; val user = values[KEY_USERNAME]
        if (url == null || user == null || values[KEY_ACCESS] == null) null else AuthRepository.accountKey(url, user)
    }
    override val username: Flow<String?> = context.dataStore.data.map { it[KEY_USERNAME] }
    override val token: Flow<String?> = context.dataStore.data.map { it[KEY_ACCESS] }
    override val role: Flow<String?> = context.dataStore.data.map { it[KEY_ROLE] }
    override val baseUrl: Flow<String?> = context.dataStore.data.map { it[KEY_BASE_URL] }

    override suspend fun accessToken(): String? = storeMutex.withLock {
        memoryAccess ?: context.dataStore.data.first()[KEY_ACCESS].also { memoryAccess = it }
    }

    override suspend fun refreshToken(): String? = storeMutex.withLock {
        memoryRefresh ?: context.dataStore.data.first()[KEY_REFRESH].also { memoryRefresh = it }
    }

    override suspend fun baseUrlValue(): String? = storeMutex.withLock {
        memoryBaseUrl ?: context.dataStore.data.first()[KEY_BASE_URL].also { memoryBaseUrl = it }
    }

    override suspend fun saveLogin(
        baseUrl: String,
        username: String,
        role: String,
        accessToken: String,
        refreshToken: String,
        expiresIn: Int
    ) = storeMutex.withLock {
        context.dataStore.edit {
            it[KEY_BASE_URL] = baseUrl
            it[KEY_USERNAME] = username
            it[KEY_ROLE] = role
            it[KEY_ACCESS] = accessToken
            it[KEY_REFRESH] = refreshToken
            it[KEY_EXPIRES_IN] = expiresIn.toString()
        }
        memoryAccess = accessToken
        memoryRefresh = refreshToken
        memoryBaseUrl = baseUrl
    }

    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Int) = storeMutex.withLock {
        context.dataStore.edit {
            it[KEY_ACCESS] = accessToken
            it[KEY_REFRESH] = refreshToken
            it[KEY_EXPIRES_IN] = expiresIn.toString()
        }
        memoryAccess = accessToken
        memoryRefresh = refreshToken
    }

    override suspend fun clear() = storeMutex.withLock {
        context.dataStore.edit { it.clear() }
        memoryAccess = null
        memoryRefresh = null
        memoryBaseUrl = null
    }

    companion object {
        private val KEY_PROFILE_ID = stringPreferencesKey("profile_cache_id")
        private val KEY_PROFILE_VALUE = stringPreferencesKey("profile_cache_value")
        private val KEY_BOOTSTRAP_ID = stringPreferencesKey("bootstrap_cache_id")
        private val KEY_BOOTSTRAP_VALUE = stringPreferencesKey("bootstrap_cache_value")
        private val KEY_BASE_URL = stringPreferencesKey("base_url")
        private val KEY_USERNAME = stringPreferencesKey("username")
        private val KEY_ROLE = stringPreferencesKey("role")
        private val KEY_ACCESS = stringPreferencesKey("access_token")
        private val KEY_REFRESH = stringPreferencesKey("refresh_token")
        private val KEY_EXPIRES_IN = stringPreferencesKey("expires_in")
    }
}
