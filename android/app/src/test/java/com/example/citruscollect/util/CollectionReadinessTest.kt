package com.example.citruscollect.util

import org.junit.Assert.*
import org.junit.Test

class CollectionReadinessTest {
    @Test fun capturedPhotoWithUnknownLabelsNeedsConfirmationRatherThanCompletion() {
        val result = CollectionReadiness.status("draft", "unknown", "leaf", "unknown", true)
        assertEquals("待确认", result.label)
        assertFalse(result.incomplete)
        assertTrue(result.needsConfirmation)
        assertEquals("请确认采集信息后保存", result.detail)
    }
    @Test fun onlyActuallyMissingRequiredFieldsAreReported() {
        val result = CollectionReadiness.status("draft", "", "leaf", "", true)
        assertEquals("待完善", result.label)
        assertEquals("缺少：品种、类别", result.detail)
        assertTrue(result.incomplete)
    }
    @Test fun photoLoadingIsNotMistakenForMissingPhoto() {
        assertEquals("待确认", CollectionReadiness.status("draft", "huyou", "leaf", "unknown", null).label)
        assertEquals("缺少：照片", CollectionReadiness.status("draft", "huyou", "leaf", "unknown", false).detail)
    }
    @Test fun confirmedCompleteRecordRemainsReadyForUpload() {
        val result = CollectionReadiness.status("pending", "huyou", "leaf", "unknown", true)
        assertEquals("待上传", result.label)
        assertFalse(result.needsConfirmation)
        assertNull(result.detail)
        assertEquals("待完善", CollectionReadiness.status("pending", "huyou", "", "unknown", true).label)
    }
    @Test fun confirmedRemoteAndInFlightStatesAreNotReclassifiedByLocalPhotoAbsence() {
        assertEquals("已上传", CollectionReadiness.status("uploaded", "", "", "", false).label)
        assertEquals("等待后台上传", CollectionReadiness.status("queued", "huyou", "leaf", "unknown", false).label)
        assertEquals("上传中", CollectionReadiness.status("uploading", "huyou", "leaf", "unknown", false).label)
        assertEquals("需补拍", CollectionReadiness.status("needs_photos", "huyou", "leaf", "unknown", false).label)
    }
}
