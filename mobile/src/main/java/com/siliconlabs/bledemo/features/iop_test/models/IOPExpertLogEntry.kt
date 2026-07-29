package com.siliconlabs.bledemo.features.iop_test.models

data class IOPExpertLogEntry(
    var timestamp: String,
    val category: String,
    val title: String,
    val detail: String?,
    val tone: String,
    var repeatCount: Int = 1
) {
    val isMilestone: Boolean
        get() = tone == "session" || tone == "test" || category == "SCENARIO"

    fun canCollapseWith(other: IOPExpertLogEntry): Boolean {
        return !isMilestone &&
            !other.isMilestone &&
            category == other.category &&
            title == other.title &&
            detail == other.detail &&
            tone == other.tone
    }
}
