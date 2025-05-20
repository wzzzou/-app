package com.wzoun.blenfc.bluetooth

import android.bluetooth.BluetoothGattCharacteristic


val BluetoothGattCharacteristic.canRead: Boolean
    get() = (properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0

val BluetoothGattCharacteristic.canWrite: Boolean
    get() = (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0

val BluetoothGattCharacteristic.canWriteNoResponse: Boolean
    get() = (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

val BluetoothGattCharacteristic.canNotify: Boolean
    get() = (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0

val BluetoothGattCharacteristic.canIndicate: Boolean
    get() = (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
