package com.wzoun.blenfc

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wzoun.blenfc.bluetooth.DeviceWrapper

class DeviceListAdapter(private val onConnectClick: (DeviceWrapper) -> Unit) :
    ListAdapter<DeviceWrapper, DeviceListAdapter.DeviceViewHolder>(DeviceDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = getItem(position)
        holder.bind(device)
    }

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDeviceName: TextView = itemView.findViewById(R.id.tvDeviceName)
        private val tvDeviceAddress: TextView = itemView.findViewById(R.id.tvDeviceAddress)
        private val tvDeviceRssi: TextView = itemView.findViewById(R.id.tvDeviceRssi)
        private val btnConnect: Button = itemView.findViewById(R.id.btnConnect)

        fun bind(deviceWrapper: DeviceWrapper) {
            val device = deviceWrapper.device
            val deviceName = device.name ?: "Unknown Device"

            tvDeviceName.text = deviceName
            tvDeviceAddress.text = device.address
            tvDeviceRssi.text = itemView.context.getString(R.string.rssi_format, deviceWrapper.rssi)

            btnConnect.setOnClickListener {
                onConnectClick(deviceWrapper)
            }
        }
    }

    private class DeviceDiffCallback : DiffUtil.ItemCallback<DeviceWrapper>() {
        override fun areItemsTheSame(oldItem: DeviceWrapper, newItem: DeviceWrapper): Boolean {
            return oldItem.device.address == newItem.device.address
        }

        override fun areContentsTheSame(oldItem: DeviceWrapper, newItem: DeviceWrapper): Boolean {
            return oldItem.device.address == newItem.device.address &&
                    oldItem.rssi == newItem.rssi
        }
    }
}
