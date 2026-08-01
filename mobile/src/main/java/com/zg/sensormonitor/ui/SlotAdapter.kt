package com.zg.sensormonitor.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.zg.sensormonitor.R
import com.zg.sensormonitor.databinding.RowSlotBinding
import com.zg.sensormonitor.domain.SlotState
import com.zg.sensormonitor.protocol.DeviceProtocol
import java.util.concurrent.TimeUnit

class SlotAdapter(
    private val onClick: (SlotState) -> Unit,
    private val onRate: (SlotState, Boolean) -> Unit
) : RecyclerView.Adapter<SlotAdapter.Holder>() {
    private var items = List(DeviceProtocol.SLOT_COUNT) { SlotState(it) }
    var maintenance = false
    fun submit(value: List<SlotState>) { items = value; notifyDataSetChanged() }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(RowSlotBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: RowSlotBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(slot: SlotState) = with(binding) {
            slotNumber.text = "槽 ${slot.slot + 1}"
            val status = when {
                slot.binding == null -> "未绑定"
                slot.stale || slot.info?.dataTimedOut == true -> "数据超时"
                slot.reading?.readOk == false -> "采样异常"
                slot.reading != null -> "在线"
                else -> "等待连接"
            }
            state.text = status
            state.setTextColor(root.context.themeColor(when (status) { "在线" -> R.attr.appPositive; "数据超时", "采样异常" -> R.attr.appWarning; else -> R.attr.appTextSecondary }))
            value.text = formatValue(slot)
            bindingFoot.visibility = if (slot.binding != null) View.VISIBLE else View.GONE
            position.text = slot.binding?.let { positionName(it.type, it.sensorId) } ?: ""
            mac.text = slot.binding?.mac.orEmpty()
            val voltage = slot.reading?.let { DeviceProtocol.voltage(it, slot.info) }?.let { "%.1fV".format(it) } ?: "--"
            diagnostics.text = "PA5 ${slot.reading?.pa5?.let { if (it) "高" else "低" } ?: "--"}   电压 $voltage   RSSI ${slot.info?.rssi?.let { "$it dBm" } ?: "--"}"
            raw.visibility = if (maintenance && slot.reading != null) View.VISIBLE else View.GONE
            raw.text = slot.reading?.let { "Raw ${formatRaw(slot)}  采样失败 ${slot.info?.invalidSamples ?: 0} / 丢帧 ${slot.info?.missedNotifications ?: 0}" } ?: "Raw --"
            updated.text = slot.reading?.let {
                val seconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - it.receivedAt).coerceAtLeast(0)
                if (seconds < 2) "刚刚" else if (seconds < 60) "${seconds}s" else "${seconds / 60}m"
            } ?: if (slot.binding == null) "未绑定" else "等待数据…"
            slowRate.setTextColor(root.context.themeColor(if (!slot.fast) R.attr.appPositive else R.attr.appTextSecondary))
            fastRate.setTextColor(root.context.themeColor(if (slot.fast) R.attr.appPositive else R.attr.appTextSecondary))
            slowRate.setOnClickListener { if (slot.binding != null) onRate(slot, false) }
            fastRate.setOnClickListener { if (slot.binding != null) onRate(slot, true) }
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

    private fun formatRaw(slot: SlotState): String {
        val p = slot.reading ?: return "--"
        return when (slot.binding?.type ?: slot.info?.sensorType) {
            DeviceProtocol.TYPE_TILT -> "X ${p.xRaw} Y ${p.yRaw} Z ${p.zRaw}"
            else -> p.raw.toString()
        }
    }

    private fun positionName(type: Int, id: Int): String {
        val pressure = listOf("", "立柱前左", "立柱前右", "立柱后左", "立柱后右", "一级护帮", "二级护帮", "三级护帮", "前梁", "伸缩梁", "平衡上腔", "平衡下腔")
        val tilt = listOf("", "底座", "顶梁", "前连杆", "一级护帮", "二级护帮", "三级护帮", "前梁", "尾梁", "掩护梁")
        val name = when (type) { DeviceProtocol.TYPE_PRESSURE -> pressure.getOrNull(id); DeviceProtocol.TYPE_TILT -> tilt.getOrNull(id); else -> null }
        return "${if (type == DeviceProtocol.TYPE_TILT) "倾角" else "压力"}-${name ?: "点$id"}"
    }
}
