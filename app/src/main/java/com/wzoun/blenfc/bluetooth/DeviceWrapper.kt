package com.wzoun.blenfc.bluetooth

import android.bluetooth.BluetoothDevice

data class DeviceWrapper(
    val device: BluetoothDevice,
    val rssi: Int
)
