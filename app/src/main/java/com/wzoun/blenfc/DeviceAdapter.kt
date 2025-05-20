package com.wzoun.blenfc

import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wzoun.blenfc.databinding.ItemDeviceBinding

// Define the data structure used by the adapter
// Pair<BluetoothDevice, String> where String is the device name (already fetched safely)
typealias DeviceInfo = Pair<BluetoothDevice, String>

class DeviceAdapter(
    private val onClickListener: (DeviceInfo) -> Unit
) : ListAdapter<DeviceInfo, DeviceAdapter.DeviceViewHolder>(DeviceDiffCallback()) {

    // ViewHolder using View Binding
    inner class DeviceViewHolder(private val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(deviceInfo: DeviceInfo) {
            // 使用布局文件中的正确ID: tvDeviceName 和 tvDeviceAddress
            binding.tvDeviceName.text = deviceInfo.second
            binding.tvDeviceAddress.text = deviceInfo.first.address

            // 如果需要显示RSSI，需要确保DeviceInfo包含这个数据
            // 假设不包含RSSI信息，可以暂时隐藏这个TextView
            binding.tvDeviceRssi.visibility = android.view.View.GONE

            // 或者如果DeviceInfo是三元组并包含RSSI，可以这样设置：
            // if (deviceInfo is Triple<*, *, *>) {
            //     binding.tvDeviceRssi.text = "RSSI: ${deviceInfo.third} dBm"
            // }

            // 设置卡片的点击事件
            binding.root.setOnClickListener {
                onClickListener(deviceInfo)
            }

            // 可选：如果你想为连接按钮设置单独的点击事件
            binding.btnConnect.setOnClickListener {
                onClickListener(deviceInfo)
            }
        }
    }

    // Create new views (invoked by the layout manager)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DeviceViewHolder(binding)
    }

    // Replace the contents of a view (invoked by the layout manager)
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

// DiffUtil Callback
class DeviceDiffCallback : DiffUtil.ItemCallback<DeviceInfo>() {
    override fun areItemsTheSame(oldItem: DeviceInfo, newItem: DeviceInfo): Boolean {
        // Check if items represent the same object by comparing device addresses
        return oldItem.first.address == newItem.first.address
    }

    override fun areContentsTheSame(oldItem: DeviceInfo, newItem: DeviceInfo): Boolean {
        // Check if the content (name) is the same
        return oldItem.second == newItem.second
    }
}
