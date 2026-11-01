package com.example.citruscollect

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.example.citruscollect.data.AppDatabase
import com.example.citruscollect.network.Backend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Local-only regression of the user-visible batch confirmation flow. No upload or SMS. */
@RunWith(AndroidJUnit4::class)
class CollectionStatusLocalTest {
    @Test fun capturedPhotoNeedsConfirmationAndRealMissingFieldIsNamed(): Unit = runBlocking {
        check(BuildConfig.LOCAL_DEBUG) { "This test only runs in the isolated local debug app" }
        check(Backend.DEFAULT_BASE_URL == "http://10.0.2.2:18081/api/v1/")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as CitrusApplication).container
        container.authRepository.login(Backend.DEFAULT_BASE_URL, "collector1", "12345678")
        val repository = container.repository
        val batch = repository.createBatch("本地状态验收", "本地测试果园", "调试采集员", "huyou")
        container.batchId = batch
        val file = File(context.filesDir, "status-check-${System.currentTimeMillis()}.png")
        val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(50, 110, 70))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val id = repository.capturePhoto(file, "image/png", 160, 120, batchId = batch, cultivarId = "huyou")
        val device = UiDevice.getInstance(instrumentation)
        fun click(text: String) {
            repeat(3) {
                device.waitForIdle()
                try {
                    val node = device.wait(Until.findObject(By.text(text)), 15000)
                    requireNotNull(node) { "Missing local UI: $text" }.click()
                    return
                } catch (_: androidx.test.uiautomator.StaleObjectException) {
                    // Navigation/Room recomposition replaced this node; acquire a fresh observation.
                }
            }
            error("Local UI remained unstable: $text")
        }
        fun openRecords() {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click("进入数据采集 →"); click("图片记录与上传")
        }
        openRecords()
        assertTrue(device.wait(Until.hasObject(By.text("待确认")), 15000))
        assertFalse(device.hasObject(By.text("待完善")))
        assertTrue(device.hasObject(By.text("请确认采集信息后保存")))
        click("去确认")
        assertTrue(device.wait(Until.hasObject(By.text("待确认：请核对品种、部位和类别，确认后保存。")), 15000))
        click("保存信息")
        assertTrue(device.wait(Until.hasObject(By.text("待上传")), 15000))
        assertEquals("pending", repository.sampleById(id).first()?.uploadState)
        assertTrue(file.exists())
        val dao = AppDatabase.get(context).sampleDao()
        dao.update(requireNotNull(dao.byId(id)).copy(classId = ""))
        assertTrue(device.wait(Until.hasObject(By.text("待完善")), 15000))
        assertTrue(device.hasObject(By.text("缺少：类别")))
        var rejected = false
        try { repository.enqueueUpload(id) } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        assertEquals("pending", repository.sampleById(id).first()?.uploadState)
        click("去完善")
        click("保存信息")
        assertTrue(device.wait(Until.hasObject(By.text("待上传")), 15000))
        assertTrue(file.exists())
        assertEquals("pending", repository.sampleById(id).first()?.uploadState)
        device.takeScreenshot(File(context.getExternalFilesDir(null), "local-confirmation-status.png"))
        println("LOCAL_CONFIRMATION_MISSING_FIELDS_AND_DATA_PRESERVATION_OK")
    }
}
