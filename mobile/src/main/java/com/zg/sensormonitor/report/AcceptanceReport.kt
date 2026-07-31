package com.zg.sensormonitor.report

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.zg.sensormonitor.domain.ReceiverStatus
import com.zg.sensormonitor.domain.SlotState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AcceptanceReport {
    fun create(context: Context, device: String, status: ReceiverStatus?, slots: List<SlotState>): List<File> {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
        val base = "acceptance-$stamp"
        val json = File(dir, "$base.json")
        val boundSlots = slots.filter { it.binding != null }
        val passed = boundSlots.isNotEmpty() && boundSlots.all { slot ->
            slot.reading != null && !slot.stale && status?.online(slot.slot) == true && status.valid(slot.slot)
        }
        json.writeText(JSONObject().apply {
            put("generatedAt", System.currentTimeMillis()); put("device", device); put("receiverId", status?.receiverId)
            put("passed", passed)
            put("slots", JSONArray().apply { slots.forEach { slot -> put(JSONObject().apply {
                put("slot", slot.slot + 1); put("mac", slot.binding?.mac); put("type", slot.binding?.type)
                put("sensorId", slot.binding?.sensorId); put("online", status?.online(slot.slot) == true); put("valid", status?.valid(slot.slot) == true); put("stale", slot.stale)
            }) } })
        }.toString(2))

        val pdf = File(dir, "$base.pdf")
        PdfDocument().use { document ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val canvas = page.canvas
            val title = Paint().apply { textSize = 22f; isFakeBoldText = true }
            val text = Paint().apply { textSize = 12f }
            canvas.drawText("矿用传感器配置验收报告", 40f, 55f, title)
            canvas.drawText("设备: $device", 40f, 88f, text)
            canvas.drawText("接收器ID: ${status?.receiverId ?: "--"}", 40f, 108f, text)
            var y = 145f
            slots.forEach { slot ->
                val result = when { slot.binding == null -> "未绑定"; status?.online(slot.slot) != true -> "离线"; status?.valid(slot.slot) != true -> "无效"; slot.reading == null -> "无数据"; slot.stale -> "数据超时"; else -> "通过" }
                canvas.drawText("槽${slot.slot + 1}  ${slot.binding?.mac ?: "--"}  $result", 40f, y, text); y += 25f
            }
            document.finishPage(page); pdf.outputStream().use(document::writeTo)
        }
        return listOf(pdf, json)
    }
}
