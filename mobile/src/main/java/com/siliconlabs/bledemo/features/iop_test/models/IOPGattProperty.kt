package com.siliconlabs.bledemo.features.iop_test.models

import android.bluetooth.BluetoothGattCharacteristic
import androidx.annotation.ColorRes
import com.siliconlabs.bledemo.R

enum class IOPGattProperty(
    val label: String,
    @ColorRes val colorRes: Int,
    val backgroundDrawableRes: Int
) {
    READ("READ", R.color.silabs_blue, R.drawable.iop_gatt_property_read_bg),
    WRITE("WRITE", R.color.silabs_yellow, R.drawable.iop_gatt_property_write_bg),
    NOTIFY("NOTIFY", R.color.silabs_green, R.drawable.iop_gatt_property_notify_bg);

    companion object {
        fun fromCharacteristicProperties(properties: Int): List<IOPGattProperty> {
            val result = mutableListOf<IOPGattProperty>()
            if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                result.add(READ)
            }
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            ) {
                result.add(WRITE)
            }
            if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            ) {
                result.add(NOTIFY)
            }
            return result
        }
    }
}
