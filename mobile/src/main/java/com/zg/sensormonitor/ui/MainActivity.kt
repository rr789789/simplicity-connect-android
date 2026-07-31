package com.zg.sensormonitor.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.zg.sensormonitor.R
import com.zg.sensormonitor.SensorMonitorApplication
import com.zg.sensormonitor.ble.BleCentralManager
import com.zg.sensormonitor.databinding.ActivityMainBinding
import com.zg.sensormonitor.domain.*

class MainActivity : ThemedActivity(), BleCentralManager.Listener {
    private lateinit var binding: ActivityMainBinding
    private val app get() = application as SensorMonitorApplication
    private val devices = linkedMapOf<String, DiscoveredDevice>()
    private lateinit var adapter: DeviceAdapter
    private var scanning = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) startScan() else Snackbar.make(binding.root, R.string.permission_required, Snackbar.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater); setContentView(binding.root)
        adapter = DeviceAdapter(::openDevice)
        binding.deviceList.layoutManager = LinearLayoutManager(this)
        binding.deviceList.adapter = adapter
        binding.scanButton.setOnClickListener { if (scanning) app.ble.stopScan() else ensurePermissions() }
        binding.toolbar.setOnMenuItemClickListener(::onMenu)
        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    override fun onResume() {
        super.onResume(); app.ble.addListener(this); binding.modeBadge.text = if (app.preferences.mineMode) "井下模式" else "普通模式"; ensurePermissions()
    }
    override fun onPause() { app.ble.stopScan(); app.ble.removeListener(this); super.onPause() }

    private fun ensurePermissions() {
        val needed = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startScan() else permissions.launch(missing.toTypedArray())
    }

    private fun startScan() { devices.clear(); refresh(); app.ble.startScan() }
    override fun onDevice(device: DiscoveredDevice) {
        if (device.rssi < app.preferences.rssiThreshold || device.kind == DeviceKind.OTA) return
        devices[device.address] = device; refresh()
    }
    override fun onPhase(phase: LinkPhase, message: String?) {
        scanning = phase == LinkPhase.SCANNING
        binding.scanButton.text = if (scanning) getString(R.string.stop) else getString(R.string.scan)
        binding.statusTitle.text = when (phase) { LinkPhase.SCANNING -> "正在识别附近设备"; LinkPhase.FAULT -> "扫描异常"; else -> "设备现场" }
        binding.statusDetail.text = message ?: if (scanning) "按信号强度排序" else "扫描已停止"
        binding.statusIcon.setTextColor(themeColor(if (phase == LinkPhase.FAULT) R.attr.appDanger else R.attr.appPositive))
    }

    private fun refresh() {
        val key = binding.search.text?.toString()?.trim()?.lowercase().orEmpty()
        val list = devices.values.filter { key.isBlank() || it.name.lowercase().contains(key) || it.address.lowercase().contains(key) }
            .sortedWith(compareBy<DiscoveredDevice> { it.kind.ordinal }.thenByDescending { it.rssi })
        adapter.submit(list); binding.deviceCount.text = "${list.size} 台"
        binding.empty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.deviceList.visibility = if (list.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun openDevice(device: DiscoveredDevice) {
        app.preferences.saveRecent(device.address, device.name); app.ble.stopScan()
        startActivity(Intent(this, DeviceActivity::class.java).apply {
            putExtra(DeviceActivity.EXTRA_ADDRESS, device.address); putExtra(DeviceActivity.EXTRA_NAME, device.name); putExtra(DeviceActivity.EXTRA_KIND, device.kind.name)
        })
    }
    private fun onMenu(item: MenuItem): Boolean = if (item.itemId == R.id.action_settings) { startActivity(Intent(this, SettingsActivity::class.java)); true } else false
}
