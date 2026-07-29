package com.siliconlabs.bledemo.features.iop_test.fragments

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.siliconlabs.bledemo.R
import com.siliconlabs.bledemo.databinding.FragmentIopExpertBinding
import com.siliconlabs.bledemo.features.iop_test.activities.IOPExpertListener
import com.siliconlabs.bledemo.features.iop_test.activities.IOPTestActivity
import com.siliconlabs.bledemo.features.iop_test.adapters.IOPExpertLogAdapter
import com.siliconlabs.bledemo.features.iop_test.models.IOPExpertLogEntry

class IOPExpertFragment : Fragment(R.layout.fragment_iop_expert), IOPExpertListener {

    private val binding by viewBinding(FragmentIopExpertBinding::bind)
    private val logAdapter = IOPExpertLogAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvExpertLog.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = logAdapter
        }
        updateEmptyState()
        (activity as? IOPTestActivity)?.setExpertListener(this)
    }

    override fun appendLogEntry(entry: IOPExpertLogEntry) {
        if (!isAdded) return
        logAdapter.appendEntry(entry)
        updateEmptyState()
        binding.rvExpertLog.post {
            if (logAdapter.itemCount > 0) {
                binding.rvExpertLog.smoothScrollToPosition(logAdapter.itemCount - 1)
            }
        }
    }

    override fun restoreLog(entries: List<IOPExpertLogEntry>) {
        if (!isAdded) return
        logAdapter.setEntries(entries)
        updateEmptyState()
        if (entries.isNotEmpty()) {
            binding.rvExpertLog.post {
                binding.rvExpertLog.scrollToPosition(entries.lastIndex)
            }
        }
    }

    override fun clearLog() {
        if (!isAdded) return
        logAdapter.clear()
        updateEmptyState()
    }

    private fun updateEmptyState() {
        binding.tvExpertEmpty.visibility =
            if (logAdapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    companion object {
        fun newExpertInstance(): IOPExpertFragment = IOPExpertFragment()
    }
}
