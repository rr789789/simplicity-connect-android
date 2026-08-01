package com.zg.sensormonitor.ota

import android.content.Context
import android.net.Uri
import com.zg.sensormonitor.ble.BleCentralManager
import com.zg.sensormonitor.data.AuditStore
import com.zg.sensormonitor.domain.*
import com.zg.sensormonitor.protocol.DeviceProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.net.HttpURLConnection
import java.net.URL

sealed interface FirmwareSource {
    data class Local(val uri: Uri) : FirmwareSource
    data class Https(val url: String) : FirmwareSource
}

class OtaEngine(
    private val context: Context,
    private val ble: BleCentralManager,
    private val audit: AuditStore
) {
    suspend fun upgrade(source: FirmwareSource, onState: (OtaState) -> Unit): String? {
        val original = ble.activeDevice ?: error("没有已连接设备")
        audit.record("OTA", "开始", original.address, if (source is FirmwareSource.Local) "本地文件" else "HTTPS下载")
        try {
            onState(OtaState.Preparing("正在读取固件"))
            val image = withContext(Dispatchers.IO) { load(source) }
            require(image.size >= 16) { "固件文件为空或无效" }
            val before = runCatching { readVersion() }.getOrNull()
            onState(OtaState.Preparing("正在进入DFU"))
            ble.setDfuMode(true)
            runCatching { ble.requestMtu(247) }
            delay(OTA_CONTROL_START_DELAY_MS)
            ble.write(DeviceProtocol.OTA_SERVICE, DeviceProtocol.OTA_CONTROL, byteArrayOf(0))
            delay(1200)
            ble.disconnect()

            val otaDevice = findDevice(original, DeviceKind.OTA)
            connectAndWait(otaDevice)
            runCatching { ble.requestMtu(247) }
            delay(OTA_CONTROL_START_DELAY_MS)
            // Silicon Labs OTA sequence starts the upload with 0x00 in both normal and DFU mode.
            ble.write(DeviceProtocol.OTA_SERVICE, DeviceProtocol.OTA_CONTROL, byteArrayOf(0))
            delay(OTA_CONTROL_START_DELAY_MS)

            var sent = 0
            while (sent < image.size) {
                val end = (sent + ble.writePayloadSize).coerceAtMost(image.size)
                writeOtaPacket(image.copyOfRange(sent, end))
                sent = end
                delay(OTA_PACKET_DELAY_MS)
                onState(OtaState.Transferring(5 + sent * 90 / image.size, sent.toLong(), image.size.toLong()))
            }

            onState(OtaState.Verifying("Bootloader正在校验并重启"))
            ble.write(DeviceProtocol.OTA_SERVICE, DeviceProtocol.OTA_CONTROL, byteArrayOf(3))
            delay(OTA_CONTROL_END_DELAY_MS)
            ble.disconnect()
            val normal = findDevice(original, original.kind)
            connectAndWait(normal)
            val after = runCatching { readVersion() }.getOrNull()
            if (!before.isNullOrBlank() && !after.isNullOrBlank() && before == after) {
                error("设备已重启，但固件版本未变化($after)")
            }
            ble.setDfuMode(false)
            onState(OtaState.Complete(after))
            audit.record("OTA", "成功", original.address, "before=$before after=$after size=${image.size}")
            return after
        } catch (e: Exception) {
            ble.setDfuMode(false)
            onState(OtaState.Failed(e.message ?: "升级失败"))
            audit.record("OTA", "失败", original.address, e.message)
            throw e
        }
    }

    private suspend fun connectAndWait(device: DiscoveredDevice) {
        val complete = CompletableDeferred<Unit>()
        val observer = object : BleCentralManager.Listener {
            override fun onPhase(phase: LinkPhase, message: String?) {
                if (phase == LinkPhase.ONLINE && !complete.isCompleted) complete.complete(Unit)
                if (phase == LinkPhase.FAULT && !complete.isCompleted) complete.completeExceptionally(IllegalStateException(message ?: "连接失败"))
            }
        }
        ble.addListener(observer)
        try {
            ble.connect(device)
            withTimeout(20_000) { complete.await() }
        } finally { ble.removeListener(observer) }
    }

    private suspend fun findDevice(original: DiscoveredDevice, expectedKind: DeviceKind): DiscoveredDevice {
        val exact = CompletableDeferred<DiscoveredDevice>()
        val candidates = linkedMapOf<String, DiscoveredDevice>()
        val observer = object : BleCentralManager.Listener {
            override fun onDevice(device: DiscoveredDevice) {
                val sensorFamily = setOf(DeviceKind.PRESSURE, DeviceKind.TILT, DeviceKind.SENSOR)
                val compatible = device.kind == expectedKind || (expectedKind in sensorFamily && device.kind in sensorFamily)
                if (!compatible) return
                candidates[device.address] = device
                if (device.address.equals(original.address, true) && !exact.isCompleted) exact.complete(device)
            }
        }
        ble.addListener(observer)
        try {
            ble.startScan()
            repeat(6) {
                delay(1000)
                if (exact.isCompleted) return exact.await()
            }
            if (candidates.size == 1) return candidates.values.first()
            if (candidates.isEmpty()) throw IllegalStateException("未找到升级设备")
            throw IllegalStateException("发现多个升级设备，无法确认设备身份")
        } finally {
            ble.stopScan()
            ble.removeListener(observer)
        }
    }

    private suspend fun readVersion(): String =
        ble.read(DeviceProtocol.DEVICE_INFO_SERVICE, DeviceProtocol.FIRMWARE_REVISION)
            .toString(Charsets.UTF_8).trim('\u0000', ' ', '\r', '\n')

    private suspend fun writeOtaPacket(packet: ByteArray) {
        var lastError: Throwable? = null
        repeat(50) {
            runCatching { ble.write(DeviceProtocol.OTA_SERVICE, DeviceProtocol.OTA_DATA, packet, withResponse = false) }
                .onSuccess { return }
                .onFailure { lastError = it }
            delay(OTA_PACKET_DELAY_MS)
        }
        throw lastError ?: IllegalStateException("OTA数据写入失败")
    }

    private fun load(source: FirmwareSource): ByteArray = when (source) {
        is FirmwareSource.Local -> context.contentResolver.openInputStream(source.uri)?.use { input ->
            readLimited(input.readBytes())
        } ?: error("无法读取本地固件")
        is FirmwareSource.Https -> download(source.url)
    }

    private fun download(value: String): ByteArray {
        var url = URL(value)
        require(url.protocol.equals("https", true)) { "仅支持HTTPS固件地址" }
        repeat(5) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = false
            }
            connection.connect()
            if (connection.responseCode in 300..399) {
                val location = connection.getHeaderField("Location") ?: error("固件下载重定向无地址")
                connection.disconnect()
                url = URL(url, location)
                require(url.protocol.equals("https", true)) { "固件下载重定向到非HTTPS地址" }
            } else {
                require(connection.responseCode in 200..299) { "固件下载失败(${connection.responseCode})" }
                return connection.inputStream.use { readLimited(it.readBytes()) }.also { connection.disconnect() }
            }
        }
        error("固件下载重定向次数过多")
    }

    private fun readLimited(bytes: ByteArray): ByteArray {
        require(bytes.size <= MAX_FIRMWARE_SIZE) { "固件超过允许大小" }
        return bytes
    }

    companion object {
        private const val MAX_FIRMWARE_SIZE = 8 * 1024 * 1024
        private const val OTA_CONTROL_START_DELAY_MS = 200L
        private const val OTA_CONTROL_END_DELAY_MS = 500L
        private const val OTA_PACKET_DELAY_MS = 1L
    }
}
