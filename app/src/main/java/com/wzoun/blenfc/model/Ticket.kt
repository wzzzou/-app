package com.wzoun.blenfc.model

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class Ticket(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val amount: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val officerId: String
) {
    fun toJsonString(): String {
        return JSONObject().apply {
            put("id", id)
            put("description", description)
            put("amount", amount)
            // Using a locale-independent format for JSON is good practice, ISO 8601 is standard
            // SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            // However, the existing format is also common. Keeping it as is for now.
            put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp)))
            put("officerId", officerId)
        }.toString()
    }

    companion object {
        fun fromJsonString(jsonString: String): Ticket? {
            return try {
                val json = JSONObject(jsonString)
                Ticket(
                    id = json.optString("id", UUID.randomUUID().toString()),
                    description = json.getString("description"),
                    amount = json.getDouble("amount"),
                    // Timestamp parsing from JSON:
                    // If the JSON contains the "yyyy-MM-dd HH:mm:ss" string, you'd parse it back to Long.
                    // For now, assuming timestamp is mainly for outgoing data or handled by default System.currentTimeMillis() on receive if not present.
                    // timestamp = json.optLong("timestamp_ms", System.currentTimeMillis()), // if you store it as long
                    // officerId = json.getString("officerId")
                    officerId = json.getString("officerId")
                    // If timestamp is string in JSON:
                    // timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(json.getString("timestamp"))?.time ?: System.currentTimeMillis()
                )
            } catch (e: Exception) {
                null // Handle parsing error
            }
        }
    }
}
