package com.zg.sensormonitor.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.zg.sensormonitor.R
import com.zg.sensormonitor.databinding.RowSlotBinding
import com.zg.sensormonitor.domain.SlotState
import com.zg.sensormonitor.protocol.DeviceProtocol
import java.util.concurrent.TimeUnit

class SlotAdapter(private val onClick: (SlotState) -> Unit) : RecyclerView.Adapter<SlotAdapter.Holder>() {
    private var items = List(DeviceProtocol.SLOT_COUNT) { SlotState(it) }
    var maintenance = false
    fun submit(value: List<SlotState>) { items = value; notifyDataSetChanged() }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(RowSlotBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: RowSlotBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(slot: SlotState) = with(binding) {
            slotNumber.text = "槽 ${slot.slot + 1}${if (slot.fast) " · 快" else " · 慢"}"
            val status = when { slot.binding == null -> "未绑定"; slot.stale -> "超时"; slot.reading != null -> "正常"; else -> "等待" }
            state.text = status
            state.setTextColor(root.context.themeColor(when (status) { "正常" -> R.attr.appPositive; "超时" -> R.attr.appWarning; else -> R.attr.appTextSecondary }))
            value.text = formatValue(slot)
            position.text = slot.binding?.let { "${positionName(it.type, it.sensorId)} · ${it.mac}" } ?: "未配置位置"
            val voltage = slot.reading?.let { DeviceProtocol.voltage(it, slot.info) }?.let { "%.1fV".format(it) } ?: "--"
            diagnostics.text = "电压 $voltage  RSSI ${slot.info?.rssi?.let { "$it dBm" } ?: "--"}  PA5 ${slot.reading?.pa5?.let { if (it) "高" else "低" } ?: "--"}"
            updated.text = slot.reading?.let { "${TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - it.receivedAt).coerceAtLeast(0)}秒前" } ?: "无数据"
            root.setOnClickListener { if (maintenance) onClick(slot) }
        }
    }

    private fun formatValue(slot: SlotState): String {
        val p = slot.reading ?: return "--"
        return when (slot.binding?.type ?: slot.info?.sensorType) {
            DeviceProtocol.TYPE_PRESSURE -> "%.2f MPa".format(p.pressure / 10.0)
            DeviceProtocol.TYPE_TILT -> "X %.1f°  Y %.1f°".format(p.xAngle / 10.0, p.yAngle / 10.0)
            else -> p.pressure.toString()
        }
    }

    private fun positionName(type: Int, id: Int): String {
        val pressure = listOf("", "立柱前左", "立柱前右", "立柱后左", "立柱后右", "一级护帮", "二级护帮", "三级护帮", "前梁", "伸缩梁", "平衡上腔", "平衡下腔")
        val tilt = listOf("", "底座", "顶梁", "前连杆", "一级护帮", "二级护帮", "三级护帮", "前梁", "尾梁", "掩护梁")
        val name = when (type) { DeviceProtocol.TYPE_PRESSURE -> pressure.getOrNull(id); DeviceProtocol.TYPE_TILT -> tilt.getOrNull(id); else -> null }
        return "${if (type == DeviceProtocol.TYPE_TILT) "倾角" else "压力"}-${name ?: "点$id"}"
    }
}
