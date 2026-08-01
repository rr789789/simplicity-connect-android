package com.zg.sensormonitor.protocol

import com.zg.sensormonitor.domain.DeviceKind
import org.junit.Assert.*
import org.junit.Test

class DeviceProtocolTest {
    @Test fun commandEncodingIsLittleEndian() {
        assertArrayEquals(byteArrayOf(1, 1, 0x34, 0x12), DeviceProtocol.setId(0x1234))
        assertArrayEquals(byteArrayOf(1, 6, 7, 1), DeviceProtocol.setRate(7, true))
    }

    @Test fun slotCommandValidatesRangeAndMac() {
        val mac = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertArrayEquals(byteArrayOf(1, 2, 0, 1, 4, 1, 2, 3, 4, 5, 6), DeviceProtocol.setSlot(0, 1, 4, mac))
        assertThrows(IllegalArgumentException::class.java) { DeviceProtocol.getSlot(8) }
        assertThrows(IllegalArgumentException::class.java) { DeviceProtocol.setSlot(0, 1, 1, byteArrayOf(1)) }
    }

    @Test fun parsesReceiverStatusAndRejectsShortPacket() {
        assertNull(DeviceProtocol.parseStatus(byteArrayOf(1, 2)))
        val value = DeviceProtocol.parseStatus(byteArrayOf(1, 0x34, 0x12, 0x03, 0x01, 0x01, 8, 0x02))!!
        assertEquals(0x1234, value.receiverId)
        assertTrue(value.bound(0)); assertTrue(value.bound(1)); assertTrue(value.online(0)); assertFalse(value.online(1))
    }

    @Test fun parsesPayloadAndVoltageVersions() {
        val bytes = ByteArray(17)
        bytes[0] = 1; bytes[3] = 25; bytes[5] = 100; bytes[16] = (40 shl 2 or 3).toByte()
        val payload = DeviceProtocol.parsePayload(bytes)!!
        assertEquals(10.0, payload.xAngle / 10.0, 0.01)
        assertTrue(payload.pa5 == true); assertTrue(payload.readOk == true)
        assertEquals(4.0, payload.voltageV2!!, 0.01)
    }

    @Test fun parsesMacStrictly() {
        assertEquals("AA:BB:CC:DD:EE:FF", DeviceProtocol.bytesToMac(DeviceProtocol.parseMac("AA-BB-CC-DD-EE-FF")!!))
        assertEquals("0A:0B:0C:0D:0E:0F", DeviceProtocol.bytesToMac(DeviceProtocol.parseMac("A:B:C:D:E:F")!!))
        assertNull(DeviceProtocol.parseMac("A:B:C:D:E"))
    }

    @Test fun classifiesSupportedNamesAndServices() {
        assertEquals(DeviceKind.RECEIVER, DeviceProtocol.classify("AIOT-01", emptyList()))
        assertEquals(DeviceKind.TILT, DeviceProtocol.classify("TiltNode", emptyList()))
        assertNull(DeviceProtocol.classify("Headphones", emptyList()))
    }
}
