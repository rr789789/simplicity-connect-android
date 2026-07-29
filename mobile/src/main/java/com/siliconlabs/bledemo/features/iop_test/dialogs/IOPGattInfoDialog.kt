package com.siliconlabs.bledemo.features.iop_test.dialogs

import android.annotation.SuppressLint
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.siliconlabs.bledemo.R
import com.siliconlabs.bledemo.databinding.DialogIopGattInfoBinding
import com.siliconlabs.bledemo.features.iop_test.adapters.IOPGattInfoAdapter
import com.siliconlabs.bledemo.features.iop_test.models.IOPGattDiscoveredCharacteristic
import com.siliconlabs.bledemo.features.iop_test.models.IOPGattDiscoveredService
import com.siliconlabs.bledemo.features.iop_test.models.IOPGattListItem
import com.siliconlabs.bledemo.features.iop_test.models.IOPGattProperty
import com.siliconlabs.bledemo.features.iop_test.models.IOPGattReferenceCatalog
import java.util.Locale

class IOPGattInfoDialog : DialogFragment() {

    private var _binding: DialogIopGattInfoBinding? = null
    private val binding get() = _binding!!

    private val adapter = IOPGattInfoAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private var targetAddress: String? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var didStartConnecting = false
    private var didFinishLoading = false
    private val discoveredServices = ArrayList<IOPGattDiscoveredService>()

    private val scanTimeoutRunnable = Runnable { handleScanTimeout() }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = targetAddress ?: return
            if (result.device.address.equals(address, ignoreCase = true)) {
                if (isAdded) {
                    requireActivity().runOnUiThread { onTargetDeviceFound(result.device) }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            if (isAdded) {
                requireActivity().runOnUiThread {
                    showStatus(getString(R.string.iop_gatt_status_discover_failed), loading = false)
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isAdded) return
            requireActivity().runOnUiThread {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        showStatus(getString(R.string.iop_gatt_status_discovering), loading = true)
                        if (!gatt.discoverServices()) {
                            showStatus(getString(R.string.iop_gatt_status_discover_failed), loading = false)
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (!didFinishLoading) {
                            showStatus(getString(R.string.iop_gatt_status_disconnected), loading = false)
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isAdded) return
            requireActivity().runOnUiThread {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    showStatus(getString(R.string.iop_gatt_status_discover_failed), loading = false)
                    return@runOnUiThread
                }
                val services = gatt.services ?: emptyList()
                discoveredServices.clear()
                discoveredServices.addAll(
                    services.map { service ->
                        val characteristics = service.characteristics.map { characteristic ->
                            IOPGattDiscoveredCharacteristic(
                                name = IOPGattReferenceCatalog.characteristicName(
                                    characteristic.uuid,
                                    requireContext()
                                ),
                                uuid = characteristic.uuid.toString().uppercase(Locale.US),
                                properties = characteristic.properties
                            )
                        }
                        IOPGattDiscoveredService(
                            name = IOPGattReferenceCatalog.serviceName(
                                service.uuid,
                                requireContext()
                            ),
                            uuid = service.uuid.toString().uppercase(Locale.US),
                            characteristics = characteristics
                        )
                    }
                )
                finishLoading()
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isCancelable = true
            setCanceledOnTouchOutside(true)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogIopGattInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val deviceName = arguments?.getString(ARG_DEVICE_NAME)?.trim().orEmpty()
        targetAddress = arguments?.getString(ARG_DEVICE_ADDRESS)?.trim()

        binding.tvGattTitle.text = deviceName.ifEmpty { getString(R.string.iop_gatt_table_title) }
        binding.btnGattClose.setOnClickListener { dismiss() }

        binding.rvGattInfo.layoutManager = LinearLayoutManager(requireContext())
        binding.rvGattInfo.adapter = adapter

        if (targetAddress.isNullOrBlank()) {
            showStatus(getString(R.string.iop_gatt_status_no_device), loading = false)
            return
        }

        showStatus(getString(R.string.iop_gatt_status_connecting), loading = true)
        beginConnectionFlow()
    }

    override fun onStart() {
        super.onStart()
        val metrics = resources.displayMetrics
        dialog?.window?.setLayout(
            (metrics.widthPixels * 0.92f).toInt(),
            (metrics.heightPixels * 0.82f).toInt()
        )
    }

    override fun onDestroyView() {
        teardownConnection()
        handler.removeCallbacksAndMessages(null)
        _binding = null
        super.onDestroyView()
    }

    @SuppressLint("MissingPermission")
    private fun beginConnectionFlow() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            showStatus(getString(R.string.iop_gatt_status_bluetooth_off), loading = false)
            return
        }
        startScanningIfNeeded()
    }

    @SuppressLint("MissingPermission")
    private fun startScanningIfNeeded() {
        if (isScanning) return
        isScanning = true
        showStatus(getString(R.string.iop_gatt_status_searching), loading = true)
        try {
            bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
            handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE scan permission denied", e)
            isScanning = false
            showStatus(getString(R.string.iop_gatt_status_discover_failed), loading = false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun onTargetDeviceFound(device: BluetoothDevice) {
        if (didStartConnecting) return
        didStartConnecting = true
        handler.removeCallbacks(scanTimeoutRunnable)
        stopScanning()
        showStatus(getString(R.string.iop_gatt_status_connecting), loading = true)
        try {
            bluetoothGatt = device.connectGatt(requireContext(), false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE connect permission denied", e)
            showStatus(getString(R.string.iop_gatt_status_discover_failed), loading = false)
        }
    }

    private fun handleScanTimeout() {
        if (didStartConnecting || didFinishLoading) return
        stopScanning()
        showStatus(getString(R.string.iop_gatt_status_not_found), loading = false)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        if (!isScanning) return
        isScanning = false
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE stop scan permission denied", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun teardownConnection() {
        handler.removeCallbacks(scanTimeoutRunnable)
        stopScanning()
        try {
            bluetoothGatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {
        }
        bluetoothGatt = null
    }

    private fun showStatus(text: String, loading: Boolean) {
        if (_binding == null) return
        binding.statusContainer.visibility = View.VISIBLE
        binding.tvStatus.text = text
        binding.progressIndicator.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun hideStatus() {
        if (_binding == null) return
        binding.statusContainer.visibility = View.GONE
        binding.progressIndicator.visibility = View.GONE
    }

    private fun finishLoading() {
        if (didFinishLoading) return
        didFinishLoading = true
        val listItems = buildListItems()
        if (listItems.isEmpty()) {
            showStatus(getString(R.string.iop_gatt_status_no_services), loading = false)
        } else {
            hideStatus()
            adapter.submitServices(listItems)
            binding.rvGattInfo.scrollToPosition(0)
        }
    }

    private fun buildListItems(): List<IOPGattListItem> {
        val result = ArrayList<IOPGattListItem>()
        discoveredServices.forEach { service ->
            result.add(IOPGattListItem.ServiceHeader(service.name, service.uuid))
            service.characteristics.forEach { characteristic ->
                result.add(
                    IOPGattListItem.CharacteristicRow(
                        name = characteristic.name,
                        uuid = characteristic.uuid,
                        properties = IOPGattProperty.fromCharacteristicProperties(characteristic.properties)
                    )
                )
            }
        }
        return result
    }

    companion object {
        private const val TAG = "IOPGattInfoDialog"
        private const val ARG_DEVICE_NAME = "device_name"
        private const val ARG_DEVICE_ADDRESS = "device_address"
        private const val SCAN_TIMEOUT_MS = 15_000L

        fun newInstance(deviceName: String, deviceAddress: String): IOPGattInfoDialog {
            return IOPGattInfoDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_DEVICE_NAME, deviceName)
                    putString(ARG_DEVICE_ADDRESS, deviceAddress)
                }
            }
        }
    }
}
