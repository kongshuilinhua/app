package com.example.citruscollect.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.citruscollect.BuildConfig
import com.example.citruscollect.R
import com.example.citruscollect.network.ProfileResponse
import com.example.citruscollect.ui.LocalAppContainer
import com.example.citruscollect.ui.theme.CitrusColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MyScreen(onQuestions: () -> Unit, onAbout: () -> Unit, onPrivacy: () -> Unit, onLogout: () -> Unit) {
    val repository = LocalAppContainer.current.authRepository
    val owner by repository.ownerKey.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var profile by remember(owner) { mutableStateOf<ProfileResponse?>(null) }
    var loading by remember(owner) { mutableStateOf(true) }
    var loadError by remember(owner) { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf(false) }
    var nickname by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var logout by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }
    var phoneDetails by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    suspend fun load() {
        loading = true; loadError = null
        try { profile = repository.profile() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadError = if (profile == null) "暂时无法读取个人资料，请重试" else "暂时无法更新，显示上次保存的资料" }
        finally { loading = false }
    }
    LaunchedEffect(owner) {
        if (owner != null) {
            profile = repository.cachedProfile()
            load()
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp)) {
            Text("柚见 · 田间助手", fontSize = 11.sp, color = CitrusColors.Muted)
            Text("我的", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = CitrusColors.Ink, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                SproutAvatar()
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(profile?.display_name ?: "我的账号", fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                        color = CitrusColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(profile?.phone_masked ?: if (profile?.phone_verified == true) "手机号已验证" else profile?.let { "账号：${it.username}" }.orEmpty(),
                        fontSize = 13.sp, color = CitrusColors.Muted, modifier = Modifier.padding(top = 5.dp))
                }
                IconButton(enabled = profile != null && !saving, onClick = { nickname = profile!!.display_name; saveError = null; edit = true }) {
                    Icon(Icons.Outlined.Edit, contentDescription = "修改昵称", tint = CitrusColors.Green, modifier = Modifier.size(20.dp))
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp), color = CitrusColors.Green)
            loadError?.let {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, fontSize = 12.sp, color = CitrusColors.Muted, modifier = Modifier.weight(1f))
                    TextButton(enabled = !loading, onClick = { scope.launch { load() } }) { Text("重试") }
                }
            }
            SectionLabel("个人资料")
            Surface(color = CitrusColors.White, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    ProfileRow(Icons.Outlined.Person, "昵称", profile?.display_name ?: "尚未读取", enabled = profile != null) {
                        nickname = profile!!.display_name; saveError = null; edit = true
                    }
                    HorizontalDivider(color = CitrusColors.Line)
                    ProfileRow(Icons.Outlined.Phone, if (profile?.phone_verified == true) "手机号" else "登录账号",
                        profile?.phone_masked ?: if (profile?.phone_verified == true) "已验证" else profile?.username.orEmpty(), enabled = profile != null) { phoneDetails = true }
                }
            }
            Spacer(Modifier.height(20.dp))
            SectionLabel("我的服务")
            Surface(color = CitrusColors.White, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    ProfileRow(Icons.Outlined.MailOutline, "我的咨询", "问题与专家答复", onClick = onQuestions)
                    HorizontalDivider(color = CitrusColors.Line)
                    ProfileRow(Icons.Outlined.Info, "关于应用", "v${BuildConfig.VERSION_NAME}", onClick = onAbout)
                    HorizontalDivider(color = CitrusColors.Line)
                    ProfileRow(Icons.Outlined.Lock, "隐私政策", onClick = onPrivacy)
                }
            }
            Text("图片与记录按账号保存，修改昵称不会影响记录。", fontSize = 11.sp, color = CitrusColors.Muted,
                lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 3.dp, vertical = 20.dp))
            OutlinedButton(enabled = !loggingOut, onClick = { logout = true }, shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CitrusColors.Line), colors = ButtonDefaults.outlinedButtonColors(containerColor = CitrusColors.White, contentColor = CitrusColors.Red),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Outlined.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text(if (loggingOut) "正在退出…" else "退出登录")
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    if (edit) AlertDialog(
        onDismissRequest = { if (!saving) edit = false },
        title = { Text("取一个喜欢的昵称") },
        text = {
            Column {
                Text("昵称会显示在个人资料中，账号和原有记录保持不变。", style = MaterialTheme.typography.bodyMedium, color = CitrusColors.Muted)
                OutlinedTextField(nickname, { nickname = it.take(18); saveError = null }, label = { Text("昵称") }, singleLine = true,
                    enabled = !saving, supportingText = { Text("${nickname.length}/18 · 2–18个字符") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                saveError?.let { Text(it, color = CitrusColors.Red, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = !saving && nickname.trim().length in 2..18, onClick = {
            scope.launch {
                saving = true; saveError = null
                try {
                    profile = repository.updateNickname(nickname)
                    edit = false
                    scope.launch { snackbar.showSnackbar("昵称已更新，原来的图片与记录都还在") }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { saveError = "保存失败，请检查网络后重试；修改内容已保留" }
                finally { saving = false }
            }
        }) { Text(if (saving) "保存中…" else "保存昵称") } },
        dismissButton = { TextButton(enabled = !saving, onClick = { edit = false }) { Text("取消") } }
    )
    if (phoneDetails) AlertDialog(onDismissRequest = { phoneDetails = false }, title = { Text(if (profile?.phone_verified == true) "登录手机号" else "登录账号") },
        text = { Column { Text(profile?.phone_masked ?: if (profile?.phone_verified == true) "手机号已验证" else profile?.username.orEmpty()); Text(if (profile?.phone_verified == true) "当前账号已通过手机号验证。" else "当前使用原有账号密码登录。", modifier = Modifier.padding(top = 12.dp)) } },
        confirmButton = { TextButton(onClick = { phoneDetails = false }) { Text("知道了") } })
    if (logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text("退出当前账号？") },
        text = { Text("本地图片与记录会保留，后台上传将暂停。重新登录相同账号后可以继续查看。") },
        confirmButton = { TextButton(onClick = { logout = false; loggingOut = true; onLogout() }) { Text("退出登录", color = CitrusColors.Red) } },
        dismissButton = { TextButton(onClick = { logout = false }) { Text("取消") } })
}

@Composable
private fun SproutAvatar() {
    Surface(color = CitrusColors.GreenSoft, shape = RoundedCornerShape(24.dp), modifier = Modifier.size(66.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_profile_sprout), contentDescription = null, tint = CitrusColors.Green, modifier = Modifier.size(34.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) { Text(text, fontSize = 11.sp, color = CitrusColors.Muted, modifier = Modifier.padding(start = 2.dp, bottom = 10.dp)) }

@Composable
private fun ProfileRow(icon: ImageVector, title: String, value: String = "", enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = CitrusColors.Green, modifier = Modifier.size(21.dp))
        Text(title, fontSize = 14.sp, color = CitrusColors.Ink, modifier = Modifier.weight(1f).padding(start = 12.dp))
        if (value.isNotEmpty()) Text(value, fontSize = 11.sp, color = CitrusColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp).padding(start = 6.dp))
        Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = null, tint = CitrusColors.Muted, modifier = Modifier.padding(start = 4.dp).size(17.dp))
    }
}

@Composable
fun AboutAppScreen(onPrivacy: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(30.dp)); SproutAvatar()
        Text("柚见 · 田间助手", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = CitrusColors.Ink, modifier = Modifier.padding(top = 24.dp))
        Text("识别、采集、记录田间观察", color = CitrusColors.Muted, modifier = Modifier.padding(top = 10.dp, bottom = 28.dp))
        Surface(color = CitrusColors.White, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("当前版本"); Text(BuildConfig.VERSION_NAME, color = CitrusColors.Muted) }
                HorizontalDivider(color = CitrusColors.Line)
                ProfileRow(Icons.Outlined.Lock, "隐私政策", onClick = onPrivacy)
            }
        }
    }
}

@Composable
fun PrivacyInfoScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("隐私与数据说明", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = CitrusColors.Ink)
        PrivacySection("账号资料", "手机号用于短信验证登录，验证码由阿里云号码认证服务提供。个人资料中仅展示脱敏手机号。昵称用于账号展示，修改昵称不会改变记录归属。")
        PrivacySection("照片与现场信息", "识别照片会发送到服务端临时处理。采集原图、标注与咨询按你选择的操作上传；定位需要主动授权，不授权仍可继续采集。")
        PrivacySection("退出与本地记录", "退出登录会清除当前登录凭据，保留本机图片与记录。其他账号不会看到当前账号的本地记录。重新登录相同账号后可继续使用。")
        PrivacySection("本地数据管理", "未上传的照片依赖本机保存。清除应用数据或卸载应用可能移除这些记录，请先确认需要保留的数据已保存。")
        Text("以上说明对应当前测试版本的数据使用方式；正式发布前会补充完整的隐私政策与联系渠道。", style = MaterialTheme.typography.bodySmall, color = CitrusColors.Muted)
    }
}

@Composable
private fun PrivacySection(title: String, text: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = CitrusColors.Ink)
    Text(text, style = MaterialTheme.typography.bodyMedium, lineHeight = 25.sp, color = CitrusColors.Muted)
}
