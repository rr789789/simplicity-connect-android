package com.zg.sensormonitor.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.zg.sensormonitor.data.AuditStore
import com.zg.sensormonitor.domain.*
import com.zg.sensormonitor.protocol.DeviceProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

@SuppressLint("MissingPermission")
class BleCentralManager(context: Context, private val audit: AuditStore) {
    interface Listener {
        fun onPhase(phase: LinkPhase, message: String? = null) {}
        fun onDevice(device: DiscoveredDevice) {}
        fun onReceiverStatus(status: ReceiverStatus) {}
        fun onStream(reading: StreamReading) {}
        fun onReceiverResponse(response: ReceiverResponse) {}
        fun onSensorPayload(payload: SensorPayload) {}
        fun onRssi(rssi: Int) {}
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operationMutex = Mutex()
    private var pending: CompletableDeferred<ByteArray>? = null
    private var pendingUuid: UUID? = null
    private var gatt: BluetoothGatt? = null
    private var active: DiscoveredDevice? = null
    private var intentionalDisconnect = false
    private var reconnectAttempt = 0
    private var lastDataAt = 0L
    private var currentPhase = LinkPhase.IDLE
    private var negotiatedMtu = 23
    private val listeners = CopyOnWriteArraySet<Listener>()
    private var autoReconnect = true

    val activeDevice: DiscoveredDevice? get() = active
    val phase: LinkPhase get() = currentPhase
    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true
    val writePayloadSize: Int get() = (negotiatedMtu - 3).coerceIn(20, 244)

    fun addListener(listener: Listener) { listeners += listener }
    fun removeListener(listener: Listener) { listeners -= listener }
    fun setDfuMode(enabled: Boolean) {
        autoReconnect = !enabled
        if (enabled) setPhase(LinkPhase.DFU, "正在准备固件升级")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = emitScanResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::emitScanResult)
        override fun onScanFailed(errorCode: Int) = setPhase(LinkPhase.FAULT, "扫描失败($errorCode)")
    }

    private val staleCheck = object : Runnable {
        override fun run() {
            if ((currentPhase == LinkPhase.ONLINE || currentPhase == LinkPhase.STALE) &&
                lastDataAt > 0 && System.currentTimeMillis() - lastDataAt > STALE_MS) {
                setPhase(LinkPhase.STALE, "数据超过5秒未更新")
            }
            if (gatt != null) handler.postDelayed(this, 1000)
        }
    }

    private val rssiCheck = object : Runnable {
        override fun run() {
            if (gatt != null && hasConnectPermission()) {
                gatt?.readRemoteRssi()
                handler.postDelayed(this, 2500)
            }
        }
    }

    fun hasScanPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun startScan() {
        if (!hasScanPermission()) return setPhase(LinkPhase.FAULT, "缺少蓝牙扫描权限")
        if (!isBluetoothEnabled) return setPhase(LinkPhase.FAULT, "蓝牙未开启")
        stopScan()
        adapter?.bluetoothLeScanner?.startScan(scanCallback)
        setPhase(LinkPhase.SCANNING)
    }

    fun stopScan() {
        if (hasScanPermission()) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (currentPhase == LinkPhase.SCANNING) setPhase(LinkPhase.IDLE)
    }

    fun connect(device: DiscoveredDevice, reconnecting: Boolean = false) {
        if (!hasConnectPermission()) return setPhase(LinkPhase.FAULT, "缺少蓝牙连接权限")
        stopScan()
        disconnect(closeOnly = true)
        active = device
        intentionalDisconnect = false
        setPhase(if (reconnecting) LinkPhase.RECONNECTING else LinkPhase.CONNECTING)
        audit.record("连接", "开始", device.address, device.name)
        val bluetoothAdapter = adapter ?: return setPhase(LinkPhase.FAULT, "设备不支持蓝牙")
        val remote = runCatching { bluetoothAdapter.getRemoteDevice(device.address) }.getOrElse {
            setPhase(LinkPhase.FAULT, "设备地址无效"); return
        }
        gatt = if (Build.VERSION.SDK_INT >= 23) remote.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        else remote.connectGatt(appContext, false, callback)
    }

    fun disconnect(closeOnly: Boolean = false) {
        handler.removeCallbacks(staleCheck)
        handler.removeCallbacks(rssiCheck)
        pending?.cancel()
        pending = null
        pendingUuid = null
        intentionalDisconnect = true
        gatt?.let { current ->
            if (!closeOnly) setPhase(LinkPhase.DISCONNECTING)
            runCatching { current.disconnect() }
            runCatching { current.close() }
        }
        gatt = null
        if (!closeOnly) {
            active = null
            setPhase(LinkPhase.IDLE)
        }
    }

    suspend fun read(service: UUID, characteristic: UUID): ByteArray = operationMutex.withLock {
        val (current, ch) = characteristic(service, characteristic)
        awaitOperation(characteristic) { current.readCharacteristic(ch) }
    }

    suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean = true): ByteArray = operationMutex.withLock {
        val (current, ch) = characteristic(service, characteristic)
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (!withResponse) {
            val status = if (Build.VERSION.SDK_INT >= 33) current.writeCharacteristic(ch, value, type)
            else {
                ch.writeType = type
                ch.value = value
                if (current.writeCharacteristic(ch)) BluetoothStatusCodes.SUCCESS else BluetoothStatusCodes.ERROR_UNKNOWN
            }
            if (status != BluetoothStatusCodes.SUCCESS) throw BleException("写入启动失败($status)")
            return@withLock value
        }
        awaitOperation(characteristic) {
            if (Build.VERSION.SDK_INT >= 33) current.writeCharacteristic(ch, value, type) == BluetoothStatusCodes.SUCCESS
            else { ch.writeType = type; ch.value = value; current.writeCharacteristic(ch) }
        }
    }

    suspend fun setSensorRate(fast: Boolean) = write(DeviceProtocol.SN_CONFIG_SERVICE, DeviceProtocol.SN_RATE, byteArrayOf((if (fast) 1 else 0).toByte()))
    suspend fun setPowerMode(lowPower: Boolean) = write(DeviceProtocol.SN_CONFIG_SERVICE, DeviceProtocol.SN_POWER_MODE, byteArrayOf((if (lowPower) 1 else 0).toByte()))

    fun summary(): String = "phase=$currentPhase\ndevice=${active?.address.orEmpty()}\nname=${active?.name.orEmpty()}\nlastDataAt=$lastDataAt"

    private suspend fun subscribe(service: UUID, characteristic: UUID) = operationMutex.withLock {
        val (current, ch) = characteristic(service, characteristic)
        if (!current.setCharacteristicNotification(ch, true)) throw BleException("无法启用通知 $characteristic")
        val descriptor = ch.getDescriptor(DeviceProtocol.CCCD) ?: throw BleException("通知描述符缺失 $characteristic")
        val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        awaitOperation(characteristic) {
            if (Build.VERSION.SDK_INT >= 33) current.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            else { descriptor.value = value; current.writeDescriptor(descriptor) }
        }
    }

    private suspend fun initialize(kind: DeviceKind) {
        setPhase(LinkPhase.SUBSCRIBING)
        when (kind) {
            DeviceKind.RECEIVER -> {
                subscribe(DeviceProtocol.RX_SERVICE, DeviceProtocol.RX_STATUS)
                delay(200)
                subscribe(DeviceProtocol.RX_SERVICE, DeviceProtocol.RX_STREAM)
                delay(200)
                subscribe(DeviceProtocol.RX_SERVICE, DeviceProtocol.RX_RESP)
                runCatching { DeviceProtocol.parseStatus(read(DeviceProtocol.RX_SERVICE, DeviceProtocol.RX_STATUS)) }
                    .getOrNull()?.let { value -> listeners.forEach { it.onReceiverStatus(value) } }
            }
            DeviceKind.PRESSURE, DeviceKind.TILT, DeviceKind.SENSOR -> subscribe(DeviceProtocol.SN_DATA_SERVICE, DeviceProtocol.SN_DATA)
            DeviceKind.OTA -> Unit
        }
        reconnectAttempt = 0
        lastDataAt = System.currentTimeMillis()
        setPhase(LinkPhase.ONLINE)
        handler.removeCallbacks(staleCheck)
        if (kind != DeviceKind.OTA) handler.post(staleCheck)
        handler.removeCallbacks(rssiCheck); handler.post(rssiCheck)
        audit.record("连接", "成功", active?.address, kind.name)
    }

    private suspend fun awaitOperation(uuid: UUID, start: () -> Boolean): ByteArray {
        val result = CompletableDeferred<ByteArray>()
        pending = result; pendingUuid = uuid
        if (!start()) {
            pending = null; pendingUuid = null
            throw BleException("GATT操作启动失败")
        }
        return try { withTimeout(OPERATION_TIMEOUT_MS) { result.await() } }
        finally { if (pending === result) { pending = null; pendingUuid = null } }
    }

    private fun complete(uuid: UUID, status: Int, value: ByteArray = byteArrayOf()) {
        if (pendingUuid != uuid) return
        val target = pending ?: return
        pending = null; pendingUuid = null
        if (status == BluetoothGatt.GATT_SUCCESS) target.complete(value)
        else target.completeExceptionally(BleException("GATT操作失败($status)"))
    }

    private fun characteristic(service: UUID, characteristic: UUID): Pair<BluetoothGatt, BluetoothGattCharacteristic> {
        val current = gatt ?: throw BleException("设备未连接")
        val target = current.getService(service)?.getCharacteristic(characteristic)
            ?: throw BleException("设备缺少特征 $characteristic")
        return current to target
    }

    private fun emitScanResult(result: ScanResult) {
        val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull().orEmpty()
        val uuids = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
        val kind = DeviceProtocol.classify(name, uuids) ?: return
        listeners.forEach { it.onDevice(DiscoveredDevice(result.device.address, name, result.rssi, kind)) }
    }

    private fun setPhase(value: LinkPhase, message: String? = null) {
        currentPhase = value
        handler.post { listeners.forEach { it.onPhase(value, message) } }
    }

    private fun scheduleReconnect(reason: String) {
        val device = active ?: return setPhase(LinkPhase.IDLE)
        if (intentionalDisconnect || !autoReconnect) return setPhase(if (autoReconnect) LinkPhase.IDLE else LinkPhase.DFU)
        val delays = longArrayOf(1000, 2000, 4000, 8000, 15000)
        val delay = delays[reconnectAttempt.coerceAtMost(delays.lastIndex)]
        reconnectAttempt++
        audit.record("重连", "等待", device.address, "$reason, ${delay}ms")
        setPhase(LinkPhase.RECONNECTING, "${delay / 1000}秒后重连")
        handler.postDelayed({ if (!intentionalDisconnect) connect(device, true) }, delay)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(current: BluetoothGatt, status: Int, newState: Int) {
            if (current !== gatt) { current.close(); return }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> if (status == BluetoothGatt.GATT_SUCCESS) {
                    setPhase(LinkPhase.DISCOVERING)
                    current.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    current.requestMtu(247)
                    current.discoverServices()
                } else scheduleReconnect("连接状态$status")
                BluetoothProfile.STATE_DISCONNECTED -> {
                    current.close(); if (current === gatt) gatt = null
                    pending?.completeExceptionally(BleException("连接已断开")); pending = null
                    audit.record("断开", status.toString(), active?.address)
                    scheduleReconnect("断开状态$status")
                }
            }
        }

        override fun onServicesDiscovered(current: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return scheduleReconnect("服务发现失败$status")
            val kind = active?.kind ?: return
            val required = when (kind) {
                DeviceKind.RECEIVER -> listOf(DeviceProtocol.RX_SERVICE)
                DeviceKind.PRESSURE, DeviceKind.TILT, DeviceKind.SENSOR -> listOf(DeviceProtocol.SN_DATA_SERVICE, DeviceProtocol.SN_CONFIG_SERVICE)
                DeviceKind.OTA -> listOf(DeviceProtocol.OTA_SERVICE)
            }
            if (required.any { current.getService(it) == null }) {
                setPhase(LinkPhase.FAULT, "设备服务不完整")
                audit.record("服务发现", "失败", active?.address, "required=$required")
                return
            }
            val requiredCharacteristics = when (kind) {
                DeviceKind.RECEIVER -> listOf(
                    DeviceProtocol.RX_SERVICE to DeviceProtocol.RX_CMD,
                    DeviceProtocol.RX_SERVICE to DeviceProtocol.RX_RESP,
                    DeviceProtocol.RX_SERVICE to DeviceProtocol.RX_STREAM,
                    DeviceProtocol.RX_SERVICE to DeviceProtocol.RX_STATUS
                )
                DeviceKind.PRESSURE, DeviceKind.TILT, DeviceKind.SENSOR -> listOf(
                    DeviceProtocol.SN_DATA_SERVICE to DeviceProtocol.SN_DATA,
                    DeviceProtocol.SN_CONFIG_SERVICE to DeviceProtocol.SN_RATE,
                    DeviceProtocol.SN_CONFIG_SERVICE to DeviceProtocol.SN_MAC,
                    DeviceProtocol.SN_CONFIG_SERVICE to DeviceProtocol.SN_INFO,
                    DeviceProtocol.SN_CONFIG_SERVICE to DeviceProtocol.SN_POWER_MODE
                )
                DeviceKind.OTA -> listOf(
                    DeviceProtocol.OTA_SERVICE to DeviceProtocol.OTA_CONTROL,
                    DeviceProtocol.OTA_SERVICE to DeviceProtocol.OTA_DATA
                )
            }
            val missing = requiredCharacteristics.filter { (service, characteristic) ->
                current.getService(service)?.getCharacteristic(characteristic) == null
            }
            if (missing.isNotEmpty()) {
                setPhase(LinkPhase.FAULT, "设备特征不完整")
                audit.record("服务发现", "失败", active?.address, "missing=$missing")
                return
            }
            scope.launch { runCatching { initialize(kind) }.onFailure {
                setPhase(LinkPhase.FAULT, it.message ?: "初始化失败")
                audit.record("初始化", "失败", active?.address, it.message)
            } }
        }

        @Deprecated("API 33")
        override fun onCharacteristicRead(current: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) = complete(ch.uuid, status, ch.value ?: byteArrayOf())
        override fun onCharacteristicRead(current: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) = complete(ch.uuid, status, value)
        override fun onCharacteristicWrite(current: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) = complete(ch.uuid, status, ch.value ?: byteArrayOf())
        override fun onDescriptorWrite(current: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = complete(descriptor.characteristic.uuid, status)

        @Deprecated("API 33")
        override fun onCharacteristicChanged(current: BluetoothGatt, ch: BluetoothGattCharacteristic) = handleNotification(ch.uuid, ch.value ?: byteArrayOf())
        override fun onCharacteristicChanged(current: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) = handleNotification(ch.uuid, value)

        override fun onReadRemoteRssi(current: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) handler.post { listeners.forEach { it.onRssi(rssi) } }
        }

        override fun onMtuChanged(current: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
        }
    }

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        lastDataAt = System.currentTimeMillis()
        if (currentPhase == LinkPhase.STALE) setPhase(LinkPhase.ONLINE)
        handler.post {
            when (uuid) {
                DeviceProtocol.RX_STATUS -> DeviceProtocol.parseStatus(value)?.let { parsed -> listeners.forEach { it.onReceiverStatus(parsed) } }
                DeviceProtocol.RX_STREAM -> DeviceProtocol.parseStream(value)?.let { parsed -> listeners.forEach { it.onStream(parsed) } }
                DeviceProtocol.RX_RESP -> DeviceProtocol.parseResponse(value)?.let { parsed -> listeners.forEach { it.onReceiverResponse(parsed) } }
                DeviceProtocol.SN_DATA -> DeviceProtocol.parsePayload(value)?.let { parsed -> listeners.forEach { it.onSensorPayload(parsed) } }
            }
        }
    }

    companion object {
        private const val OPERATION_TIMEOUT_MS = 4000L
        private const val STALE_MS = 5000L
    }
}

class BleException(message: String) : Exception(message)
