package com.example.citruscollect.ui.screens

import android.os.SystemClock
import com.example.citruscollect.BuildConfig
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.citruscollect.network.Backend
import com.example.citruscollect.data.AuthRepository
import com.example.citruscollect.ui.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoginScreen() {
    val repository = LocalAppContainer.current.authRepository
    val scope = rememberCoroutineScope()
    var phoneMode by remember { mutableStateOf(!BuildConfig.LOCAL_DEBUG) }
    val sms = remember { SmsFormState() }
    var identifier by remember { mutableStateOf(if (BuildConfig.LOCAL_DEBUG) "collector1" else "") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = !phoneMode && !busy) {
        phoneMode = true; password = ""; error = null
    }
    AuthFrame(if (phoneMode) "手机号登录" else "账号密码登录", if (BuildConfig.LOCAL_DEBUG) "本地调试 · 数据独立保存" else "柑橘病虫害采集") {
        if (phoneMode) {
            SmsFields(sms, repository, busy, onBusy = { busy = it }, onError = { error = it })
            Text("未注册手机号验证后将自动创建账号", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
        } else {
            OutlinedTextField(identifier, { identifier = it }, label = { Text("账号") }, enabled = !busy,
                singleLine = true, modifier = Modifier.fillMaxWidth())
            PasswordField(password, { password = it }, "密码", !busy)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) }
        Button(enabled = !busy && if (phoneMode) sms.ready else identifier.isNotBlank() && password.isNotBlank(), onClick = {
            scope.launch {
                busy = true; error = null
                try {
                    if (phoneMode) repository.loginPhone(Backend.DEFAULT_BASE_URL, sms.phone, sms.challenge, sms.code)
                    else repository.login(Backend.DEFAULT_BASE_URL, identifier.trim(), password)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "登录失败，请重试" }
                finally { busy = false }
            }
        }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp)) else Text("登录")
        }
        TextButton(enabled = !busy, onClick = { phoneMode = !phoneMode; password = ""; error = null },
            modifier = Modifier.padding(top = 8.dp)) {
            Text(if (phoneMode) "账号密码登录" else "手机验证码登录")
        }
    }
}

@Composable
private fun AuthFrame(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 28.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp, bottom = 24.dp))
        content()
    }
}

@Composable
private fun PasswordField(value: String, onValue: (String) -> Unit, label: String, enabled: Boolean) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onValue, enabled = enabled, singleLine = true,
        label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示") } },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
}

@Stable
private class SmsFormState {
    var phone by mutableStateOf("")
    var code by mutableStateOf("")
    var challenge by mutableStateOf("")
    var resendUntil by mutableStateOf(0L)
    var remaining by mutableStateOf(0)
    var message by mutableStateOf<String?>(null)
    val ready get() = phone.length == 11 && challenge.isNotBlank() && code.length == 6
    fun invalidate() { challenge = ""; code = ""; message = null }
}

@Composable
private fun SmsFields(state: SmsFormState, repository: AuthRepository, busy: Boolean,
                      onBusy: (Boolean) -> Unit, onError: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.resendUntil) {
        do {
            state.remaining = ((state.resendUntil - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0).toInt()
            if (state.remaining > 0) delay(1000)
        } while (state.remaining > 0)
    }
    OutlinedTextField(state.phone, { state.phone = it.filter { c -> c in '0'..'9' }.take(11); state.invalidate() },
        label = { Text("手机号") }, enabled = !busy, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    OutlinedTextField(state.code, { state.code = it.filter { c -> c in '0'..'9' }.take(6) },
        label = { Text("6位短信验证码") }, enabled = !busy, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    OutlinedButton(enabled = !busy && state.phone.length == 11 && state.remaining == 0, onClick = {
        scope.launch {
            onBusy(true); onError(null); state.invalidate()
            try {
                val result = repository.sendPhoneCode(Backend.DEFAULT_BASE_URL, state.phone)
                state.challenge = result.challenge_id
                state.resendUntil = SystemClock.elapsedRealtime() + result.resend_after_seconds * 1000L
                state.remaining = result.resend_after_seconds
                state.message = "验证码已发送，${result.expires_in / 60}分钟内有效"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(e.message ?: "验证码发送失败，请稍后重试") }
            finally { onBusy(false) }
        }
    }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(if (state.remaining > 0) "${state.remaining}秒后可重新发送" else "获取验证码")
    }
    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
}
