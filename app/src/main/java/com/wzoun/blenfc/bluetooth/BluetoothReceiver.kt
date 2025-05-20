package com.wzoun.blenfc.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.wzoun.blenfc.R

@SuppressLint("MissingPermission") // Permissions checked by BluetoothManager or calling components
class BluetoothReceiver(
    private val onDeviceFound: (BluetoothDevice) -> Unit,
    private val onDiscoveryFinished: () -> Unit,
    private val onBluetoothStateChanged: (Int) -> Unit,
    private val onBondStateChanged: (BluetoothDevice, Int) -> Unit
) : BroadcastReceiver() {

    private val tag = "BluetoothReceiver"

    companion object {
        fun getIntentFilter(): IntentFilter {
            return IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND) // Classic discovery
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) // Classic discovery
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED) // BT on/off
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED) // Pairing
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothDevice.ACTION_FOUND -> {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                device?.let {
                    // Name can be null, handle it gracefully
                    val deviceName = try { it.name ?: context.getString(R.string.unknown_device) } catch (e: SecurityException) { context.getString(R.string.unknown_device) }
                    Log.d(tag, "Classic device found: $deviceName (${it.address})")
                    onDeviceFound(it)
                }
            }
            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                Log.d(tag, "Classic Bluetooth discovery finished.")
                onDiscoveryFinished()
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                Log.d(tag, "Bluetooth state changed to: $state")
                onBluetoothStateChanged(state)
            }
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                device?.let {
                    val deviceName = try { it.name ?: context.getString(R.string.unknown_device) } catch (e: SecurityException) { context.getString(R.string.unknown_device) }
                    Log.d(tag, "Bond state changed for $deviceName: $bondState")
                    onBondStateChanged(it, bondState)
                }
            }
        }
    }
}
