package com.siliconlabs.bledemo.features.iop_test.models

sealed class IOPGattListItem {
    data class ServiceHeader(val name: String, val uuid: String) : IOPGattListItem()

    data class CharacteristicRow(
        val name: String,
        val uuid: String,
        val properties: List<IOPGattProperty>
    ) : IOPGattListItem()
}

data class IOPGattDiscoveredService(
    val name: String,
    val uuid: String,
    var characteristics: List<IOPGattDiscoveredCharacteristic> = emptyList()
)

data class IOPGattDiscoveredCharacteristic(
    val name: String,
    val uuid: String,
    val properties: Int
)
