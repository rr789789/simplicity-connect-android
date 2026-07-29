package com.siliconlabs.bledemo.features.iop_test.activities

import com.siliconlabs.bledemo.features.iop_test.models.IOPExpertLogEntry

interface IOPExpertListener {
    fun appendLogEntry(entry: IOPExpertLogEntry)
    fun restoreLog(entries: List<IOPExpertLogEntry>)
    fun clearLog()
}
