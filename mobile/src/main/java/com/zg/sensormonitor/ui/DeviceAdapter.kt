package com.zg.sensormonitor.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.zg.sensormonitor.R
import com.zg.sensormonitor.databinding.RowDeviceBinding
import com.zg.sensormonitor.domain.DeviceKind
import com.zg.sensormonitor.domain.DiscoveredDevice

class DeviceAdapter(private val onClick: (DiscoveredDevice) -> Unit) : RecyclerView.Adapter<DeviceAdapter.Holder>() {
    private var items = emptyList<DiscoveredDevice>()
    fun submit(value: List<DiscoveredDevice>) { items = value; notifyDataSetChanged() }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(RowDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: RowDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(device: DiscoveredDevice) = with(binding) {
            name.text = device.name.ifBlank { kindName(device.kind) }
            address.text = "${kindName(device.kind)} · ${device.address}"
            rssi.text = device.rssi.toString()
            kind.text = when (device.kind) { DeviceKind.RECEIVER -> "收"; DeviceKind.PRESSURE -> "压"; DeviceKind.TILT -> "倾"; else -> "感" }
            kind.backgroundTintList = ColorStateList.valueOf(root.context.themeColor(if (device.rssi >= -80) R.attr.appPositive else R.attr.appWarning))
            root.setOnClickListener { onClick(device) }
        }
    }

    companion object {
        fun kindName(kind: DeviceKind) = when (kind) {
            DeviceKind.RECEIVER -> "蓝牙接收器"
            DeviceKind.PRESSURE -> "压力传感器"
            DeviceKind.TILT -> "倾角传感器"
            DeviceKind.SENSOR -> "传感器"
            DeviceKind.OTA -> "升级设备"
        }
    }
}
