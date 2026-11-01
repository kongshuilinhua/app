package com.example.citruscollect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.citruscollect.ui.screens.*
import com.example.citruscollect.ui.screens.BatchesScreen
import com.example.citruscollect.ui.screens.CaptureHomeScreen
import com.example.citruscollect.ui.screens.CaptureSessionScreen
import com.example.citruscollect.ui.screens.ConfirmScreen
import com.example.citruscollect.ui.screens.LoginScreen
import com.example.citruscollect.ui.screens.QuestionScreen
import com.example.citruscollect.ui.screens.RecordsScreen
import com.example.citruscollect.ui.theme.CitrusColors
import kotlinx.coroutines.launch

@Composable
fun AppNav() {
    val container = LocalAppContainer.current
    val token by container.sessionStore.token.collectAsState(initial = null)
    val ownerKey by container.authRepository.ownerKey.collectAsState(initial = null)

    if (token == null) {
        LoginScreen()
    } else if (ownerKey != null) {
        key(ownerKey) { MainNav() }
    }
}

private val TABS = listOf(
    Triple("home", Icons.Outlined.Home, "首页"), Triple("identify", Icons.Outlined.Search, "拍照识别"),
    Triple("collection", Icons.Outlined.Create, "数据采集"), Triple("history", Icons.Outlined.List, "记录"),
    Triple("mine", Icons.Outlined.Person, "我的")
)

@Composable
private fun MainNav() {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tabRoute = when {
        route?.startsWith("confirm") == true -> "collection"
        route in setOf("capture", "records", "batches") -> "collection"
        route in setOf("questions", "about", "privacy") -> "mine"
        else -> route
    }

    fun openTab(target: String) {
        nav.navigate(target) {
            launchSingleTop = true
            popUpTo("home") { saveState = true }
            restoreState = true
        }
    }

    Scaffold(
        containerColor = CitrusColors.Screen,
        bottomBar = {
            if (route in setOf("home", "identify", "collection", "history", "mine")) {
                Column(Modifier.background(CitrusColors.White).navigationBarsPadding()) {
                    HorizontalDivider(color = CitrusColors.Line)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TABS.forEach { (key, icon, label) ->
                            val active = tabRoute == key
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f).clickable { openTab(key) }.padding(vertical = 4.dp)
                            ) {
                                Box(
                                    Modifier.clip(RoundedCornerShape(10.dp))
                                        .background(if (active) Color(0xFFE8EFE4) else Color.Transparent)
                                        .padding(horizontal = 16.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp),
                                        tint = if (active) CitrusColors.Green else Color(0xFF94A08F))
                                }
                                Text(label, fontSize = 10.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = if (active) CitrusColors.Green else Color(0xFF94A08F),
                                    modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") { DualHomeScreen(onIdentify = { openTab("identify") }, onCollection = { openTab("collection") }, onHistory = { openTab("history") }) }
            composable("collection") { CollectionWorkspaceScreen(onCapture = { nav.navigate("capture") }, onRecords = { nav.navigate("records") }, onBatches = { nav.navigate("batches") }) }
            composable("identify") { entry ->
                val photo by entry.savedStateHandle.getStateFlow<String?>("diagnosisPhoto", null).collectAsState()
                IdentifyScreen(photoPath = photo, onPhotoConsumed = { entry.savedStateHandle["diagnosisPhoto"] = null },
                    onCamera = { nav.navigate("diagnosis-camera") }, onReport = { nav.navigate("diagnosis/$it") })
            }
            composable("diagnosis-camera") {
                DiagnosisCameraScreen(onCaptured = { file ->
                    nav.previousBackStackEntry?.savedStateHandle?.set("diagnosisPhoto", file.path)
                    nav.popBackStack()
                }, onBack = { nav.popBackStack() })
            }
            composable("diagnosis/{id}") { entry ->
                DiagnosisReportScreen(id = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() },
                    onNewPhoto = { nav.navigate("identify") { popUpTo("home"); launchSingleTop = true } },
                    onCollectionRecord = { nav.navigate("confirm/$it") })
            }
            composable("history") {
                DiagnosisHistoryScreen(onOpen = { nav.navigate("diagnosis/$it") }, onIdentify = { openTab("identify") })
            }
            composable("mine") {
                MyScreen(onQuestions = { nav.navigate("questions") }, onAbout = { nav.navigate("about") },
                    onPrivacy = { nav.navigate("privacy") },
                    onLogout = { container.appScope.launch { container.authRepository.logout() } })
            }
            composable("about") { CollectionSubpage(onBack = { nav.popBackStack() }) { AboutAppScreen(onPrivacy = { nav.navigate("privacy") }) } }
            composable("privacy") { CollectionSubpage(onBack = { nav.popBackStack() }) { PrivacyInfoScreen() } }
            composable("capture") {
                CollectionSubpage(onBack = { nav.popBackStack() }) { CaptureHomeScreen(
                    onStartCapture = { nav.navigate("shoot") },
                    onOpenRecords = { nav.navigate("records") },
                    onOpenBatches = { nav.navigate("batches") }
                ) }
            }
            composable("records") {
                CollectionSubpage(onBack = { nav.popBackStack() }) { RecordsScreen(
                    onOpenRecord = { nav.navigate("confirm/$it") },
                    onCapture = { nav.navigate("shoot") },
                    onOpenBatches = { nav.navigate("batches") },
                    onAccount = { openTab("mine") }
                ) }
            }
            composable("questions") { CollectionSubpage(onBack = { nav.popBackStack() }) { QuestionScreen() } }
            composable("batches") {
                CollectionSubpage(onBack = { nav.popBackStack() }) { BatchesScreen(onCapture = { nav.navigate("shoot") }) }
            }
            composable(
                "shoot?retakeSample={retakeSample}&retake={retake}",
                arguments = listOf(
                    navArgument("retakeSample") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("retake") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { entry ->
                val retakeSample = entry.arguments?.getString("retakeSample")?.takeIf { it.isNotBlank() }
                CaptureSessionScreen(
                    retakeSampleId = retakeSample,
                    retakeImageId = entry.arguments?.getString("retake")?.takeIf { it.isNotBlank() },
                    onCaptured = { nav.popBackStack() },
                    onOpenRecord = { nav.navigate("confirm/$it") },
                    onDone = { nav.popBackStack() },
                    onBack = { nav.popBackStack() }
                )
            }
            composable("confirm/{sampleId}") { entry ->
                ConfirmScreen(
                    sampleId = entry.arguments?.getString("sampleId").orEmpty(),
                    onOpenRecord = { nav.navigate("confirm/$it") },
                    onRecordDeleted = {
                        if (!nav.popBackStack("records", false) && !nav.popBackStack("collection", false)) {
                            nav.popBackStack()
                        }
                    },
                    onBack = { nav.popBackStack() },
                    onRetake = { imageId ->
                        val sampleId = entry.arguments?.getString("sampleId").orEmpty()
                        nav.navigate("shoot?retakeSample=$sampleId" + (imageId?.let { "&retake=$it" } ?: ""))
                    },
                    onSaved = {
                        if (!nav.popBackStack("records", false)) {
                            nav.navigate("records") { popUpTo("collection") { inclusive = false } }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun CollectionSubpage(onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 12.dp)) { Text("← 返回") }
        Box(Modifier.weight(1f)) { content() }
    }
}
