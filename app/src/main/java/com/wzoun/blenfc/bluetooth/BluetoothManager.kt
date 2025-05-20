package com.wzoun.blenfc.bluetooth

import java.util.Arrays
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

@SuppressLint("MissingPermission") // Permissions are checked in MainActivity before operations
class BluetoothManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothManager"
        private const val SCAN_TIMEOUT_MS = 10000L // 10 seconds
        private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bluetoothLeScanner: BluetoothLeScanner? by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DeviceWrapper>>(emptyList())
    val discoveredDevices: StateFlow<List<DeviceWrapper>> = _discoveredDevices.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _services = MutableStateFlow<List<BluetoothGattService>>(emptyList())
    val services: StateFlow<List<BluetoothGattService>> = _services.asStateFlow()

    private val _operationStatus = MutableStateFlow<BleOperationStatus>(BleOperationStatus.Success("Initialize"))
    val operationStatus: StateFlow<BleOperationStatus> = _operationStatus.asStateFlow()

    // Keep track of characteristic notification states
    private val notificationEnabledCharacteristics = mutableSetOf<UUID>()

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    fun startBleScan() {
        if (!isBluetoothEnabled()) {
            _scanState.value = ScanState.Error("Bluetooth is not enabled", _discoveredDevices.value)
            return
        }

        if (bluetoothLeScanner == null) {
            _scanState.value = ScanState.Error("BLE scanner not available", _discoveredDevices.value)
            return
        }

        _discoveredDevices.value = emptyList()
        _scanState.value = ScanState.Scanning

        val filters = listOf<ScanFilter>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            Log.d(TAG, "Starting BLE scan")
            bluetoothLeScanner?.startScan(filters, settings, scanCallback)
            handler.postDelayed({ stopBleScan() }, SCAN_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE scan: ${e.message}")
            _scanState.value = ScanState.Error("Error starting scan: ${e.message}", _discoveredDevices.value)
        }
    }

    fun stopBleScan() {
        if (_scanState.value is ScanState.Scanning) {
            try {
                Log.d(TAG, "Stopping BLE scan")
                bluetoothLeScanner?.stopScan(scanCallback)
                handler.removeCallbacksAndMessages(null)
                if (_discoveredDevices.value.isEmpty()) {
                    _scanState.value = ScanState.DevicesFound(emptyList())
                } else {
                    _scanState.value = ScanState.DevicesFound(_discoveredDevices.value)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping BLE scan: ${e.message}")
                _scanState.value = ScanState.Error("Error stopping scan: ${e.message}", _discoveredDevices.value)
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val rssi = result.rssi

            // Check if device already in the list
            val existingDevices = _discoveredDevices.value.toMutableList()
            val existingIndex = existingDevices.indexOfFirst { it.device.address == device.address }

            if (existingIndex >= 0) {
                // Update RSSI for existing device
                existingDevices[existingIndex] = DeviceWrapper(device, rssi)
            } else {
                // Add new device
                existingDevices.add(DeviceWrapper(device, rssi))
            }

            _discoveredDevices.value = existingDevices
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            val existingDevices = _discoveredDevices.value.toMutableList()

            for (result in results) {
                val device = result.device
                val rssi = result.rssi

                val existingIndex = existingDevices.indexOfFirst { it.device.address == device.address }
                if (existingIndex >= 0) {
                    existingDevices[existingIndex] = DeviceWrapper(device, rssi)
                } else {
                    existingDevices.add(DeviceWrapper(device, rssi))
                }
            }

            _discoveredDevices.value = existingDevices
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
            _scanState.value = ScanState.Error("Scan failed with code: $errorCode", _discoveredDevices.value)
        }
    }

    fun connectToDevice(deviceAddress: String) {
        if (!isBluetoothEnabled()) {
            _connectionState.value = ConnectionState.Error("Bluetooth is not enabled")
            return
        }

        // First disconnect if already connected
        disconnectFromCurrentDevice()

        try {
            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            if (device == null) {
                _connectionState.value = ConnectionState.Error("Device not found: $deviceAddress")
                return
            }

            _connectionState.value = ConnectionState.Connecting(deviceAddress)

            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }

            Log.d(TAG, "Connecting to device: $deviceAddress")
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to device: ${e.message}")
            _connectionState.value = ConnectionState.Error("Error connecting: ${e.message}")
        }
    }

    fun disconnectFromCurrentDevice() {
        if (bluetoothGatt != null) {
            try {
                _connectionState.value = ConnectionState.Disconnecting
                bluetoothGatt?.disconnect()
                Log.d(TAG, "Disconnecting from device")
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting: ${e.message}")
                _connectionState.value = ConnectionState.Error("Error disconnecting: ${e.message}")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "Connected to device: ${gatt.device.address}")
                        val deviceName = gatt.device.name ?: "Unknown Device"
                        _connectionState.value = ConnectionState.Connected(gatt.device.address, deviceName)
                        // Start service discovery after connection
                        handler.postDelayed({
                            gatt.discoverServices()
                        }, 600) // Small delay for stability
                    } else {
                        Log.e(TAG, "Connection established but with error status: $status")
                        _connectionState.value = ConnectionState.Error("Connection error with status: $status")
                        closeGatt()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from device")
                    // Reset notification tracking when disconnected
                    notificationEnabledCharacteristics.clear()
                    if (_connectionState.value is ConnectionState.Disconnecting) {
                        // Normal disconnect that we initiated
                        _connectionState.value = ConnectionState.Disconnected(gatt.device.address)
                    } else {
                        // Unexpected disconnect
                        _connectionState.value = ConnectionState.Disconnected(gatt.device.address)
                    }
                    closeGatt()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered")
                val discoveredServices = gatt.services?.filterNotNull() ?: emptyList()
                _services.value = discoveredServices
                if (discoveredServices.isEmpty()) {
                    _operationStatus.value = BleOperationStatus.Failure("Service Discovery", "No services found")
                } else {
                    _operationStatus.value = BleOperationStatus.Success("Service Discovery")
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: $status")
                _operationStatus.value = BleOperationStatus.Failure("Service Discovery", "Failed with status: $status")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // 成功读取数据
                val value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    characteristic.value // Android 13+ 直接使用characteristic.value
                } else {
                    characteristic.value
                }

                if (value != null) {
                    // 修复 contentToString 问题
                    Log.d(TAG, "Read characteristic ${characteristic.uuid}: ${bytesToHexString(value)}")
                    _operationStatus.value = BleOperationStatus.ReadSuccess(characteristic.uuid, value)
                } else {
                    Log.w(TAG, "Null value read from characteristic: ${characteristic.uuid}")
                    _operationStatus.value = BleOperationStatus.ReadFailure(characteristic.uuid, "Null value read")
                }
            } else {
                Log.e(TAG, "Characteristic read failed with status: $status")
                _operationStatus.value = BleOperationStatus.ReadFailure(characteristic.uuid, "Read failed with status: $status")
            }
        }

        // 添加一个辅助方法用于ByteArray转16进制字符串
        private fun bytesToHexString(bytes: ByteArray): String {
            val hexChars = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xFF
                hexChars[i * 2] = "0123456789ABCDEF"[v ushr 4]
                hexChars[i * 2 + 1] = "0123456789ABCDEF"[v and 0x0F]
            }
            return String(hexChars)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Write to characteristic ${characteristic.uuid} succeeded")
                _operationStatus.value = BleOperationStatus.WriteSuccess(characteristic.uuid)
            } else {
                Log.e(TAG, "Write to characteristic ${characteristic.uuid} failed with status: $status")
                _operationStatus.value = BleOperationStatus.WriteFailure(characteristic.uuid, "Write failed with status: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // 数据通知接收
            val value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                characteristic.value // 使用特性的当前值
            } else {
                characteristic.value
            }

            if (value != null) {
                val stringValue = String(value, Charsets.UTF_8)
                Log.d(TAG, "Notification from ${characteristic.uuid}: $stringValue")
                _operationStatus.value = BleOperationStatus.ReadSuccess(characteristic.uuid, value)
            } else {
                Log.w(TAG, "Null notification from characteristic: ${characteristic.uuid}")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val characteristic = descriptor.characteristic
                val descriptorValue = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    descriptor.value
                } else {
                    descriptor.value
                }

                val isEnabling = descriptorValue != null && Arrays.equals(descriptorValue, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)

                if (isEnabling) {
                    notificationEnabledCharacteristics.add(characteristic.uuid)
                    _operationStatus.value = BleOperationStatus.NotificationChangeSuccess(characteristic.uuid, true)
                    Log.d(TAG, "Notifications enabled for ${characteristic.uuid}")
                } else {
                    notificationEnabledCharacteristics.remove(characteristic.uuid)
                    _operationStatus.value = BleOperationStatus.NotificationChangeSuccess(characteristic.uuid, false)
                    Log.d(TAG, "Notifications disabled for ${characteristic.uuid}")
                }
            } else {
                val characteristic = descriptor.characteristic
                Log.e(TAG, "Descriptor write failed for ${characteristic.uuid} with status: $status")
                _operationStatus.value = BleOperationStatus.NotificationChangeFailure(characteristic.uuid, "Failed with status: $status")
            }
        }
    }

    fun readCharacteristic(serviceUuid: UUID, characteristicUuid: UUID) {
        val gatt = bluetoothGatt

        if (gatt == null) {
            _operationStatus.value = BleOperationStatus.Error("Not connected to any device")
            return
        }

        val service = gatt.getService(serviceUuid)
        if (service == null) {
            _operationStatus.value = BleOperationStatus.ReadFailure(characteristicUuid, "Service not found: $serviceUuid")
            return
        }

        val characteristic = service.getCharacteristic(characteristicUuid)
        if (characteristic == null) {
            _operationStatus.value = BleOperationStatus.ReadFailure(characteristicUuid, "Characteristic not found")
            return
        }

        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
            _operationStatus.value = BleOperationStatus.ReadFailure(characteristicUuid, "Characteristic does not support read operation")
            return
        }

        try {
            if (!gatt.readCharacteristic(characteristic)) {
                _operationStatus.value = BleOperationStatus.ReadFailure(characteristicUuid, "Failed to initiate read operation")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading characteristic: ${e.message}")
            _operationStatus.value = BleOperationStatus.ReadFailure(characteristicUuid, "Error: ${e.message}")
        }
    }

    fun writeCharacteristic(serviceUuid: UUID, characteristicUuid: UUID, data: String) {
        val gatt = bluetoothGatt

        if (gatt == null) {
            _operationStatus.value = BleOperationStatus.Error("Not connected to any device")
            return
        }

        val service = gatt.getService(serviceUuid)
        if (service == null) {
            _operationStatus.value = BleOperationStatus.WriteFailure(characteristicUuid, "Service not found: $serviceUuid")
            return
        }

        val characteristic = service.getCharacteristic(characteristicUuid)
        if (characteristic == null) {
            _operationStatus.value = BleOperationStatus.WriteFailure(characteristicUuid, "Characteristic not found")
            return
        }

        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) &&
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0)) {
            _operationStatus.value = BleOperationStatus.WriteFailure(characteristicUuid, "Characteristic does not support write operations")
            return
        }

        try {
            val byteData = data.toByteArray(Charsets.UTF_8)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }

                val writeResult = gatt.writeCharacteristic(characteristic, byteData, writeType)
                if (writeResult != BluetoothGatt.GATT_SUCCESS) {
                    _operationStatus.value = BleOperationStatus.WriteFailure(characteristicUuid, "Failed to initiate write operation: $writeResult")
                }
            } else {
                // Legacy
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                characteristic.value = byteData

                val writeResult = gatt.writeCharacteristic(characteristic)
                if (writeResult == false) {
                    _operationStatus.value = BleOperationStatus.WriteFailure(characteristicUuid, "Failed to initiate write operation")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing characteristic: ${e.message}")
            _operationStatus.value = BleOperationStatus.WriteFailure(characteristicUuid, "Error: ${e.message}")
        }
    }

    fun toggleCharacteristicNotification(serviceUuid: UUID, characteristicUuid: UUID) {
        val gatt = bluetoothGatt

        if (gatt == null) {
            _operationStatus.value = BleOperationStatus.Error("Not connected to any device")
            return
        }

        val service = gatt.getService(serviceUuid)
        if (service == null) {
            _operationStatus.value = BleOperationStatus.NotificationChangeFailure(characteristicUuid, "Service not found: $serviceUuid")
            return
        }

        val characteristic = service.getCharacteristic(characteristicUuid)
        if (characteristic == null) {
            _operationStatus.value = BleOperationStatus.NotificationChangeFailure(characteristicUuid, "Characteristic not found")
            return
        }

        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) &&
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE == 0)) {
            _operationStatus.value = BleOperationStatus.NotificationChangeFailure(characteristicUuid,
                "Characteristic does not support notifications or indications")
            return
        }

        try {
            // Determine current notification state
            val notificationsEnabled = notificationEnabledCharacteristics.contains(characteristicUuid)

            // Set the local system to receive notifications from this characteristic
            val newNotificationState = !notificationsEnabled
            if (!gatt.setCharacteristicNotification(characteristic, newNotificationState)) {
                _operationStatus.value = BleOperationStatus.NotificationChangeFailure(characteristicUuid,
                    "Failed to set characteristic notification")
                return
            }

            // Find the Client Characteristic Configuration Descriptor
            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor == null) {
                _operationStatus.value = BleOperationStatus.NotificationChangeFailure(characteristicUuid,
                    "CCC descriptor not found")
                return
            }

            // Write to the descriptor to enable/disable notifications on the remote device
            val value = if (notificationsEnabled) {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            } else {
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val descriptorWriteResult = gatt.writeDescriptor(descriptor, value)
                if (descriptorWriteResult != BluetoothGatt.GATT_SUCCESS) {
                    _operationStatus.value = BleOperationStatus.NotificationChangeFailure(characteristicUuid,
                        "Failed to write to CCC descriptor: $descriptorWriteResult")
                }
            } else {
                // Legacy
                descriptor.value = value
                val descriptorWriteResult = gatt.writeDescriptor(descriptor)
                if (descriptorWriteResult == false) {
                    _operationStatus.value = BleOperationStatus.NotificationChangeFailure(characteristicUuid,
                        "Failed to write to CCC descriptor")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling notifications: ${e.message}")
            _operationStatus.value = BleOperationStatus.NotificationChangeFailure(characteristicUuid,
                "Error: ${e.message}")
        }
    }

    private fun closeGatt() {
        try {
            Log.d(TAG, "Closing GATT connection")
            bluetoothGatt?.close()
            bluetoothGatt = null
            _services.value = emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT: ${e.message}")
        }
    }

    sealed class ScanState {
        object Idle : ScanState()
        object Scanning : ScanState()
        data class DevicesFound(val devices: List<DeviceWrapper>) : ScanState()
        data class Error(val message: String, val devices: List<DeviceWrapper>?) : ScanState()
    }

    sealed class ConnectionState {
        data class Connecting(val deviceAddress: String) : ConnectionState()
        data class Connected(val deviceAddress: String, val deviceName: String? = null) : ConnectionState()
        object Disconnecting : ConnectionState()
        data class Disconnected(val deviceAddress: String? = null) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    sealed class BleOperationStatus {
        data class Success(val operation: String) : BleOperationStatus()
        data class ReadSuccess(val characteristicUuid: UUID, val value: ByteArray) : BleOperationStatus() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as ReadSuccess

                if (characteristicUuid != other.characteristicUuid) return false
                if (!value.contentEquals(other.value)) return false

                return true
            }

            override fun hashCode(): Int {
                var result = characteristicUuid.hashCode()
                result = 31 * result + value.contentHashCode()
                return result
            }
        }
        data class WriteSuccess(val characteristicUuid: UUID) : BleOperationStatus()
        data class NotificationChangeSuccess(val characteristicUuid: UUID, val enabled: Boolean) : BleOperationStatus()

        data class Failure(val operation: String, val message: String) : BleOperationStatus()
        data class ReadFailure(val characteristicUuid: UUID, val message: String) : BleOperationStatus()
        data class WriteFailure(val characteristicUuid: UUID, val message: String) : BleOperationStatus()
        data class NotificationChangeFailure(val characteristicUuid: UUID, val message: String) : BleOperationStatus()

        data class Error(val message: String) : BleOperationStatus()
    }
}
