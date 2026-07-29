package com.siliconlabs.bledemo.features.iop_test.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayout
import com.siliconlabs.bledemo.databinding.ItemIopGattCharacteristicInfoBinding
import com.siliconlabs.bledemo.databinding.ItemIopGattServiceHeaderBinding
import com.siliconlabs.bledemo.features.iop_test.models.IOPGattListItem
import com.siliconlabs.bledemo.features.iop_test.models.IOPGattProperty

class IOPGattInfoAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<IOPGattListItem>()

    fun submitServices(services: List<IOPGattListItem>) {
        items.clear()
        items.addAll(services)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is IOPGattListItem.ServiceHeader -> VIEW_TYPE_HEADER
        is IOPGattListItem.CharacteristicRow -> VIEW_TYPE_CHARACTERISTIC
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> ServiceHeaderViewHolder(
                ItemIopGattServiceHeaderBinding.inflate(inflater, parent, false)
            )
            else -> CharacteristicViewHolder(
                ItemIopGattCharacteristicInfoBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is IOPGattListItem.ServiceHeader -> (holder as ServiceHeaderViewHolder).bind(item)
            is IOPGattListItem.CharacteristicRow -> (holder as CharacteristicViewHolder).bind(item)
        }
    }

    private class ServiceHeaderViewHolder(
        private val binding: ItemIopGattServiceHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: IOPGattListItem.ServiceHeader) {
            binding.tvServiceName.text = item.name
            binding.tvServiceUuid.text = item.uuid
        }
    }

    private class CharacteristicViewHolder(
        private val binding: ItemIopGattCharacteristicInfoBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: IOPGattListItem.CharacteristicRow) {
            binding.tvCharacteristicName.text = item.name
            binding.tvCharacteristicUuid.text = item.uuid
            binding.flexProperties.removeAllViews()
            if (item.properties.isEmpty()) {
                binding.flexProperties.addView(
                    TextView(binding.root.context).apply {
                        text = "—"
                        setTextColor(ContextCompat.getColor(context, com.siliconlabs.bledemo.R.color.silabs_redtheme_body_text_color))
                        textSize = 11f
                    }
                )
            } else {
                item.properties.forEach { property ->
                    binding.flexProperties.addView(createBadge(property))
                }
            }
        }

        private fun createBadge(property: IOPGattProperty): TextView {
            val context = binding.root.context
            return TextView(context).apply {
                text = property.label
                textSize = 10f
                setTextColor(ContextCompat.getColor(context, property.colorRes))
                setBackgroundResource(property.backgroundDrawableRes)
                val horizontal = (8 * resources.displayMetrics.density).toInt()
                val vertical = (3 * resources.displayMetrics.density).toInt()
                setPadding(horizontal, vertical, horizontal, vertical)
                val params = FlexboxLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, (6 * resources.displayMetrics.density).toInt(), 0)
                layoutParams = params
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_CHARACTERISTIC = 1
    }
}
