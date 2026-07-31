package com.zg.sensormonitor.domain

enum class DeviceKind { RECEIVER, PRESSURE, TILT, SENSOR, OTA }

enum class LinkPhase {
    IDLE, SCANNING, CONNECTING, DISCOVERING, SUBSCRIBING, ONLINE,
    STALE, RECONNECTING, DISCONNECTING, DFU, FAULT
}

data class DiscoveredDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val kind: DeviceKind,
    val lastSeen: Long = System.currentTimeMillis()
)

data class ReceiverStatus(
    val version: Int,
    val receiverId: Int,
    val boundMask: Int,
    val onlineMask: Int,
    val validMask: Int,
    val slots: Int,
    val fastMask: Int
) {
    fun bound(slot: Int) = boundMask and (1 shl slot) != 0
    fun online(slot: Int) = onlineMask and (1 shl slot) != 0
    fun valid(slot: Int) = validMask and (1 shl slot) != 0
}

data class SensorPayload(
    val uptime: Long,
    val uptime24: Int,
    val temperature: Int,
    val pressure: Int,
    val raw: Long,
    val sequence: Int,
    val notifyFlags: Int?,
    val pa5: Boolean?,
    val readOk: Boolean?,
    val voltageV2: Double?,
    val voltageV1: Double?,
    val xAngle: Int,
    val yAngle: Int,
    val xRaw: Int,
    val yRaw: Int,
    val zRaw: Int,
    val receivedAt: Long = System.currentTimeMillis()
)

data class StreamReading(val slot: Int, val payload: SensorPayload)

data class SensorInfo(
    val workSeconds: Long,
    val bootSeconds: Long,
    val voltageMv: Int,
    val online: Boolean,
    val rate: Int,
    val errors: Int,
    val version: Int,
    val sensorType: Int,
    val pa5: Int?,
    val powerMode: Int,
    val rssi: Int?,
    val infoValid: Boolean?,
    val dataTimedOut: Boolean?,
    val invalidSamples: Int,
    val missedNotifications: Int
)

data class ReceiverResponse(
    val version: Int,
    val opcode: Int,
    val status: Int,
    val slot: Int,
    val receiverId: Int,
    val type: Int,
    val sensorId: Int,
    val mac: String,
    val online: Boolean,
    val sensorInfo: SensorInfo? = null
)

data class BindingConfig(val slot: Int, val type: Int, val sensorId: Int, val mac: String)

data class SlotState(
    val slot: Int,
    val binding: BindingConfig? = null,
    val reading: SensorPayload? = null,
    val info: SensorInfo? = null,
    val fast: Boolean = false,
    val stale: Boolean = false
)

sealed interface OtaState {
    data object Idle : OtaState
    data class Preparing(val message: String) : OtaState
    data class Transferring(val percent: Int, val sent: Long, val total: Long) : OtaState
    data class Verifying(val message: String) : OtaState
    data class Complete(val version: String?) : OtaState
    data class Failed(val message: String, val recoverable: Boolean = true) : OtaState
}
