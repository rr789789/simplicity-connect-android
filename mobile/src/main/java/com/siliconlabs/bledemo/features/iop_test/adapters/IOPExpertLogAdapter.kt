package com.siliconlabs.bledemo.features.iop_test.adapters

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.siliconlabs.bledemo.R
import com.siliconlabs.bledemo.databinding.ItemIopExpertLogBinding
import com.siliconlabs.bledemo.features.iop_test.models.IOPExpertLogEntry

class IOPExpertLogAdapter : RecyclerView.Adapter<IOPExpertLogAdapter.LogViewHolder>() {

    private val entries = ArrayList<IOPExpertLogEntry>()

    fun setEntries(newEntries: List<IOPExpertLogEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }

    fun appendEntry(entry: IOPExpertLogEntry) {
        entries.add(entry)
        notifyItemInserted(entries.lastIndex)
    }

    fun updateLastEntry(entry: IOPExpertLogEntry) {
        if (entries.isEmpty()) return
        entries[entries.lastIndex] = entry
        notifyItemChanged(entries.lastIndex)
    }

    fun clear() {
        val size = entries.size
        if (size == 0) return
        entries.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemIopExpertLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    class LogViewHolder(
        private val binding: ItemIopExpertLogBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: IOPExpertLogEntry) {
            val context = binding.root.context
            val style = visualStyle(entry.tone)
            val isMilestone = entry.isMilestone
            val showCategory = shouldShowCategoryBadge(entry.category)

            binding.tvTimestamp.text = entry.timestamp
            binding.tvTitle.text = entry.title
            if (entry.detail.isNullOrBlank()) {
                binding.tvDetail.visibility = View.GONE
            } else {
                binding.tvDetail.visibility = View.VISIBLE
                binding.tvDetail.text = entry.detail
            }

            if (showCategory) {
                binding.tvCategory.visibility = View.VISIBLE
                binding.tvCategory.text = if (entry.repeatCount > 1) {
                    " ${entry.category} x${entry.repeatCount} "
                } else {
                    " ${entry.category} "
                }
            } else {
                binding.tvCategory.visibility = View.GONE
            }

            val accentWidth = if (isMilestone) {
                context.resources.getDimensionPixelSize(R.dimen.iop_expert_accent_milestone)
            } else {
                context.resources.getDimensionPixelSize(R.dimen.iop_expert_accent_default)
            }
            binding.accentBar.layoutParams.width = accentWidth

            binding.accentBar.setBackgroundColor(style.accentColor)
            binding.cardContainer.setCardBackgroundColor(style.backgroundColor)
            binding.cardContainer.strokeColor = style.borderColor
            binding.cardContainer.strokeWidth = if (isMilestone) 0 else context.resources.getDimensionPixelSize(R.dimen.matter_1dp)

            binding.tvTimestamp.setTextColor(style.accentColor)
            binding.tvCategory.setTextColor(style.accentColor)
            binding.tvCategory.setBackgroundColor(style.backgroundColor)
            binding.tvTitle.setTextColor(style.titleColor)
            binding.tvDetail.setTextColor(
                if (isMilestone) style.titleColor else ContextCompat.getColor(context, R.color.silabs_redtheme_body_text_color)
            )

            binding.tvTitle.textSize = if (isMilestone) 14f else 13f
            binding.tvTitle.setTypeface(binding.tvTitle.typeface, if (isMilestone) Typeface.BOLD else Typeface.NORMAL)
        }

        private fun shouldShowCategoryBadge(category: String): Boolean {
            return category in listOf("PASS", "FAIL", "WAIT", "RUN", "SCENARIO", "TEST")
        }

        private data class VisualStyle(
            val accentColor: Int,
            val backgroundColor: Int,
            val borderColor: Int,
            val titleColor: Int
        )

        private fun visualStyle(tone: String): VisualStyle {
            val context = binding.root.context
            return when (tone) {
                "success" -> VisualStyle(
                    ContextCompat.getColor(context, R.color.silabs_green),
                    ContextCompat.getColor(context, R.color.iop_expert_tone_success_bg),
                    ContextCompat.getColor(context, R.color.iop_expert_tone_success_border),
                    ContextCompat.getColor(context, R.color.silabs_redtheme_header_text_color)
                )
                "failure" -> VisualStyle(
                    ContextCompat.getColor(context, R.color.silabs_red),
                    ContextCompat.getColor(context, R.color.iop_expert_tone_failure_bg),
                    ContextCompat.getColor(context, R.color.iop_expert_tone_failure_border),
                    ContextCompat.getColor(context, R.color.silabs_red_dark)
                )
                "warning" -> VisualStyle(
                    ContextCompat.getColor(context, R.color.silabs_yellow),
                    ContextCompat.getColor(context, R.color.iop_expert_tone_warning_bg),
                    ContextCompat.getColor(context, R.color.iop_expert_tone_warning_border),
                    ContextCompat.getColor(context, R.color.silabs_redtheme_header_text_color)
                )
                "session" -> VisualStyle(
                    ContextCompat.getColor(context, R.color.silabs_red),
                    ContextCompat.getColor(context, R.color.iop_expert_tone_session_bg),
                    ContextCompat.getColor(context, R.color.iop_expert_tone_session_border),
                    ContextCompat.getColor(context, R.color.silabs_redtheme_header_text_color)
                )
                "test" -> VisualStyle(
                    ContextCompat.getColor(context, android.R.color.darker_gray),
                    ContextCompat.getColor(context, R.color.iop_expert_tone_test_bg),
                    ContextCompat.getColor(context, R.color.iop_expert_tone_test_border),
                    ContextCompat.getColor(context, R.color.silabs_redtheme_header_text_color)
                )
                else -> VisualStyle(
                    ContextCompat.getColor(context, R.color.silabs_blue),
                    ContextCompat.getColor(context, R.color.iop_expert_tone_info_bg),
                    ContextCompat.getColor(context, R.color.iop_expert_tone_info_border),
                    ContextCompat.getColor(context, R.color.silabs_redtheme_header_text_color)
                )
            }
        }
    }
}
