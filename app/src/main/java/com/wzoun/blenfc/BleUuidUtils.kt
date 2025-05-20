package com.wzoun.blenfc // Make sure this matches your actual package structure

import java.util.UUID

object BleUuidUtils {

    // Standard Bluetooth Base UUID
    // private const val BLUETOOTH_BASE_UUID = "0000xxxx-0000-1000-8000-00805F9B34FB" // xxxx is the 16-bit short UUID
    // Common GATT Service UUIDs (Examples - Add more as needed)    private val serviceNameMap = mapOf(
    private val serviceNameMap = mapOf(
        "1800" to "Generic Access",
        "1801" to "Generic Attribute",
        "180D" to "Heart Rate",
        "180A" to "Device Information",
        "180F" to "Battery Service",
        "1802" to "Link Loss",
        "1803" to "Tx Power",
        "1804" to "Current Time Service",
        "1805" to "Next DST Change Service",
        "1806" to "Reference Time Update Service"
        // Add more standard services from bluetooth.com or specific custom services
    )

    // Common GATT Characteristic UUIDs (Examples - Add more as needed)
    private val characteristicNameMap = mapOf(
        "2A00" to "Device Name",
        "2A01" to "Appearance",
        "2A04" to "Peripheral Preferred Connection Parameters",
        "2A37" to "Heart Rate Measurement",
        "2A29" to "Manufacturer Name String",
        "2A25" to "Serial Number String",
        "2A24" to "Model Number String",
        "2A19" to "Battery Level",
        "2A2B" to "Current Time",
        "2A0F" to "Local Time Information"
        // Add more standard characteristics or specific custom characteristics
    )

    fun resolveServiceName(uuid: UUID): String {
        val shortUuid = getShortUuid(uuid)
        return serviceNameMap[shortUuid] ?: "Unknown Service ($shortUuid)"
    }

    fun resolveCharacteristicName(uuid: UUID): String {
        val shortUuid = getShortUuid(uuid)
        return characteristicNameMap[shortUuid] ?: "Unknown Characteristic ($shortUuid)"
    }

    fun getShortUuid(uuid: UUID): String {
        // For standard Bluetooth UUIDs (0000xxxx-0000-1000-8000-00805f9b34fb)
        // the xxxx part is the 16-bit identifier.        return uuid.toString().substring(4, 8).uppercase()
        return uuid.toString().substring(4, 8).uppercase()
    }

    fun isStandardUuid(uuid: UUID): Boolean {
        return uuid.toString().lowercase().endsWith("-0000-1000-8000-00805f9b34fb")
    }

    fun sixteenBitUuidToFullUuid(shortUuid: String): UUID? {
        if (shortUuid.length == 4) {
            try {
                return UUID.fromString("0000$shortUuid-0000-1000-8000-00805F9B34FB")
            } catch (e: IllegalArgumentException) {
                // Fallback for non-standard short UUIDs if needed, or just log/return null
            }
        }
        // If it's already a full UUID, try parsing directly (or handle as error)
        return try {
            UUID.fromString(shortUuid)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
