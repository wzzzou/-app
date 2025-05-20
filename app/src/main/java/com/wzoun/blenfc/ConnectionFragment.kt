package com.wzoun.blenfc

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wzoun.blenfc.bluetooth.BluetoothManager
import com.wzoun.blenfc.bluetooth.DeviceWrapper
import com.wzoun.blenfc.viewmodel.NfcViewModel
import kotlinx.coroutines.launch
import java.util.UUID

class ConnectionFragment : Fragment() {

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var nfcViewModel: NfcViewModel

    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button
    private lateinit var rvDiscoveredDevices: RecyclerView
    private lateinit var deviceListAdapter: DeviceListAdapter
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvSelectedDevice: TextView // Was tvSelectedDeviceNFC, mapping to existing ID
    private lateinit var progressBarScan: ProgressBar
    private lateinit var lvServices: ListView
    private lateinit var servicesAdapter: ArrayAdapter<String>
    private val displayedServices = mutableListOf<Pair<BluetoothGattService, List<BluetoothGattCharacteristic>>>()

    private lateinit var btnSelectCharacteristic: Button
    private lateinit var spinnerCharacteristics: Spinner
    private var selectedService: BluetoothGattService? = null
    private var selectedCharacteristic: BluetoothGattCharacteristic? = null

    // UI elements for displaying selected characteristic details
    private lateinit var tvSelectedCharacteristicLabel: TextView
    private lateinit var tvSelectedCharacteristicUuid: TextView


    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                Log.d("ConnectionFragment", "All Bluetooth permissions granted.")
                startScan()
            } else {
                Log.e("ConnectionFragment", "Not all Bluetooth permissions granted.")
                Toast.makeText(context, getString(R.string.bluetooth_permissions_required_for_scan), Toast.LENGTH_LONG).show()
                showPermissionsDeniedDialog()
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                Log.d("ConnectionFragment", "Bluetooth enabled by user.")
                startScan()
            } else {
                Log.e("ConnectionFragment", "Bluetooth not enabled by user.")
                Toast.makeText(context, getString(R.string.status_bluetooth_not_enabled), Toast.LENGTH_SHORT).show()
            }
        }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_connection, container, false)

        bluetoothManager = BluetoothManager(requireContext().applicationContext)
        nfcViewModel = ViewModelProvider(requireActivity()).get(NfcViewModel::class.java)

        btnScan = view.findViewById(R.id.btnScan) // Corrected ID based on your new Kotlin
        btnDisconnect = view.findViewById(R.id.btnDisconnect)
        rvDiscoveredDevices = view.findViewById(R.id.rvDiscoveredDevices)
        tvConnectionStatus = view.findViewById(R.id.tvConnectionStatus)
        tvSelectedDevice = view.findViewById(R.id.tvSelectedDeviceNFC) // Maps to this ID in provided XML
        progressBarScan = view.findViewById(R.id.progressBarScan)
        lvServices = view.findViewById(R.id.lvServices)
        btnSelectCharacteristic = view.findViewById(R.id.btnSelectCharacteristic)
        spinnerCharacteristics = view.findViewById(R.id.spinnerCharacteristics)
        tvSelectedCharacteristicLabel = view.findViewById(R.id.tvSelectedCharacteristicLabel)
        tvSelectedCharacteristicUuid = view.findViewById(R.id.tvSelectedCharacteristicUuid)


        setupRecyclerView()
        setupObservers()
        setupClickListeners()

        servicesAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, mutableListOf<String>())
        lvServices.adapter = servicesAdapter

        lvServices.setOnItemClickListener { _, _, position, _ ->
            if (position < displayedServices.size) {
                selectedService = displayedServices[position].first
                val characteristics = displayedServices[position].second
                updateCharacteristicSpinner(characteristics)
                Log.d("ConnectionFragment", "Service selected: ${selectedService?.uuid}")
                // Consider using a string resource for "Service selected: %s"
                Toast.makeText(context, "服务已选择: ${selectedService?.uuid}", Toast.LENGTH_SHORT).show()
            }
        }
        updateSelectedCharacteristicDisplay() // Initial UI state
        return view
    }

    private fun setupRecyclerView() {
        deviceListAdapter = DeviceListAdapter { deviceWrapper: DeviceWrapper ->
            if (bluetoothManager.scanState.value is BluetoothManager.ScanState.Scanning) {
                bluetoothManager.stopBleScan()
            }
            bluetoothManager.connectToDevice(deviceWrapper.device.address)
        }
        rvDiscoveredDevices.layoutManager = LinearLayoutManager(context)
        rvDiscoveredDevices.adapter = deviceListAdapter
    }

    @SuppressLint("MissingPermission") // Permissions are checked before connect/scan
    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    bluetoothManager.scanState.collect { state ->
                        when (state) {
                            is BluetoothManager.ScanState.Idle -> {
                                progressBarScan.visibility = View.GONE
                                btnScan.text = getString(R.string.scan_devices_button) // Corrected
                                btnScan.isEnabled = true
                            }
                            is BluetoothManager.ScanState.Scanning -> {
                                progressBarScan.visibility = View.VISIBLE
                                btnScan.text = getString(R.string.status_scanning) // Corrected (button text)
                                btnScan.isEnabled = false
                                deviceListAdapter.submitList(emptyList())
                                tvConnectionStatus.text = getString(R.string.status_scanning) // Corrected (status text)
                            }
                            is BluetoothManager.ScanState.DevicesFound -> {
                                progressBarScan.visibility = View.GONE
                                btnScan.text = getString(R.string.rescan_devices_button) // Corrected
                                btnScan.isEnabled = true
                                deviceListAdapter.submitList(state.devices)
                                if (state.devices.isEmpty()) {
                                    tvConnectionStatus.text = getString(R.string.status_no_devices_found)
                                } else {
                                    tvConnectionStatus.text = getString(R.string.status_scan_finished_select_device)
                                }
                            }
                            is BluetoothManager.ScanState.Error -> {
                                progressBarScan.visibility = View.GONE
                                btnScan.text = getString(R.string.scan_devices_button) // Corrected
                                btnScan.isEnabled = true
                                Toast.makeText(context, getString(R.string.status_scan_failed, state.message), Toast.LENGTH_LONG).show()
                                tvConnectionStatus.text = getString(R.string.status_scan_failed, state.message)
                                state.devices?.let { deviceListAdapter.submitList(it) }
                            }
                        }
                    }
                }
                launch {
                    bluetoothManager.discoveredDevices.collect { devices ->
                        deviceListAdapter.submitList(devices)
                        Log.d("ConnectionFragment", "Discovered devices updated: ${devices.size} devices")
                    }
                }
                launch {
                    bluetoothManager.connectionState.collect { state ->
                        // Update MainActivity about characteristic selection availability
                        val mainActivity = activity as? MainActivity
                        when (state) {
                            is BluetoothManager.ConnectionState.Connecting -> {
                                tvConnectionStatus.text = getString(R.string.status_connecting_to, state.deviceAddress)
                                tvSelectedDevice.text = getString(R.string.status_connecting_to, state.deviceAddress)
                                btnDisconnect.isEnabled = false
                                btnSelectCharacteristic.isEnabled = false
                                mainActivity?.clearSelectedCharacteristicUuids()
                                clearServicesAndCharacteristics()
                            }
                            is BluetoothManager.ConnectionState.Connected -> {
                                // IMPORTANT: Assumes BluetoothManager.ConnectionState.Connected has 'deviceName'
                                val deviceName = state.deviceName ?: state.deviceAddress // Use state.deviceName
                                tvConnectionStatus.text = getString(R.string.status_connected_to_ble, deviceName)
                                tvSelectedDevice.text = getString(R.string.connected_device_label) + deviceName
                                btnDisconnect.isEnabled = true
                                btnSelectCharacteristic.isEnabled = true // Enable once connected, selection handled by spinner/list
                                // Service discovery should be triggered by BluetoothManager upon connection
                            }
                            is BluetoothManager.ConnectionState.Disconnecting -> {
                                tvConnectionStatus.text = getString(R.string.status_disconnecting)
                                btnDisconnect.isEnabled = false
                                btnSelectCharacteristic.isEnabled = false
                                mainActivity?.clearSelectedCharacteristicUuids()
                            }
                            is BluetoothManager.ConnectionState.Disconnected -> {
                                val deviceNameText = state.deviceAddress ?: getString(R.string.previous_device_placeholder)
                                tvConnectionStatus.text = getString(R.string.status_disconnected_from, deviceNameText)
                                tvSelectedDevice.text = getString(R.string.no_device_selected)
                                btnDisconnect.isEnabled = false
                                btnSelectCharacteristic.isEnabled = false
                                mainActivity?.clearSelectedCharacteristicUuids()
                                clearServicesAndCharacteristics()
                            }
                            is BluetoothManager.ConnectionState.Error -> {
                                tvConnectionStatus.text = getString(R.string.status_connection_failed, state.message)
                                tvSelectedDevice.text = getString(R.string.status_connection_failed, "") // Keep it short
                                btnDisconnect.isEnabled = false
                                btnSelectCharacteristic.isEnabled = false
                                mainActivity?.clearSelectedCharacteristicUuids()
                                clearServicesAndCharacteristics()
                                Toast.makeText(context, getString(R.string.status_connection_failed, state.message), Toast.LENGTH_LONG).show()
                            }
                        }
                        updateSelectedCharacteristicDisplay() // Update UI based on connection and selection
                    }
                }
                launch {
                    bluetoothManager.services.collect { services ->
                        Log.d("ConnectionFragment", "Services received: ${services.size}")
                        displayedServices.clear()
                        val serviceDetails = mutableListOf<String>()
                        services.forEach { service ->
                            val characteristics = service.characteristics.filterNotNull()
                            displayedServices.add(Pair(service, characteristics))
                            val characteristicsInfo = characteristics.joinToString(separator = "\n    - ", prefix = getString(R.string.characteristics_label_prefix)) {
                                "${it.uuid} (${getString(R.string.characteristic_properties_label, getCharacteristicProperties(it))})"
                            }
                            serviceDetails.add(getString(R.string.service_label, service.uuid.toString()) +
                                    if (characteristics.isNotEmpty()) characteristicsInfo
                                    else getString(R.string.no_characteristics_found_or_accessible))
                        }

                        servicesAdapter.clear()
                        if (serviceDetails.isNotEmpty()) {
                            servicesAdapter.addAll(serviceDetails)
                        } else {
                            servicesAdapter.add(getString(R.string.no_services_found_or_accessible))
                        }
                        servicesAdapter.notifyDataSetChanged()

                        if (displayedServices.isNotEmpty()) {
                            selectedService = displayedServices[0].first
                            updateCharacteristicSpinner(displayedServices[0].second)
                        } else {
                            updateCharacteristicSpinner(emptyList())
                        }
                    }
                }
                launch {
                    bluetoothManager.operationStatus.collect { status ->
                        val message = when (status) {
                            is BluetoothManager.BleOperationStatus.Success -> getString(R.string.operation_successful, status.operation)
                            is BluetoothManager.BleOperationStatus.ReadSuccess -> getString(R.string.read_from_characteristic_success, status.characteristicUuid.toString(), status.value.toHexString())
                            is BluetoothManager.BleOperationStatus.WriteSuccess -> getString(R.string.write_to_characteristic_success, status.characteristicUuid.toString())
                            is BluetoothManager.BleOperationStatus.NotificationChangeSuccess -> getString(R.string.notifications_change_success, status.characteristicUuid.toString(), if (status.enabled) getString(R.string.enabled_text) else getString(R.string.disabled_text))
                            is BluetoothManager.BleOperationStatus.Failure -> getString(R.string.operation_failed, status.operation, status.message)
                            is BluetoothManager.BleOperationStatus.ReadFailure -> getString(R.string.read_from_characteristic_failed, status.characteristicUuid.toString(), status.message)
                            is BluetoothManager.BleOperationStatus.WriteFailure -> getString(R.string.write_to_characteristic_failed, status.characteristicUuid.toString(), status.message)
                            is BluetoothManager.BleOperationStatus.NotificationChangeFailure -> getString(R.string.notifications_change_failed, status.characteristicUuid.toString(), status.message)
                            is BluetoothManager.BleOperationStatus.Error -> getString(R.string.general_error_message, status.message)
                        }
                        Log.d("ConnectionFragment", "Operation Status: $message")
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

                        if (status is BluetoothManager.BleOperationStatus.ReadSuccess) {
                            nfcViewModel.setNfcMessage(status.value.toString(Charsets.UTF_8))
                            Toast.makeText(context, getString(R.string.data_ready_for_nfc, status.value.toString(Charsets.UTF_8)), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                launch {
                    nfcViewModel.nfcMessage.collect { message ->
                        Log.d("ConnectionFragment", "NFC message updated in ViewModel: $message")
                    }
                }
            }
        }
    }
    private fun updateCharacteristicSpinner(characteristics: List<BluetoothGattCharacteristic>) {
        val characteristicDescriptions = characteristics.map {
            "${it.uuid} (${getCharacteristicProperties(it)})"
        }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, characteristicDescriptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCharacteristics.adapter = adapter

        val mainActivity = activity as? MainActivity
        if (characteristics.isNotEmpty()) {
            spinnerCharacteristics.visibility = View.VISIBLE
            btnSelectCharacteristic.visibility = View.VISIBLE // Button to trigger action on selected
            spinnerCharacteristics.setSelection(0, false) // Avoid re-triggering listener if already selected
            selectedCharacteristic = characteristics[0]
            mainActivity?.setSelectedCharacteristicUuids(selectedService!!.uuid, selectedCharacteristic!!.uuid)
        } else {
            spinnerCharacteristics.visibility = View.GONE
            btnSelectCharacteristic.visibility = View.GONE
            selectedCharacteristic = null
            mainActivity?.clearSelectedCharacteristicUuids()
        }

        updateSelectedCharacteristicDisplay()
    }

    private fun getCharacteristicProperties(characteristic: BluetoothGattCharacteristic): String {
        val properties = mutableListOf<String>()
        val props = characteristic.properties

        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) properties.add(getString(R.string.property_read))
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) properties.add(getString(R.string.property_write))
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) properties.add(getString(R.string.property_write_no_response))
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) properties.add(getString(R.string.property_notify))
        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) properties.add(getString(R.string.property_indicate))

        return properties.joinToString("/") { it }
    }

    private fun setupClickListeners() {
        btnScan.setOnClickListener {
            if (bluetoothManager.scanState.value is BluetoothManager.ScanState.Scanning) {
                bluetoothManager.stopBleScan()
            } else {
                checkPermissionsAndStartScan()
            }
        }

        btnDisconnect.setOnClickListener {
            bluetoothManager.disconnectFromCurrentDevice()
        }

        btnSelectCharacteristic.setOnClickListener {
            val selectedPosition = spinnerCharacteristics.selectedItemPosition
            val characteristics = if (selectedService != null) {
                displayedServices.find { it.first.uuid == selectedService!!.uuid }?.second ?: emptyList()
            } else emptyList()

            if (selectedPosition >= 0 && selectedPosition < characteristics.size) {
                selectedCharacteristic = characteristics[selectedPosition]
                val mainActivity = activity as? MainActivity
                mainActivity?.setSelectedCharacteristicUuids(selectedService!!.uuid, selectedCharacteristic!!.uuid)

                // Display a dialog to select operation (Read/Write/etc)
                showCharacteristicOperationDialog(selectedCharacteristic!!)
            } else {
                Toast.makeText(context, getString(R.string.select_characteristic_first), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCharacteristicOperationDialog(characteristic: BluetoothGattCharacteristic) {
        val properties = characteristic.properties

        val options = mutableListOf<String>()

        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
            options.add(getString(R.string.operation_read))
        }
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
            properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            options.add(getString(R.string.operation_write))
        }
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
            properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            options.add(getString(R.string.operation_toggle_notifications))
        }

        if (options.isEmpty()) {
            Toast.makeText(context, getString(R.string.no_operations_available), Toast.LENGTH_SHORT).show()
            return
        }

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(getString(R.string.select_operation))
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    getString(R.string.operation_read) -> {
                        bluetoothManager.readCharacteristic(selectedService!!.uuid, characteristic.uuid)
                    }
                    getString(R.string.operation_write) -> {
                        showWriteValueDialog(characteristic)
                    }
                    getString(R.string.operation_toggle_notifications) -> {
                        // Toggle notification state - BluetoothManager should track current state
                        bluetoothManager.toggleCharacteristicNotification(selectedService!!.uuid, characteristic.uuid)
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }

        builder.create().show()
    }

    private fun showWriteValueDialog(characteristic: BluetoothGattCharacteristic) {
        val input = EditText(requireContext())
        input.hint = getString(R.string.write_value_hint)
        input.inputType = InputType.TYPE_CLASS_TEXT

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(getString(R.string.enter_value_to_write))
            .setView(input)
            .setPositiveButton(getString(R.string.write)) { _, _ ->
                val valueToWrite = input.text.toString()
                if (valueToWrite.isNotEmpty()) {
                    bluetoothManager.writeCharacteristic(
                        selectedService!!.uuid,
                        characteristic.uuid,
                        valueToWrite
                    )
                } else {
                    Toast.makeText(context, getString(R.string.empty_input_error), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }

        builder.create().show()
    }

    fun updateSelectedCharacteristicDisplay() {
        val mainActivity = activity as? MainActivity
        val selectedUuids = mainActivity?.getSelectedCharacteristicUuids()

        if (selectedUuids != null) {
            tvSelectedCharacteristicLabel.visibility = View.VISIBLE
            tvSelectedCharacteristicUuid.visibility = View.VISIBLE
            tvSelectedCharacteristicUuid.text = "Service: ${selectedUuids.first}\nChar: ${selectedUuids.second}"
        } else {
            tvSelectedCharacteristicLabel.visibility = View.GONE
            tvSelectedCharacteristicUuid.visibility = View.GONE
            tvSelectedCharacteristicUuid.text = ""
        }
    }

    private fun clearServicesAndCharacteristics() {
        displayedServices.clear()
        servicesAdapter.clear()
        servicesAdapter.add(getString(R.string.no_services_found_or_accessible))
        servicesAdapter.notifyDataSetChanged()

        val emptyAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, emptyList<String>())
        spinnerCharacteristics.adapter = emptyAdapter

        selectedService = null
        selectedCharacteristic = null
    }

    fun sendDataToSelectedCharacteristic(jsonData: String) {
        val mainActivity = activity as? MainActivity
        val selectedUuids = mainActivity?.getSelectedCharacteristicUuids()

        if (selectedUuids != null) {
            bluetoothManager.writeCharacteristic(
                selectedUuids.first,  // serviceUuid
                selectedUuids.second, // characteristicUuid
                jsonData
            )
            Log.d("ConnectionFragment", "Sent data to selected characteristic: $jsonData")
        } else {
            Toast.makeText(context, getString(R.string.error_no_characteristic_selected), Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissionsAndStartScan() {
        // Check if Bluetooth is enabled
        if (!bluetoothManager.isBluetoothEnabled()) {
            Toast.makeText(context, getString(R.string.status_bluetooth_not_enabled), Toast.LENGTH_SHORT).show()

            // Request Bluetooth enabling
            try {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                enableBluetoothLauncher.launch(enableBtIntent)
            } catch (e: Exception) {
                Log.e("ConnectionFragment", "Error requesting Bluetooth enable: ${e.message}")
                Toast.makeText(context, getString(R.string.bluetooth_enable_failed), Toast.LENGTH_LONG).show()
            }
            return
        }

        // If Bluetooth is enabled, check permissions
        val permissionsRequired = getRequiredBluetoothPermissions()
        if (permissionsRequired.isNotEmpty()) {
            // Request permissions
            requestPermissionsLauncher.launch(permissionsRequired.toTypedArray())
        } else {
            // All permissions granted, start scan
            startScan()
        }
    }

    private fun getRequiredBluetoothPermissions(): List<String> {
        val permissionsNeeded = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            // For Android 11 (R) and below
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        return permissionsNeeded
    }

    private fun startScan() {
        bluetoothManager.startBleScan()
    }

    private fun showPermissionsDeniedDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(getString(R.string.permissions_required))
            .setMessage(getString(R.string.bluetooth_permissions_explanation))
            .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                // Open app settings
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", requireContext().packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
        builder.create().show()
    }

    // Extension function to convert ByteArray to hex string for display
    private fun ByteArray.toHexString(): String {
        return joinToString(separator = " ") { byte -> "%02X".format(byte) }
    }
}

