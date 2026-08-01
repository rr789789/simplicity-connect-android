package com.zg.sensormonitor.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.zg.sensormonitor.R
import com.zg.sensormonitor.SensorMonitorApplication
import com.zg.sensormonitor.ble.BleCentralManager
import com.zg.sensormonitor.data.PasswordResult
import com.zg.sensormonitor.databinding.ActivityMainBinding
import com.zg.sensormonitor.databinding.RowDeviceBinding
import com.zg.sensormonitor.domain.DeviceKind
import com.zg.sensormonitor.domain.DiscoveredDevice
import com.zg.sensormonitor.domain.LinkPhase

class MainActivity : ThemedActivity(), BleCentralManager.Listener {
    private lateinit var binding: ActivityMainBinding
    private val app get() = application as SensorMonitorApplication
    private val devices = linkedMapOf<String, DiscoveredDevice>()
    private var scanning = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) startScan() else Snackbar.make(binding.root, R.string.permission_required, Snackbar.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.scanButton.setOnClickListener { if (scanning) app.ble.stopScan() else ensurePermissions() }
        binding.settingsButton.setOnClickListener { showScanSettings() }
        binding.siteMode.setOnClickListener { toggleSiteMode() }
        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    override fun onResume() {
        super.onResume()
        app.ble.addListener(this)
        renderSiteMode()
        renderRecentReceiver()
        ensurePermissions()
    }

    override fun onPause() {
        app.ble.stopScan()
        app.ble.removeListener(this)
        super.onPause()
    }

    private fun ensurePermissions() {
        val needed = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startScan() else permissions.launch(missing.toTypedArray())
    }

    private fun startScan() {
        devices.clear()
        refresh()
        app.ble.startScan()
    }

    override fun onDevice(device: DiscoveredDevice) {
        if (device.rssi < app.preferences.rssiThreshold || device.kind == DeviceKind.OTA) return
        devices[device.address] = device
        refresh()
    }

    override fun onPhase(phase: LinkPhase, message: String?) {
        scanning = phase == LinkPhase.SCANNING
        binding.scanButton.text = if (scanning) getString(R.string.stop) else getString(R.string.scan)
        if (phase == LinkPhase.FAULT) {
            binding.statusDetail.text = message ?: "扫描异常"
        } else {
            updateOverviewText()
        }
        binding.empty.text = if (scanning) "正在搜索…" else if (devices.isEmpty()) "点击扫描搜索设备" else "全部被筛选隐藏，放宽条件"
    }

    private fun refresh() {
        val key = binding.search.text?.toString()?.trim()?.lowercase().orEmpty()
        val shown = devices.values
            .filter { key.isBlank() || it.name.lowercase().contains(key) || it.address.lowercase().contains(key) }
            .sortedByDescending { it.rssi }
        val receivers = shown.filter { it.kind == DeviceKind.RECEIVER }
        val pressures = shown.filter { it.kind == DeviceKind.PRESSURE }
        val tilts = shown.filter { it.kind == DeviceKind.TILT }
        val others = shown.filter { it.kind == DeviceKind.SENSOR }
        renderSection(binding.receiverSection, binding.receiverList, receivers)
        renderSection(binding.pressureSection, binding.pressureList, pressures)
        renderSection(binding.tiltSection, binding.tiltList, tilts)
        renderSection(binding.otherSection, binding.otherList, others)
        binding.receiverCount.text = receivers.size.toString()
        binding.pressureCount.text = pressures.size.toString()
        binding.tiltCount.text = tilts.size.toString()
        binding.strongestRssi.text = shown.maxOfOrNull { it.rssi }?.toString() ?: "--"
        binding.empty.isVisible = shown.isEmpty()
        updateOverviewText(receivers.size, pressures.size + tilts.size + others.size)
    }

    private fun renderSection(section: View, container: LinearLayout, items: List<DiscoveredDevice>) {
        section.isVisible = items.isNotEmpty()
        container.removeAllViews()
        items.forEach { device ->
            val row = RowDeviceBinding.inflate(layoutInflater, container, false)
            bindDeviceRow(row, device)
            container.addView(row.root)
        }
    }

    private fun bindDeviceRow(row: RowDeviceBinding, device: DiscoveredDevice) = with(row) {
        name.text = device.name.ifBlank { DeviceAdapter.kindName(device.kind) }
        address.text = when (device.kind) {
            DeviceKind.RECEIVER -> "${device.address} · 靠近优先"
            else -> "${device.address} · 靠近后点入读取"
        }
        rssi.text = "${device.rssi} dBm"
        kind.text = when (device.kind) { DeviceKind.RECEIVER -> "收"; DeviceKind.PRESSURE -> "压"; DeviceKind.TILT -> "倾"; else -> "感" }
        kind.backgroundTintList = ColorStateList.valueOf(getColor(if (device.kind == DeviceKind.RECEIVER) R.color.normal_positive else R.color.sensor_teal))
        val level = when { device.rssi >= -55 -> 4; device.rssi >= -67 -> 3; device.rssi >= -80 -> 2; else -> 1 }
        listOf(bar1, bar2, bar3, bar4).forEachIndexed { index, bar ->
            bar.backgroundTintList = ColorStateList.valueOf(if (index < level) getColor(R.color.signal_green) else themeColor(R.attr.appOutline))
        }
        root.setOnClickListener { openDevice(device) }
    }

    private fun updateOverviewText(receiverCount: Int = devices.values.count { it.kind == DeviceKind.RECEIVER }, sensorCount: Int = devices.size - receiverCount) {
        binding.statusDetail.text = "${if (scanning) "正在靠近识别…" else "扫描已停止"} · 接收器 $receiverCount · 传感器 $sensorCount"
    }

    private fun openDevice(device: DiscoveredDevice) {
        if (device.kind == DeviceKind.RECEIVER) app.preferences.saveRecentReceiver(device.address, device.name)
        app.ble.stopScan()
        startActivity(Intent(this, DeviceActivity::class.java).apply {
            putExtra(DeviceActivity.EXTRA_ADDRESS, device.address)
            putExtra(DeviceActivity.EXTRA_NAME, device.name)
            putExtra(DeviceActivity.EXTRA_KIND, device.kind.name)
        })
    }

    private fun renderRecentReceiver() {
        val recent = app.preferences.recentDevice()
        binding.recentPanel.isVisible = recent != null
        if (recent == null) return
        binding.recentName.text = recent.second.ifBlank { "AIOT-" }
        binding.recentAddress.text = recent.first
        binding.recentEnter.setOnClickListener { openDevice(DiscoveredDevice(recent.first, recent.second, 0, DeviceKind.RECEIVER)) }
    }

    private fun renderSiteMode() {
        val maintenance = app.preferences.maintenanceMode
        binding.siteMode.text = if (maintenance) "维护模式" else "客户模式"
        binding.siteMode.backgroundTintList = ColorStateList.valueOf(getColor(if (maintenance) R.color.mine_warning else R.color.normal_surface_raised))
        binding.siteMode.setTextColor(getColor(if (maintenance) R.color.normal_warning else R.color.normal_text))
    }

    private fun toggleSiteMode() {
        if (app.preferences.maintenanceMode) {
            app.preferences.maintenanceMode = false
            renderSiteMode()
            return
        }
        val input = EditText(this).apply {
            hint = "维护密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        MaterialAlertDialogBuilder(this).setTitle("进入维护模式").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("进入") { _, _ ->
                when (val result = app.preferences.verifyPassword(input.text.toString().toCharArray())) {
                    is PasswordResult.Valid -> { app.preferences.maintenanceMode = true; renderSiteMode() }
                    is PasswordResult.Invalid -> Snackbar.make(binding.root, "维护密码错误", Snackbar.LENGTH_LONG).show()
                    is PasswordResult.Locked -> Snackbar.make(binding.root, "已锁定 ${result.seconds} 秒", Snackbar.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun showScanSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
        }
        val thresholdLabel = TextView(this).apply {
            setTextColor(themeColor(R.attr.appTextSecondary))
            textSize = 13f
        }
        val threshold = SeekBar(this).apply {
            max = 12
            progress = ((app.preferences.rssiThreshold + 100) / 5).coerceIn(0, 12)
        }
        fun updateThresholdLabel() {
            val value = -100 + threshold.progress * 5
            thresholdLabel.text = "信号强度 ≥ $value dBm${if (value <= -100) "（全部）" else ""}"
        }
        updateThresholdLabel()
        threshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateThresholdLabel()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        val mineMode = SwitchMaterial(this).apply {
            text = "井下高对比模式"
            isChecked = app.preferences.mineMode
            setTextColor(themeColor(R.attr.appTextPrimary))
        }
        content.addView(thresholdLabel)
        content.addView(threshold)
        content.addView(mineMode)
        MaterialAlertDialogBuilder(this)
            .setTitle("扫描设置")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("完成") { _, _ ->
                app.preferences.rssiThreshold = -100 + threshold.progress * 5
                val changed = app.preferences.mineMode != mineMode.isChecked
                app.preferences.mineMode = mineMode.isChecked
                if (changed) recreate() else refresh()
            }.show()
    }
}
