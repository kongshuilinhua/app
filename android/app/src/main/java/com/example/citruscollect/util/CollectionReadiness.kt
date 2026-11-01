package com.example.citruscollect.util

/** Presentation of local confirmation readiness; "unknown" is a valid explicit choice. */
object CollectionReadiness {
    data class Status(val label: String, val detail: String? = null, val incomplete: Boolean = false, val needsConfirmation: Boolean = false)

    fun missingFields(cultivarId: String, part: String, classId: String, hasPhoto: Boolean? = null): List<String> = buildList {
        if (hasPhoto == false) add("照片")
        if (cultivarId.isBlank()) add("品种")
        if (part.isBlank()) add("部位")
        if (classId.isBlank()) add("类别")
    }

    fun status(state: String, cultivarId: String, part: String, classId: String, hasPhoto: Boolean? = null): Status {
        if (state in setOf("draft", "pending")) {
            val missing = missingFields(cultivarId, part, classId, hasPhoto)
            if (missing.isNotEmpty()) return Status("待完善", "缺少：${missing.joinToString("、")}", incomplete = true, needsConfirmation = true)
            if (state == "draft") return Status("待确认", "请确认采集信息后保存", needsConfirmation = true)
        }
        return when (state) {
            "uploaded" -> Status("已上传")
            "queued", "retrying" -> Status("等待后台上传")
            "uploading" -> Status("上传中")
            "needs_photos" -> Status("需补拍", "请补拍照片后再确认信息")
            "failed" -> Status("上传失败")
            else -> Status("待上传")
        }
    }
}
