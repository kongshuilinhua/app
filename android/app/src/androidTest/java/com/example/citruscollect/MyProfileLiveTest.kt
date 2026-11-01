package com.example.citruscollect

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real profile UI verification; explicit configured test account only, nickname restored afterwards. */
@RunWith(AndroidJUnit4::class)
class MyProfileLiveTest {
    @Test fun editConsultAboutLogoutAndRetainRecords(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as CitrusApplication).container
        val args = InstrumentationRegistry.getArguments()
        val base = requireNotNull(args.getString("baseUrl"))
        val username = requireNotNull(args.getString("collectorUsername"))
        val password = requireNotNull(args.getString("collectorPassword"))
        val device = UiDevice.getInstance(instrumentation)
        container.authRepository.login(base, username, password)
        val original = container.authRepository.profile()
        val owner = container.authRepository.currentOwnerKey()
        val records = container.diagnosisRepository.observeAll().first().map { it.id }.toSet()
        val collectionRecords = container.repository.observePending().first().map { it.sampleId }.toSet()
        fun launch() = context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        fun openMy() {
            assertTrue(device.wait(Until.hasObject(By.text("我的")), 15000))
            device.findObject(By.text("我的")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("修改昵称")), 15000))
        }
        try {
            launch(); openMy()
            assertTrue(device.wait(Until.hasObject(By.text(original.display_name)), 20000))
            device.findObject(By.desc("修改昵称")).click()
            assertTrue(device.wait(Until.hasObject(By.text("取一个喜欢的昵称")), 5000))
            device.findObject(By.clazz("android.widget.EditText")).text = "橘园观察员"
            device.findObject(By.text("保存昵称")).click()
            assertTrue(device.wait(Until.gone(By.text("取一个喜欢的昵称")), 20000))
            assertTrue(device.hasObject(By.text("橘园观察员")))
            val changed = container.authRepository.profile()
            assertEquals(original.user_id, changed.user_id)
            assertEquals(original.username, changed.username)
            assertEquals("橘园观察员", changed.display_name)
            assertEquals(owner, container.authRepository.currentOwnerKey())
            device.findObject(By.desc("修改昵称")).click()
            device.findObject(By.clazz("android.widget.EditText")).text = "不保存的修改"
            device.findObject(By.text("取消")).click()
            assertTrue(device.hasObject(By.text("橘园观察员")))
            device.findObject(By.text("我的咨询")).click()
            assertTrue(device.wait(Until.hasObject(By.text("专家咨询")), 10000)); device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("关于应用")), 10000))
            device.findObject(By.text("关于应用")).click()
            assertTrue(device.wait(Until.hasObject(By.text(BuildConfig.VERSION_NAME)), 10000))
            device.findObject(By.text("隐私政策")).click()
            assertTrue(device.wait(Until.hasObject(By.text("隐私与数据说明")), 10000))
            device.pressBack(); device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("退出登录")), 10000))
            device.findObject(By.text("退出登录")).click()
            assertTrue(device.wait(Until.hasObject(By.text("退出当前账号？")), 5000))
            device.findObject(By.text("取消")).click()
            assertNotNull(container.sessionStore.accessToken())
            val access = requireNotNull(container.sessionStore.accessToken())
            val service = container.backend.servicesFor(base)
            device.findObject(By.text("退出登录")).click()
            assertTrue(device.wait(Until.hasObject(By.text("退出当前账号？")), 5000))
            val dialogs = device.findObjects(By.text("退出登录"))
            dialogs.last().click()
            assertTrue(device.wait(Until.hasObject(By.text(if (BuildConfig.LOCAL_DEBUG) "账号密码登录" else "手机号登录")), 10000))
            assertNull(container.sessionStore.accessToken())
            withTimeout(15000) {
                while (true) {
                    try { service.auth.me("Bearer $access") }
                    catch (e: retrofit2.HttpException) { if (e.code() == 401) break else throw e }
                    delay(250)
                }
            }
            container.authRepository.login(base, username, password)
            assertEquals(owner, container.authRepository.currentOwnerKey())
            assertEquals(records, container.diagnosisRepository.observeAll().first().map { it.id }.toSet())
            assertEquals(collectionRecords, container.repository.observePending().first().map { it.sampleId }.toSet())
        } finally {
            if (container.sessionStore.accessToken() == null) container.authRepository.login(base, username, password)
            if (original.display_name.length in 2..18) container.authRepository.updateNickname(original.display_name)
        }
        launch();openMy()
        assertTrue(device.wait(Until.hasObject(By.text(original.display_name)), 15000))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "my-page-implemented.png"))
        println("PROFILE_UI_EDIT_CONSULT_ABOUT_LOGOUT_RECORDS_OK")
    }
}
