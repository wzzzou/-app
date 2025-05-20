package com.wzoun.blenfc

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

    companion object {
        private const val NUM_PAGES = 2
    }

    override fun getItemCount(): Int = NUM_PAGES

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> ConnectionFragment()
            1 -> NfcOperationsFragment()
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }

    fun getPageTitle(position: Int): String {
        return when (position) {
            0 -> "BLE连接"
            1 -> "NFC操作"
            else -> "未知"
        }
    }
}
