package com.zg.sensormonitor.protocol

import com.zg.sensormonitor.domain.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object DeviceProtocol {
    val RX_SERVICE: UUID = uuid("6F8A0000-7D4B-4A8E-9B21-3C5D7E9F1000")
    val RX_CMD: UUID = uuid("6F8A0001-7D4B-4A8E-9B21-3C5D7E9F1000")
    val RX_RESP: UUID = uuid("6F8A0002-7D4B-4A8E-9B21-3C5D7E9F1000")
    val RX_STREAM: UUID = uuid("6F8A0003-7D4B-4A8E-9B21-3C5D7E9F1000")
    val RX_STATUS: UUID = uuid("6F8A0004-7D4B-4A8E-9B21-3C5D7E9F1000")

    val SN_DATA_SERVICE: UUID = uuid("C8E21E04-2D3A-4C65-B9D6-8E1A4B0F2C7D")
    val SN_DATA: UUID = uuid("C8E21E05-2D3A-4C65-B9D6-8E1A4B0F2C7D")
    val SN_CONFIG_SERVICE: UUID = uuid("D6E1F204-3A1B-4C72-B9A5-5E1F6C308D2A")
    val SN_OFFSET: UUID = uuid("D6E1F205-3A1B-4C72-B9A5-5E1F6C308D2A")
    val SN_RATE: UUID = uuid("D6E1F206-3A1B-4C72-B9A5-5E1F6C308D2A")
    val SN_MAC: UUID = uuid("D6E1F207-3A1B-4C72-B9A5-5E1F6C308D2A")
    val SN_INFO: UUID = uuid("D6E1F208-3A1B-4C72-B9A5-5E1F6C308D2A")
    val SN_POWER_MODE: UUID = uuid("D6E1F209-3A1B-4C72-B9A5-5E1F6C308D2A")

    val OTA_SERVICE: UUID = uuid("1D14D6EE-FD63-4FA1-BFA4-8F47B42119F0")
    val OTA_CONTROL: UUID = uuid("F7BF3564-FB6D-4E53-88A4-5E37E0326063")
    val OTA_DATA: UUID = uuid("984227F3-34FC-4045-A5D0-2C581F81A153")
    val DEVICE_INFO_SERVICE: UUID = uuid("0000180A-0000-1000-8000-00805F9B34FB")
    val FIRMWARE_REVISION: UUID = uuid("00002A26-0000-1000-8000-00805F9B34FB")
    val CCCD: UUID = uuid("00002902-0000-1000-8000-00805F9B34FB")

    const val VERSION = 1
    const val SLOT_COUNT = 8
    const val TYPE_PRESSURE = 1
    const val TYPE_TILT = 2

    object Op {
        const val SET_ID = 1
        const val SET_SLOT = 2
        const val CLEAR_SLOT = 3
        const val CLEAR_ALL = 4
        const val GET_SLOT = 5
        const val SET_RATE = 6
        const val GET_SENSOR_INFO = 7
    }

    fun setId(id: Int) = byteArrayOf(VERSION.b(), Op.SET_ID.b(), id.b(), (id shr 8).b())
    fun setSlot(slot: Int, type: Int, sensorId: Int, mac: ByteArray): ByteArray {
        require(slot in 0 until SLOT_COUNT) { "槽位超出范围" }
        require(mac.size == 6) { "MAC 必须为6字节" }
        return byteArrayOf(VERSION.b(), Op.SET_SLOT.b(), slot.b(), type.b(), sensorId.b()) + mac
    }
    fun clearSlot(slot: Int) = byteArrayOf(VERSION.b(), Op.CLEAR_SLOT.b(), checkedSlot(slot).b())
    fun clearAll() = byteArrayOf(VERSION.b(), Op.CLEAR_ALL.b())
    fun getSlot(slot: Int) = byteArrayOf(VERSION.b(), Op.GET_SLOT.b(), checkedSlot(slot).b())
    fun setRate(slot: Int, fast: Boolean) = byteArrayOf(VERSION.b(), Op.SET_RATE.b(), checkedSlot(slot).b(), (if (fast) 1 else 0).b())
    fun getSensorInfo(slot: Int) = byteArrayOf(VERSION.b(), Op.GET_SENSOR_INFO.b(), checkedSlot(slot).b())

    fun parseStatus(data: ByteArray): ReceiverStatus? {
        if (data.size < 8) return null
        return ReceiverStatus(data.u(0), data.u(1) or (data.u(2) shl 8), data.u(3), data.u(4), data.u(5), data.u(6), data.u(7))
    }

    fun parseResponse(data: ByteArray): ReceiverResponse? {
        if (data.size < 15) return null
        val opcode = data.u(1)
        return ReceiverResponse(
            version = data.u(0), opcode = opcode, status = data.u(2), slot = data.u(3),
            receiverId = data.u(4) or (data.u(5) shl 8), type = data.u(6), sensorId = data.u(7),
            mac = bytesToMac(data.copyOfRange(8, 14)), online = data.u(14) != 0,
            sensorInfo = if (opcode == Op.GET_SENSOR_INFO && data.size > 15) parseSensorInfo(data.copyOfRange(15, data.size)) else null
        )
    }

    fun parseStream(data: ByteArray): StreamReading? {
        if (data.size < 18 || data.u(0) !in 0 until SLOT_COUNT) return null
        val payload = parsePayload(data, 1) ?: return null
        return StreamReading(data.u(0), payload)
    }

    fun parsePayload(data: ByteArray, offset: Int = 0): SensorPayload? {
        if (offset < 0 || data.size - offset < 16) return null
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val flags = if (data.size - offset >= 17) data.u(offset + 16) else null
        val zRaw = (data[offset + 13].toInt() shl 8) or data.u(offset + 4)
        return SensorPayload(
            uptime = b.getInt(offset).toLong() and 0xffffffffL,
            uptime24 = data.u(offset) or (data.u(offset + 1) shl 8) or (data.u(offset + 2) shl 16),
            temperature = data.u(offset + 3), pressure = b.getInt(offset + 5),
            raw = b.getInt(offset + 9).toLong() and 0xffffffffL, sequence = b.getShort(offset + 14).toInt() and 0xffff,
            notifyFlags = flags, pa5 = flags?.let { it and 1 != 0 }, readOk = flags?.let { it and 2 != 0 },
            voltageV2 = flags?.let { ((it shr 2) and 0x3f) / 10.0 },
            voltageV1 = flags?.let { ((it shr 1) and 0x7f) / 10.0 },
            xAngle = b.getShort(offset + 5).toInt(), yAngle = b.getShort(offset + 7).toInt(),
            xRaw = b.getShort(offset + 9).toInt(), yRaw = b.getShort(offset + 11).toInt(), zRaw = zRaw
        )
    }

    fun parseSensorInfo(data: ByteArray): SensorInfo? {
        if (data.size < 10) return null
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val flags = data.getOrNull(19)?.toInt()?.and(0xff)
        return SensorInfo(
            b.getInt(0).toLong() and 0xffffffffL,
            if (data.size >= 8) b.getInt(4).toLong() and 0xffffffffL else 0,
            b.getShort(8).toInt() and 0xffff, (data.getOrNull(10)?.toInt()?.and(0xff) ?: 0) != 0,
            data.getOrNull(11)?.toInt()?.and(0xff) ?: 0,
            if (data.size >= 14) b.getShort(12).toInt() and 0xffff else 0,
            data.getOrNull(14)?.toInt()?.and(0xff) ?: 0,
            data.getOrNull(15)?.toInt()?.and(0xff) ?: 0,
            data.getOrNull(16)?.toInt()?.and(0xff), data.getOrNull(17)?.toInt()?.and(0xff) ?: 0,
            data.getOrNull(18)?.toInt(), flags?.let { it and 1 != 0 }, flags?.let { it and 2 != 0 },
            if (data.size >= 22) b.getShort(20).toInt() and 0xffff else 0,
            if (data.size >= 24) b.getShort(22).toInt() and 0xffff else 0
        )
    }

    fun voltage(payload: SensorPayload, info: SensorInfo?): Double? =
        if (payload.notifyFlags == null) null else if (info?.version == 1) payload.voltageV1 else payload.voltageV2

    fun parseMac(text: String): ByteArray? {
        val parts = text.trim().replace('-', ':').split(':')
        if (parts.size != 6 || parts.any { !it.matches(Regex("[0-9a-fA-F]{1,2}")) }) return null
        return ByteArray(6) { parts[it].toInt(16).toByte() }
    }

    fun bytesToMac(bytes: ByteArray) = bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    fun classify(name: String?, serviceUuids: Collection<UUID>): DeviceKind? {
        val n = name.orEmpty().uppercase()
        return when {
            OTA_SERVICE in serviceUuids -> DeviceKind.OTA
            RX_SERVICE in serviceUuids || n.startsWith("AIOT-") || n.startsWith("RX-") || n == "MCMPDT" -> DeviceKind.RECEIVER
            n.contains("TILT") -> DeviceKind.TILT
            n.contains("PRESS") || n.startsWith("PRES-") -> DeviceKind.PRESSURE
            SN_DATA_SERVICE in serviceUuids || n == "SENSORNODE" -> DeviceKind.SENSOR
            else -> null
        }
    }

    private fun checkedSlot(slot: Int): Int = slot.also { require(it in 0 until SLOT_COUNT) { "槽位超出范围" } }
    private fun Int.b() = toByte()
    private fun ByteArray.u(index: Int) = this[index].toInt() and 0xff
    private fun uuid(value: String) = UUID.fromString(value.lowercase())
}
