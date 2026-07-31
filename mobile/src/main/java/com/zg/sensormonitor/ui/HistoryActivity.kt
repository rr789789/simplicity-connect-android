package com.zg.sensormonitor.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zg.sensormonitor.SensorMonitorApplication
import com.zg.sensormonitor.data.AlarmPolicy
import com.zg.sensormonitor.databinding.ActivityHistoryBinding

class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private val app get() = application as SensorMonitorApplication
    private lateinit var address: String
    private var receiver = false
    private var maintenance = false
    private val selectedSlot get() = if (receiver) binding.slot.selectedItemPosition else -1
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater); setContentView(binding.root)
        address = intent.getStringExtra("address") ?: return finish()
        receiver = intent.getBooleanExtra("receiver", false)
        maintenance = intent.getBooleanExtra("maintenance", false)
        binding.toolbar.subtitle = address; binding.toolbar.setNavigationOnClickListener { finish() }
        binding.slot.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, if (receiver) List(8) { "槽 ${it + 1}" } else listOf("直连传感器"))
        binding.slot.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refresh()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.alarm.setOnClickListener { editAlarm() }
        binding.alarm.visibility = if (maintenance) View.VISIBLE else View.GONE
        binding.export.setOnClickListener { export() }
    }
    private fun refresh() {
        val points = app.history.recent(address, selectedSlot)
        binding.chart.submit(points)
        binding.summary.text = if (points.isEmpty()) "暂无历史数据" else "${points.size}分钟 · 最低 %.2f · 最高 %.2f · 最新 %.2f".format(points.minOf { it.min }, points.maxOf { it.max }, points.last().last)
    }
    private fun editAlarm() {
        val current = app.preferences.alarmPolicy(address, selectedSlot)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 0, 48, 0) }
        val low = EditText(this).apply { hint = "低阈值（留空关闭）"; setText(current.low?.toString().orEmpty()); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED }
        val high = EditText(this).apply { hint = "高阈值（留空关闭）"; setText(current.high?.toString().orEmpty()); inputType = low.inputType }
        val dwell = EditText(this).apply { hint = "持续秒数"; setText((current.dwellMs / 1000).toString()); inputType = InputType.TYPE_CLASS_NUMBER }
        val hysteresis = EditText(this).apply { hint = "恢复回差"; setText(current.hysteresis.toString()); inputType = low.inputType }
        box.addView(low); box.addView(high); box.addView(dwell); box.addView(hysteresis)
        MaterialAlertDialogBuilder(this).setTitle("告警阈值").setView(box).setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
            val policy = AlarmPolicy(low.text.toString().toDoubleOrNull(), high.text.toString().toDoubleOrNull(), (dwell.text.toString().toLongOrNull() ?: 3).coerceIn(1, 300) * 1000, (hysteresis.text.toString().toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0))
            if (policy.low != null && policy.high != null && policy.low >= policy.high) toast("低阈值必须小于高阈值") else { app.preferences.setAlarmPolicy(address, selectedSlot, policy); app.audit.record("告警阈值", "已修改", address, "slot=$selectedSlot low=${policy.low} high=${policy.high}"); toast("已保存") }
        }.show()
    }
    private fun export() {
        val file = app.history.exportCsv(this, address, selectedSlot)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "分享历史CSV"))
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
