package com.zg.sensormonitor.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.zg.sensormonitor.R
import com.zg.sensormonitor.SensorMonitorApplication
import com.zg.sensormonitor.ble.BleCentralManager
import com.zg.sensormonitor.databinding.ActivityDeviceBinding
import com.zg.sensormonitor.domain.*
import com.zg.sensormonitor.ota.FirmwareSource
import com.zg.sensormonitor.ota.OtaEngine
import com.zg.sensormonitor.protocol.DeviceProtocol
import com.zg.sensormonitor.report.AcceptanceReport
import com.zg.sensormonitor.data.AlarmChange
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import kotlinx.coroutines.*
import android.os.Handler
import android.os.Looper

class DeviceActivity : ThemedActivity(), BleCentralManager.Listener {
    private lateinit var binding: ActivityDeviceBinding
    private val app get() = application as SensorMonitorApplication
    private lateinit var device: DiscoveredDevice
    private lateinit var slotAdapter: SlotAdapter
    private var receiverStatus: ReceiverStatus? = null
    private val slots = MutableList(DeviceProtocol.SLOT_COUNT) { SlotState(it) }
    private var sensorInfo: SensorInfo? = null
    private var sensorPayload: SensorPayload? = null
    private var maintenance = false
    private var currentRssi: Int? = null
    private var responseWaiter: ResponseWaiter? = null
    private var pendingMacInput: EditText? = null
    private val bindingEditors = linkedMapOf<Int, Pair<Spinner, EditText>>()
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val ageRefresh = object : Runnable {
        override fun run() {
            var changed = false
            slots.indices.forEach { index ->
                val stale = slots[index].reading?.let { System.currentTimeMillis() - it.receivedAt > 5000 } == true
                if (stale != slots[index].stale) { slots[index] = slots[index].copy(stale = stale); changed = true }
            }
            if (changed) submitSlots()
            refreshHandler.postDelayed(this, 1000)
        }
    }

    private val qrLauncher = registerForActivityResult(ScanQRCode()) { result ->
        if (result is QRResult.QRSuccess) {
            val raw = result.content.rawValue.orEmpty()
            val compact = Regex("(?i)[0-9a-f]{12}").find(raw.replace(Regex("[^0-9a-fA-F]"), ""))?.value
            pendingMacInput?.setText(compact?.chunked(2)?.joinToString(":")?.uppercase() ?: raw)
        }
    }
    private val firmwarePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { beginOta(FirmwareSource.Local(it)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceBinding.inflate(layoutInflater); setContentView(binding.root)
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return finish()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val kind = runCatching { DeviceKind.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty()) }.getOrDefault(DeviceKind.SENSOR)
        device = DiscoveredDevice(address, name, 0, kind)
        binding.toolbar.title = if (kind == DeviceKind.RECEIVER) "接收器" else "传感器配置"
        binding.deviceName.text = name.ifBlank { DeviceAdapter.kindName(kind) }
        binding.statusDetail.text = address
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_history) {
                startActivity(Intent(this, HistoryActivity::class.java).putExtra("address", device.address).putExtra("receiver", kind == DeviceKind.RECEIVER).putExtra("maintenance", maintenance)); true
            } else false
        }
        slotAdapter = SlotAdapter(::onSlotClicked, ::setReceiverSlotRate)
        binding.slotList.layoutManager = GridLayoutManager(this, 2)
        binding.slotList.adapter = slotAdapter
        val receiver = kind == DeviceKind.RECEIVER
        binding.slotList.visibility = if (receiver) View.VISIBLE else View.GONE
        binding.receiverActions.visibility = if (receiver) View.VISIBLE else View.GONE
        binding.sensorLive.visibility = if (receiver) View.GONE else View.VISIBLE
        binding.sensorPanel.visibility = if (receiver) View.GONE else View.VISIBLE
        binding.rateCard.visibility = if (receiver) View.GONE else View.VISIBLE
        binding.workModeCard.visibility = if (receiver) View.GONE else View.VISIBLE
        renderSummary()
        binding.readConfig.setOnClickListener { if (receiver) readAllConfiguration() else readSensorConfiguration(true) }
        binding.readSensorInfo.setOnClickListener { readSensorConfiguration(true) }
        binding.refreshData.setOnClickListener { app.ble.refreshSubscriptions() }
        binding.editBindings.setOnClickListener { scanNextBinding() }
        binding.submitBindings.setOnClickListener { submitBindingEditors() }
        binding.slowRate.setOnClickListener { setSensorRate(false) }
        binding.fastRate.setOnClickListener { setSensorRate(true) }
        binding.normalPower.setOnClickListener { setSensorPowerMode(false) }
        binding.lowPower.setOnClickListener { setSensorPowerMode(true) }
        binding.sleepButton.setOnClickListener { sleepSensor() }
        binding.markZero.setOnClickListener { markZero() }
        binding.writeMac.setOnClickListener { writeMacFromInput() }
        binding.danger.setOnClickListener { showSetReceiverId() }
        binding.clearAllButton.setOnClickListener { clearAll() }
        binding.ota.setOnClickListener { chooseOtaSource() }
        binding.siteMode.setOnClickListener { if (maintenance) leaveMaintenance() else requestMaintenance() }
        binding.maintainer.visibility = View.GONE
        if (app.preferences.maintenanceMode) enterMaintenance()
        else updateMaintenanceVisibility()
        buildBindingEditors()
    }

    override fun onStart() {
        super.onStart()
        app.ble.addListener(this)
        if (app.ble.activeDevice?.address != device.address || app.ble.phase !in setOf(LinkPhase.ONLINE, LinkPhase.STALE, LinkPhase.DFU)) app.ble.connect(device)
        else onPhase(app.ble.phase)
        refreshHandler.post(ageRefresh)
    }
    override fun onStop() { refreshHandler.removeCallbacks(ageRefresh); app.ble.removeListener(this); super.onStop() }
    override fun onDestroy() { if (isFinishing && !isChangingConfigurations) app.ble.disconnect(); super.onDestroy() }

    override fun onPhase(phase: LinkPhase, message: String?) {
        binding.statusTitle.text = phaseText(phase)
        if (device.kind != DeviceKind.RECEIVER) binding.statusDetail.text = device.address
        val color = when (phase) { LinkPhase.ONLINE -> R.attr.appPositive; LinkPhase.STALE, LinkPhase.RECONNECTING, LinkPhase.CONNECTING, LinkPhase.DISCOVERING, LinkPhase.SUBSCRIBING, LinkPhase.DFU -> R.attr.appWarning; LinkPhase.FAULT -> R.attr.appDanger; else -> R.attr.appTextSecondary }
        binding.statusIcon.setTextColor(themeColor(color))
        binding.statusTitle.setTextColor(themeColor(color))
        if (phase == LinkPhase.ONLINE && device.kind != DeviceKind.RECEIVER && sensorInfo == null) readSensorConfiguration(false)
        markStale(phase == LinkPhase.STALE)
        renderSummary()
    }
    override fun onRssi(rssi: Int) { currentRssi = rssi; binding.rssi.text = "$rssi dBm" }
    override fun onReceiverStatus(status: ReceiverStatus) {
        receiverStatus = status
        for (i in slots.indices) slots[i] = slots[i].copy(fast = status.fastMask and (1 shl i) != 0)
        renderSummary()
        binding.statusDetail.text = "ID ${status.receiverId} · 绑定 ${Integer.bitCount(status.boundMask)} · 在线 ${Integer.bitCount(status.onlineMask)} · 有效 ${Integer.bitCount(status.validMask)}"
        submitSlots()
    }
    override fun onStream(reading: StreamReading) {
        val old = slots[reading.slot]
        slots[reading.slot] = old.copy(reading = reading.payload, stale = false)
        lifecycleScope.launch(Dispatchers.IO) { app.history.add(device.address, reading.slot, old.binding?.type ?: old.info?.sensorType ?: 0, reading.payload) }
        evaluateAlarm(reading.slot, if ((old.binding?.type ?: old.info?.sensorType) == DeviceProtocol.TYPE_TILT) reading.payload.xAngle / 10.0 else reading.payload.pressure / 10.0)
        submitSlots()
        renderSummary()
    }
    override fun onReceiverResponse(response: ReceiverResponse) {
        responseWaiter?.takeIf { it.opcode == response.opcode && (it.slot == null || it.slot == response.slot) }?.let {
            responseWaiter = null; it.deferred.complete(response)
        }
    }
    override fun onSensorPayload(payload: SensorPayload) {
        sensorPayload = payload; lifecycleScope.launch(Dispatchers.IO) { app.history.add(device.address, -1, sensorInfo?.sensorType ?: 0, payload) }
        evaluateAlarm(-1, if (sensorInfo?.sensorType == DeviceProtocol.TYPE_PRESSURE || device.kind == DeviceKind.PRESSURE) payload.pressure / 10.0 else payload.xAngle / 10.0)
        renderSensor()
    }

    private fun readAllConfiguration() = lifecycleScope.launch {
        runOperation("读取配置") {
            for (slot in 0 until DeviceProtocol.SLOT_COUNT) {
                val response = sendConfirmed(DeviceProtocol.getSlot(slot), DeviceProtocol.Op.GET_SLOT, slot)
                slots[slot] = slots[slot].copy(binding = if (response.type == 0) null else BindingConfig(slot, response.type, response.sensorId, response.mac))
                if (response.type != 0) {
                    val info = runCatching { sendConfirmed(DeviceProtocol.getSensorInfo(slot), DeviceProtocol.Op.GET_SENSOR_INFO, slot).sensorInfo }.getOrNull()
                    slots[slot] = slots[slot].copy(info = info)
                }
            }
            submitSlots()
            val reports = AcceptanceReport.create(this@DeviceActivity, device.address, receiverStatus, slots)
            val bound = slots.filter { it.binding != null }
            val passed = bound.isNotEmpty() && bound.all { it.reading != null && !it.stale && receiverStatus?.online(it.slot) == true && receiverStatus?.valid(it.slot) == true }
            app.audit.record("配置验收", if (passed) "通过" else "未通过", device.address)
            Snackbar.make(binding.root, "验收完成，已生成PDF和JSON", Snackbar.LENGTH_LONG).setAction("分享") { shareFiles(reports) }.show()
            buildBindingEditors()
        }
    }

    private suspend fun sendConfirmed(command: ByteArray, opcode: Int, slot: Int? = null): ReceiverResponse {
        responseWaiter?.deferred?.cancel()
        val waiter = ResponseWaiter(opcode, slot, CompletableDeferred())
        responseWaiter = waiter
        try {
            app.ble.write(DeviceProtocol.RX_SERVICE, DeviceProtocol.RX_CMD, command)
            val response = withTimeout(2500) { waiter.deferred.await() }
            if (response.status != 0) error("设备拒绝命令(${response.status})")
            return response
        } finally { if (responseWaiter === waiter) responseWaiter = null }
    }

    private fun selectSlotToEdit() {
        MaterialAlertDialogBuilder(this).setTitle("选择槽位")
            .setItems(Array(8) { "槽 ${it + 1} · ${slots[it].binding?.mac ?: "未绑定"}" }) { _, index -> showBindingEditor(index) }.show()
    }

    private fun showBindingEditor(slot: Int) {
        val positions = positionOptions()
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 8, 48, 0) }
        val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@DeviceActivity, android.R.layout.simple_spinner_dropdown_item, positions.map { it.label }) }
        val mac = EditText(this).apply { hint = "AA:BB:CC:DD:EE:FF"; setText(slots[slot].binding?.mac.orEmpty()); inputType = InputType.TYPE_CLASS_TEXT }
        val scan = Button(this).apply { text = "扫描二维码"; setOnClickListener { pendingMacInput = mac; qrLauncher.launch(null) } }
        container.addView(spinner); container.addView(mac); container.addView(scan)
        MaterialAlertDialogBuilder(this).setTitle("槽 ${slot + 1} 绑定").setView(container)
            .setNegativeButton("取消", null).setNeutralButton("清除") { _, _ -> clearSlot(slot) }
            .setPositiveButton("保存") { _, _ -> saveBinding(slot, positions[spinner.selectedItemPosition], mac.text.toString()) }.show()
    }

    private fun saveBinding(slot: Int, position: PositionOption, macText: String) = lifecycleScope.launch {
        val mac = DeviceProtocol.parseMac(macText) ?: return@launch toast("MAC格式错误")
        if (slots.any { it.slot != slot && it.binding?.mac.equals(DeviceProtocol.bytesToMac(mac), true) }) return@launch toast("MAC已绑定到其他槽位")
        if (slots.any { item -> item.slot != slot && item.binding?.let { it.type == position.type && it.sensorId == position.id } == true }) return@launch toast("该位置已被其他槽位使用")
        runOperation("保存绑定") {
            sendConfirmed(DeviceProtocol.setSlot(slot, position.type, position.id, mac), DeviceProtocol.Op.SET_SLOT, slot)
            slots[slot] = slots[slot].copy(binding = BindingConfig(slot, position.type, position.id, DeviceProtocol.bytesToMac(mac)))
            submitSlots(); app.audit.record("绑定", "成功", device.address, "slot=${slot + 1},mac=${DeviceProtocol.bytesToMac(mac)}")
        }
    }

    private fun clearSlot(slot: Int) = lifecycleScope.launch { confirm("清除槽 ${slot + 1}", "该槽传感器将被移除") {
        runOperation("清除槽位") { sendConfirmed(DeviceProtocol.clearSlot(slot), DeviceProtocol.Op.CLEAR_SLOT, slot); slots[slot] = SlotState(slot); submitSlots(); app.audit.record("清除槽位", "成功", device.address, "slot=${slot + 1}") }
    } }

    private fun onSlotClicked(slot: SlotState) {
        if (slot.binding == null) return showBindingEditor(slot.slot)
        MaterialAlertDialogBuilder(this).setTitle("槽 ${slot.slot + 1} 上报速率").setItems(arrayOf("慢速 1s", "快速 100ms")) { _, which ->
            lifecycleScope.launch { runOperation("切换速率") { sendConfirmed(DeviceProtocol.setRate(slot.slot, which == 1), DeviceProtocol.Op.SET_RATE, slot.slot); slots[slot.slot] = slot.copy(fast = which == 1); submitSlots() } }
        }.show()
    }

    private fun setReceiverSlotRate(slot: SlotState, fast: Boolean) {
        lifecycleScope.launch { runOperation("切换速率") {
            sendConfirmed(DeviceProtocol.setRate(slot.slot, fast), DeviceProtocol.Op.SET_RATE, slot.slot)
            slots[slot.slot] = slot.copy(fast = fast)
            submitSlots()
        } }
    }

    private fun buildBindingEditors() {
        if (!::device.isInitialized || device.kind != DeviceKind.RECEIVER) return
        binding.bindingRows.removeAllViews()
        bindingEditors.clear()
        val options = positionOptions()
        slots.forEach { slot ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 3, 0, 3)
            }
            val number = TextView(this).apply {
                text = "${slot.slot + 1}"
                gravity = android.view.Gravity.CENTER
                setTextColor(themeColor(R.attr.appTextSecondary))
            }
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@DeviceActivity, android.R.layout.simple_spinner_dropdown_item, options.map { it.label })
                val selected = slot.binding?.let { binding -> options.indexOfFirst { it.type == binding.type && it.id == binding.sensorId } } ?: 0
                setSelection(selected.coerceAtLeast(0))
            }
            val mac = EditText(this).apply {
                hint = "AA:BB:CC:DD:EE:FF"
                setText(slot.binding?.mac.orEmpty())
                textSize = 12f
                singleLine = true
                setTextColor(themeColor(R.attr.appTextPrimary))
                setHintTextColor(themeColor(R.attr.appTextSecondary))
            }
            val scan = Button(this).apply {
                text = "扫"
                textSize = 12f
                setOnClickListener { pendingMacInput = mac; qrLauncher.launch(null) }
            }
            row.addView(number, LinearLayout.LayoutParams(dp(28), dp(46)))
            row.addView(spinner, LinearLayout.LayoutParams(dp(112), dp(46)))
            row.addView(mac, LinearLayout.LayoutParams(0, dp(46), 1f))
            row.addView(scan, LinearLayout.LayoutParams(dp(48), dp(46)))
            binding.bindingRows.addView(row)
            bindingEditors[slot.slot] = spinner to mac
        }
    }

    private fun scanNextBinding() {
        val target = bindingEditors.entries.firstOrNull { it.value.second.text.isNullOrBlank() } ?: bindingEditors.entries.firstOrNull() ?: return
        pendingMacInput = target.value.second
        qrLauncher.launch(null)
    }

    private fun submitBindingEditors() = lifecycleScope.launch {
        val options = positionOptions()
        runOperation("下发绑定") {
            bindingEditors.forEach { (slot, controls) ->
                val text = controls.second.text.toString().trim()
                if (text.isBlank()) {
                    if (slots[slot].binding != null) {
                        sendConfirmed(DeviceProtocol.clearSlot(slot), DeviceProtocol.Op.CLEAR_SLOT, slot)
                        slots[slot] = SlotState(slot)
                    }
                } else {
                    val mac = DeviceProtocol.parseMac(text) ?: error("槽 ${slot + 1} MAC格式错误")
                    val position = options[controls.first.selectedItemPosition]
                    sendConfirmed(DeviceProtocol.setSlot(slot, position.type, position.id, mac), DeviceProtocol.Op.SET_SLOT, slot)
                    slots[slot] = slots[slot].copy(binding = BindingConfig(slot, position.type, position.id, DeviceProtocol.bytesToMac(mac)))
                }
            }
            submitSlots()
            buildBindingEditors()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun readSensorConfiguration(showResult: Boolean) = lifecycleScope.launch {
        runCatching {
            sensorInfo = DeviceProtocol.parseSensorInfo(app.ble.read(DeviceProtocol.SN_CONFIG_SERVICE, DeviceProtocol.SN_INFO))
            renderSensor(); if (showResult) toast("设备信息已读取")
        }.onFailure { if (showResult) toast(it.message ?: "读取失败") }
    }
    private fun setSensorRate(fast: Boolean) = lifecycleScope.launch { runOperation("切换速率") { app.ble.setSensorRate(fast); app.audit.record("上报速率", "成功", device.address, if (fast) "100ms" else "1s") } }
    private fun setSensorPowerMode(low: Boolean) { if (!requireMaintenance()) return; lifecycleScope.launch { runOperation("切换功耗") { app.ble.setPowerMode(low); sensorInfo = sensorInfo?.copy(powerMode = if (low) 1 else 0); renderSensor(); app.audit.record("功耗模式", "成功", device.address, if (low) "低功耗" else "普通") } } }
    private fun markZero() {
        if (!requireMaintenance()) return
        val payload = sensorPayload ?: return toast("等待倾角数据")
        app.preferences.setTiltZero(device.address, payload.xAngle, payload.yAngle); app.audit.record("倾角置零", "成功", device.address, "x=${payload.xAngle},y=${payload.yAngle}"); renderSensor(); toast("已标记零点")
    }

    private fun renderSensor() {
        val payload = sensorPayload
        val info = sensorInfo
        val liveValue = if (payload != null) {
            val (zx, zy) = app.preferences.tiltZero(device.address)
            if (info?.sensorType == DeviceProtocol.TYPE_PRESSURE || device.kind == DeviceKind.PRESSURE) "%.2f MPa".format(payload.pressure / 10.0)
            else "X %.1f°  Y %.1f°".format((payload.xAngle - zx) / 10.0, (payload.yAngle - zy) / 10.0)
        } else "等待实时数据"
        binding.liveValue.text = liveValue
        binding.liveType.text = when (info?.sensorType ?: if (device.kind == DeviceKind.TILT) DeviceProtocol.TYPE_TILT else DeviceProtocol.TYPE_PRESSURE) { DeviceProtocol.TYPE_TILT -> "倾角"; else -> "压力" }
        binding.uptimeValue.text = "运行秒 ${payload?.uptime24 ?: "--"}"
        binding.livePa5.text = "PA5 ${payload?.pa5?.let { if (it) "高" else "低" } ?: "--"}"
        binding.liveVoltage.text = "电压 ${payload?.let { DeviceProtocol.voltage(it, info) }?.let { "%.1fV".format(it) } ?: "--"}"
        binding.rawValue.text = "Raw ${payload?.raw ?: "--"}"
        binding.infoWork.text = info?.workSeconds?.let(::formatDuration) ?: "--"
        binding.infoVoltage.text = info?.voltageMv?.takeIf { it > 0 }?.let { "%.2f V".format(it / 1000.0) } ?: "--"
        binding.infoBoot.text = info?.bootSeconds?.let(::formatDuration) ?: "--"
        binding.infoStatus.text = when { info == null -> "--"; info.online && info.errors == 0 -> "正常"; info.online -> "异常(${info.errors})"; else -> "离线" }
        binding.infoPa5.text = info?.pa5?.let { if (it != 0) "高" else "低" } ?: "--"
        binding.infoRssi.text = info?.rssi?.let { "$it dBm" } ?: currentRssi?.let { "$it dBm" } ?: "--"
        binding.normalPower.isEnabled = maintenance
        binding.lowPower.isEnabled = maintenance
        binding.normalPower.backgroundTintList = ColorStateList.valueOf(themeColor(if (info?.powerMode == 1) R.attr.appSurfaceRaised else R.attr.appPositive))
        binding.lowPower.backgroundTintList = ColorStateList.valueOf(themeColor(if (info?.powerMode == 1) R.attr.appPositive else R.attr.appSurfaceRaised))
        binding.slowRate.backgroundTintList = ColorStateList.valueOf(themeColor(if (info?.rate == 1) R.attr.appSurfaceRaised else R.attr.appPositive))
        binding.fastRate.backgroundTintList = ColorStateList.valueOf(themeColor(if (info?.rate == 1) R.attr.appPositive else R.attr.appSurfaceRaised))
        binding.powerHint.text = if (maintenance) (if (info?.powerMode == 1) "当前低功耗模式" else "当前普通模式") else "维护模式下可切换工作模式"
        val (zx, zy) = app.preferences.tiltZero(device.address)
        binding.zeroText.text = "零点: X %.1f° Y %.1f°".format(zx / 10.0, zy / 10.0)
        renderSummary()
    }

    private fun formatDuration(seconds: Long): String {
        val days = seconds / 86400
        val hours = seconds % 86400 / 3600
        val minutes = seconds % 3600 / 60
        return if (days > 0) "${days}天${hours}小时" else if (hours > 0) "${hours}小时${minutes}分" else "${minutes}分${seconds % 60}秒"
    }

    private fun renderSummary() {
        if (device.kind == DeviceKind.RECEIVER) {
            val status = receiverStatus
            val bound = status?.let { Integer.bitCount(it.boundMask) } ?: 0
            val online = status?.let { Integer.bitCount(it.onlineMask) } ?: 0
            val valid = status?.let { Integer.bitCount(it.validMask) } ?: 0
            val lowVoltage = slots.count { slot ->
                DeviceProtocol.voltage(slot.reading ?: return@count false, slot.info)?.let { it > 0.0 && it < 3.3 } == true
            }
            binding.summaryLabel1.text = "绑定"
            binding.summaryLabel2.text = "在线"
            binding.summaryLabel3.text = "异常"
            binding.summaryLabel4.text = "低电"
            binding.primaryValue.text = bound.toString()
            binding.secondaryValue.text = online.toString()
            binding.tertiaryValue.text = ((bound - online).coerceAtLeast(0) + (online - valid).coerceAtLeast(0)).toString()
            binding.quaternaryValue.text = lowVoltage.toString()
        } else {
            val online = app.ble.phase == LinkPhase.ONLINE || app.ble.phase == LinkPhase.STALE
            binding.summaryLabel1.text = "连接"
            binding.summaryLabel2.text = "电压"
            binding.summaryLabel3.text = "模式"
            binding.summaryLabel4.text = "PA5"
            binding.primaryValue.text = if (online) "在线" else "离线"
            binding.secondaryValue.text = sensorPayload?.let { DeviceProtocol.voltage(it, sensorInfo) }?.let { "%.1fV".format(it) } ?: "--"
            binding.tertiaryValue.text = if (sensorInfo?.powerMode == 1) "低功耗" else "普通"
            binding.quaternaryValue.text = sensorPayload?.pa5?.let { if (it) "高" else "低" } ?: "--"
        }
    }

    private fun showDangerActions() {
        if (!requireMaintenance()) return
        val receiver = device.kind == DeviceKind.RECEIVER
        val actions = if (receiver) arrayOf("修改接收器ID", "清除全部绑定") else arrayOf("修改传感器MAC", "进入休眠")
        MaterialAlertDialogBuilder(this).setTitle("危险操作").setItems(actions) { _, which ->
            if (receiver && which == 0) showSetReceiverId() else if (receiver) clearAll() else if (which == 0) showChangeMac() else sleepSensor()
        }.show()
    }
    private fun showSetReceiverId() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "0-65535" }
        MaterialAlertDialogBuilder(this).setTitle("修改接收器ID").setView(input).setNegativeButton("取消", null).setPositiveButton("确认") { _, _ ->
            val id = input.text.toString().toIntOrNull(); if (id == null || id !in 0..65535) toast("ID范围错误") else lifecycleScope.launch { runOperation("修改ID") { sendConfirmed(DeviceProtocol.setId(id), DeviceProtocol.Op.SET_ID); app.audit.record("修改ID", "成功", device.address, id.toString()) } }
        }.show()
    }
    private fun clearAll() = lifecycleScope.launch { confirm("清除全部绑定", "此操作会移除8个槽位的全部绑定") { runOperation("清除全部") { sendConfirmed(DeviceProtocol.clearAll(), DeviceProtocol.Op.CLEAR_ALL); slots.indices.forEach { slots[it] = SlotState(it) }; submitSlots(); app.audit.record("清除全部", "成功", device.address) } } }
    private fun showChangeMac() {
        val input = EditText(this).apply { hint = "AA:BB:CC:DD:EE:FF" }
        MaterialAlertDialogBuilder(this).setTitle("修改传感器MAC").setView(input).setNegativeButton("取消", null).setPositiveButton("写入并重启") { _, _ ->
            val mac = DeviceProtocol.parseMac(input.text.toString()) ?: return@setPositiveButton toast("MAC格式错误")
            lifecycleScope.launch { runOperation("修改MAC") { app.ble.write(DeviceProtocol.SN_CONFIG_SERVICE, DeviceProtocol.SN_MAC, mac.reversedArray()); app.audit.record("修改MAC", "已发送", device.address, DeviceProtocol.bytesToMac(mac)) } }
        }.show()
    }
    private fun writeMacFromInput() {
        val mac = DeviceProtocol.parseMac(binding.macInput.text.toString()) ?: return toast("MAC格式错误")
        lifecycleScope.launch { runOperation("修改MAC") { app.ble.write(DeviceProtocol.SN_CONFIG_SERVICE, DeviceProtocol.SN_MAC, mac.reversedArray()); app.audit.record("修改MAC", "已发送", device.address, DeviceProtocol.bytesToMac(mac)) } }
    }
    private fun sleepSensor() = lifecycleScope.launch { confirm("进入休眠", "设备将进入EM4并断开，需要外部唤醒源恢复") { runOperation("进入休眠") { app.ble.write(DeviceProtocol.SN_CONFIG_SERVICE, DeviceProtocol.SN_RATE, byteArrayOf(2)); app.audit.record("休眠", "已发送", device.address) } } }

    private fun chooseOtaSource() {
        if (!requireMaintenance()) return
        MaterialAlertDialogBuilder(this).setTitle("固件来源").setItems(arrayOf("本地GBL文件", "HTTPS地址")) { _, which ->
            if (which == 0) firmwarePicker.launch(arrayOf("application/octet-stream", "*/*")) else showHttpsInput()
        }.show()
    }
    private fun showHttpsInput() {
        val input = EditText(this).apply { hint = "https://example.com/firmware.gbl"; inputType = InputType.TYPE_TEXT_VARIATION_URI }
        MaterialAlertDialogBuilder(this).setTitle("HTTPS固件地址").setView(input).setNegativeButton("取消", null).setPositiveButton("继续") { _, _ -> beginOta(FirmwareSource.Https(input.text.toString().trim())) }.show()
    }
    private fun beginOta(source: FirmwareSource) {
        confirm("开始固件升级", "设备 ${device.address}\nRSSI ${currentRssi ?: "--"} dBm\n升级期间请勿离开设备") {
            lifecycleScope.launch {
                binding.ota.isEnabled = false
                runCatching { OtaEngine(this@DeviceActivity, app.ble, app.audit).upgrade(source) { state -> runOnUiThread { renderOta(state) } } }
                    .onFailure { toast(it.message ?: "升级失败") }
                binding.ota.isEnabled = true
            }
        }
    }
    private fun renderOta(state: OtaState) {
        binding.otaProgress.visibility = if (state is OtaState.Transferring) View.VISIBLE else View.GONE
        if (state is OtaState.Transferring) binding.otaProgress.progress = state.percent
        binding.otaStatus.text = when (state) { is OtaState.Preparing -> state.message; is OtaState.Transferring -> "升级中 ${state.percent}%"; is OtaState.Verifying -> state.message; is OtaState.Complete -> "升级完成 ${state.version.orEmpty()}"; is OtaState.Failed -> "升级失败：${state.message}"; OtaState.Idle -> "使用 Silicon Labs 官方 OTA 流程" }
    }

    private fun requestMaintenance() {
        val input = EditText(this).apply { hint = "维护密码"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        MaterialAlertDialogBuilder(this).setTitle("进入维护模式").setView(input).setNegativeButton("取消", null).setPositiveButton("验证") { _, _ ->
            when (val result = app.preferences.verifyPassword(input.text.toString().toCharArray())) {
                is com.zg.sensormonitor.data.PasswordResult.Valid -> enterMaintenance()
                is com.zg.sensormonitor.data.PasswordResult.Invalid -> toast("密码错误，剩余${result.attemptsBeforeDelay}次后锁定")
                is com.zg.sensormonitor.data.PasswordResult.Locked -> toast("已锁定，请${result.seconds}秒后重试")
            }
        }.show()
    }
    private fun forceChangePassword() {
        val input = EditText(this).apply { hint = "新密码（至少8位）"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("首次使用必须修改默认密码").setView(input).setCancelable(false).setPositiveButton("保存", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { runCatching { app.preferences.changePassword(input.text.toString().toCharArray()) }.onSuccess { dialog.dismiss(); enterMaintenance() }.onFailure { toast(it.message ?: "密码无效") } } }; dialog.show()
    }
    private fun enterMaintenance() { maintenance = true; app.preferences.maintenanceMode = true; updateMaintenanceVisibility(); slotAdapter.maintenance = true; slotAdapter.notifyDataSetChanged(); app.audit.record("维护模式", "进入", device.address) }
    private fun leaveMaintenance() { maintenance = false; app.preferences.maintenanceMode = false; updateMaintenanceVisibility(); slotAdapter.maintenance = false; slotAdapter.notifyDataSetChanged() }
    private fun updateMaintenanceVisibility() {
        val receiver = device.kind == DeviceKind.RECEIVER
        binding.siteMode.text = if (maintenance) "维护" else "客户"
        binding.siteMode.backgroundTintList = ColorStateList.valueOf(themeColor(if (maintenance) R.attr.appWarning else R.attr.appSurfaceRaised))
        binding.siteMode.setTextColor(themeColor(if (maintenance) R.attr.appBackground else R.attr.appTextPrimary))
        binding.maintenancePanel.visibility = if (maintenance && receiver) View.VISIBLE else View.GONE
        binding.sleepButton.visibility = if (maintenance && !receiver) View.VISIBLE else View.GONE
        binding.zeroCard.visibility = if (maintenance && device.kind != DeviceKind.PRESSURE && !receiver) View.VISIBLE else View.GONE
        binding.macCard.visibility = if (maintenance && !receiver) View.VISIBLE else View.GONE
        binding.otaCard.visibility = if (maintenance) View.VISIBLE else View.GONE
        binding.rawValue.visibility = if (maintenance && !receiver) View.VISIBLE else View.GONE
        binding.normalPower.isEnabled = maintenance
        binding.lowPower.isEnabled = maintenance
    }
    private fun requireMaintenance(): Boolean { if (!maintenance) toast("请先进入维护模式"); return maintenance }

    private fun submitSlots() = slotAdapter.submit(slots.toList())
    private fun markStale(stale: Boolean) { if (stale) { slots.indices.forEach { slots[it] = slots[it].copy(stale = slots[it].reading != null) }; submitSlots() } }
    private fun evaluateAlarm(slot: Int, value: Double) {
        val change = app.alarms.evaluate("${device.address}:$slot", value, app.preferences.alarmPolicy(device.address, slot)) ?: return
        val raised = change == AlarmChange.Raised
        app.audit.record("数据告警", if (raised) "产生" else "恢复", device.address, "slot=$slot value=$value")
        Snackbar.make(binding.root, if (raised) "告警：数值 $value 超出阈值" else "告警已恢复", Snackbar.LENGTH_LONG).show()
    }
    private fun lastDataText() = sensorPayload?.receivedAt?.let { "${(System.currentTimeMillis() - it) / 1000}秒前" } ?: "等待数据"
    private fun phaseText(phase: LinkPhase) = when (phase) { LinkPhase.IDLE -> "未连接"; LinkPhase.SCANNING -> "正在扫描"; LinkPhase.CONNECTING -> "正在连接"; LinkPhase.DISCOVERING -> "正在发现服务"; LinkPhase.SUBSCRIBING -> "正在订阅数据"; LinkPhase.ONLINE -> "设备在线"; LinkPhase.STALE -> "数据超时"; LinkPhase.RECONNECTING -> "正在重连"; LinkPhase.DISCONNECTING -> "正在断开"; LinkPhase.DFU -> "固件升级"; LinkPhase.FAULT -> "设备故障" }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun runOperation(name: String, block: suspend () -> Unit) = lifecycleScope.launch { runCatching { block() }.onSuccess { toast("$name 成功") }.onFailure { app.audit.record(name, "失败", device.address, it.message); toast(it.message ?: "$name 失败") } }
    private fun confirm(title: String, message: String, action: () -> Unit) { MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setNegativeButton("取消", null).setPositiveButton("确认") { _, _ -> action() }.show() }
    private fun shareFiles(files: List<java.io.File>) {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "application/zip"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "分享验收报告"))
    }
    private fun positionOptions(): List<PositionOption> {
        val pressure = listOf("立柱前左", "立柱前右", "立柱后左", "立柱后右", "一级护帮", "二级护帮", "三级护帮", "前梁", "伸缩梁", "平衡上腔", "平衡下腔")
        val tilt = listOf("底座", "顶梁", "前连杆", "一级护帮", "二级护帮", "三级护帮", "前梁", "尾梁", "掩护梁")
        return pressure.mapIndexed { i, s -> PositionOption(DeviceProtocol.TYPE_PRESSURE, i + 1, "压力-$s") } + tilt.mapIndexed { i, s -> PositionOption(DeviceProtocol.TYPE_TILT, i + 1, "倾角-$s") }
    }

    data class ResponseWaiter(val opcode: Int, val slot: Int?, val deferred: CompletableDeferred<ReceiverResponse>)
    data class PositionOption(val type: Int, val id: Int, val label: String)
    companion object { const val EXTRA_ADDRESS = "address"; const val EXTRA_NAME = "name"; const val EXTRA_KIND = "kind" }
}
