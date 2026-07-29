package com.siliconlabs.bledemo.features.iop_test.models

import android.content.Context
import com.siliconlabs.bledemo.bluetooth.parsing.Common
import java.util.Locale
import java.util.UUID

object IOPGattReferenceCatalog {
    private val serviceNames = mapOf(
        "0000180a-0000-1000-8000-00805f9b34fb" to "Device Information Service",
        CommonUUID.Service.TEST_PARAMETERS.toString().lowercase(Locale.US) to "IOP Test Service",
        CommonUUID.Service.UUID_PROPERTIES_SERVICE.toString().lowercase(Locale.US) to "IOP Test Properties Service",
        CommonUUID.Service.UUID_CHARACTERISTICS_SERVICE.toString().lowercase(Locale.US) to
            "IOP Test Characteristic Types Service",
        CommonUUID.Service.UUID_PHASE3_SERVICE.toString().lowercase(Locale.US) to "IOP Test Phase 3 Service",
        CommonUUID.Service.UUID_BLE_OTA.toString().lowercase(Locale.US) to "BLE OTA Service",
        CommonUUID.Service.UUID_GENERIC_ATTRIBUTE.toString().lowercase(Locale.US) to "Generic Attribute Service",
        CommonUUID.Service.UUID_GENERIC_ACCESS.toString().lowercase(Locale.US) to "Generic Access Service"
    )

    private val characteristicNames = mapOf(
        "00002a24-0000-1000-8000-00805f9b34fb" to "Model Number String",
        CommonUUID.Characteristic.FIRMWARE_VERSION.toString().lowercase(Locale.US) to "IOP Test Version",
        CommonUUID.Characteristic.CONNECTION_PARAMETERS.toString().lowercase(Locale.US) to "IOP Test Connection",
        "a432d31f-9022-4045-96ff-32258ffe7192" to "IOP Test Control RFU",
        CommonUUID.Characteristic.READ_ONLY_LENGTH_1.toString().lowercase(Locale.US) to "IOP Test Read Only Length 1",
        CommonUUID.Characteristic.READ_ONLY_LENGTH_255.toString().lowercase(Locale.US) to
            "IOP Test Read Only Length 255",
        CommonUUID.Characteristic.WRITE_ONLY_LENGTH_1.toString().lowercase(Locale.US) to "IOP Test Write Only Length 1",
        CommonUUID.Characteristic.WRITE_ONLY_LENGTH_255.toString().lowercase(Locale.US) to
            "IOP Test Write Only Length 255",
        CommonUUID.Characteristic.WRITE_WITHOUT_RESPONSE_LENGTH_1.toString().lowercase(Locale.US) to
            "IOP Test Write Without Response Length 1",
        CommonUUID.Characteristic.WRITE_WITHOUT_RESPONSE_LENGTH_255.toString().lowercase(Locale.US) to
            "IOP Test Write Without Response Length 255",
        CommonUUID.Characteristic.NOTIFICATION_LENGTH_1.toString().lowercase(Locale.US) to "IOP Test Notify Length 1",
        CommonUUID.Characteristic.NOTIFICATION_LENGTH_MTU_3.toString().lowercase(Locale.US) to
            "IOP Test Notify Length MTU - 3",
        CommonUUID.Characteristic.INDICATE_LENGTH_1.toString().lowercase(Locale.US) to "IOP Test Indicate Length 1",
        CommonUUID.Characteristic.INDICATE_LENGTH_MTU_3.toString().lowercase(Locale.US) to
            "IOP Test Indicate Length MTU - 3",
        CommonUUID.Characteristic.IOP_TEST_LENGTH_1.toString().lowercase(Locale.US) to "IOP Test Length 1",
        CommonUUID.Characteristic.IOP_TEST_LENGTH_255.toString().lowercase(Locale.US) to "IOP Test Length 255",
        CommonUUID.Characteristic.IOP_TEST_LENGTH_VARIABLE_4.toString().lowercase(Locale.US) to
            "IOP Test Length Variable 4",
        CommonUUID.Characteristic.IOP_TEST_CONST_LENGTH_1.toString().lowercase(Locale.US) to "IOP Test Const Length 1",
        CommonUUID.Characteristic.IOP_TEST_CONST_LENGTH_255.toString().lowercase(Locale.US) to
            "IOP Test Const Length 255",
        CommonUUID.Characteristic.IOP_TEST_USER_LEN_1.toString().lowercase(Locale.US) to "IOP Test User Len 1",
        CommonUUID.Characteristic.IOP_TEST_USER_LEN_255.toString().lowercase(Locale.US) to "IOP Test User Len 255",
        CommonUUID.Characteristic.IOP_TEST_USER_LEN_VARIABLE_4.toString().lowercase(Locale.US) to
            "IOP Test User Len Variable 4",
        CommonUUID.Characteristic.IOP_TEST_PHASE3_CONTROL.toString().lowercase(Locale.US) to "IOP Test Phase 3 Control",
        CommonUUID.Characteristic.IOP_TEST_SECURITY_PAIRING.toString().lowercase(Locale.US) to
            "IOP Test Security Pairing",
        CommonUUID.Characteristic.IOP_TEST_SECURITY_AUTHENTICATION.toString().lowercase(Locale.US) to
            "IOP Test Security Authentication",
        CommonUUID.Characteristic.IOP_TEST_SECURITY_BONDING.toString().lowercase(Locale.US) to
            "IOP Test Security Bonding",
        CommonUUID.Characteristic.IOP_TEST_THROUGHPUT.toString().lowercase(Locale.US) to
            "IOP Test Throughput GATT Notification",
        CommonUUID.Characteristic.IOP_TEST_GATT_CATCHING.toString().lowercase(Locale.US) to "IOP Test GATT Caching 7.5",
        "6a978442-f37b-a07c-1a5f-0e6f15a5fc83" to "IOP Test Security Bonding",
        "b5178061-69ce-46a9-3740-7b3c580953b0" to "IOP Test GATT Caching 7.5"
    )

    fun serviceName(uuid: UUID, context: Context): String {
        val key = uuid.toString().lowercase(Locale.US)
        return serviceNames[key] ?: Common.getServiceName(uuid, context)
    }

    fun characteristicName(uuid: UUID, context: Context): String {
        val key = uuid.toString().lowercase(Locale.US)
        return characteristicNames[key] ?: Common.getCharacteristicName(uuid, context)
    }
}
