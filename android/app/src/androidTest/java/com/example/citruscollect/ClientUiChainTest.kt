package com.example.citruscollect

import com.example.citruscollect.network.Backend
import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** One real UI flow through CameraX and Room to the configured service. */
@RunWith(AndroidJUnit4::class)
class ClientUiChainTest {
    @get:Rule val cameraPermission = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test fun loginCaptureConfirmUpload() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as CitrusApplication
        val args = InstrumentationRegistry.getArguments()
        val baseUrl = requireNotNull(args.getString("baseUrl"))
        val username = requireNotNull(args.getString("collectorUsername"))
        val password = requireNotNull(args.getString("collectorPassword"))
        val device = UiDevice.getInstance(instrumentation)
        runBlocking { app.container.authRepository.logout() }
        val oldIds = runBlocking { app.container.repository.observePending().first().map { it.sampleId }.toSet() }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue("Login form", device.wait(Until.hasObject(By.text("账号密码登录")), 15000))
        assertFalse("Service URL is not exposed", device.hasObject(By.text("服务地址")))
        if (baseUrl.trimEnd('/') == Backend.DEFAULT_BASE_URL.trimEnd('/')) {
            device.findObject(By.text("账号密码登录")).click()
            val fields = device.findObjects(By.clazz("android.widget.EditText"))
            assertEquals("Password login fields", 2, fields.size)
            fields[0].text = username
            fields[1].text = password
            device.findObject(By.text("登录")).click()
        } else {
            // Integration fixtures choose their service through the repository, not the user-facing form.
            runBlocking { app.container.authRepository.login(baseUrl, username, password) }
        }
        assertTrue("Real login", device.wait(Until.hasObject(By.text("开始采集")), 30000))
        device.findObject(By.text("开始采集")).click()
        assertTrue("Capture screen", device.wait(Until.hasObject(By.text("完成拍摄")), 15000))
        // CameraX asynchronously binds its surface after the screen appears.
        assertTrue("Camera ready", device.wait(Until.hasObject(By.text("相机已就绪")), 30000))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "client-before-shutter.xml"))
        var shutter = device.findObject(By.text("拍摄"))
        while (shutter != null && !shutter.isClickable) shutter = shutter.parent
        requireNotNull(shutter) { "No clickable shutter" }.click()
        val saved = device.wait(Until.hasObject(By.text("拍摄（已拍 1 张）")), 30000)
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "client-after-shutter.xml"))
        assertTrue("CameraX saved photo to Room", saved)
        val sample = runBlocking { app.container.repository.observePending().first { list -> list.any { it.sampleId !in oldIds } }.first { it.sampleId !in oldIds } }
        device.findObject(By.text("完成拍摄")).click()
        assertTrue("Confirmation", device.wait(Until.hasObject(By.text("编辑图片")), 10000))
        assertTrue("Bootstrap dictionary", device.wait(Until.hasObject(By.text("胡柚")), 20000))
        if (!device.hasObject(By.text("保存信息"))) {
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("保存信息"))
        }
        device.findObject(By.text("保存信息")).click()
        assertTrue("Upload queue", device.wait(Until.hasObject(By.text("上传")), 10000))
        device.findObject(By.text("上传")).click()
        assertTrue("Server completion receipt", device.wait(Until.hasObject(By.textStartsWith("上传完成：")), 60000))
        val stored = runBlocking { app.container.repository.sampleById(sample.sampleId).first() }
        assertEquals("uploaded", stored?.uploadState)
        val image = runBlocking { app.container.repository.imagesOf(sample.sampleId).first().single() }
        File(context.getExternalFilesDir(null), "client-ui-result.json").writeText(JSONObject().put("sample_id",sample.sampleId).put("image_id",image.imageId).put("sha256",image.sha256).put("ui_flow",true).toString())
        device.takeScreenshot(File(context.getExternalFilesDir(null), "client-ui-complete.png"))
    }
}
