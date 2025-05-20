package com.wzoun.blenfc.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.wzoun.blenfc.R
import org.json.JSONException
import org.json.JSONObject

class NfcManager(private val context: Context) {
    private var nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)
    private var nfcReadListener: NfcReadListener? = null
    private val tag = "NfcManager"
    private var pendingNdefMessage: NdefMessage? = null

    // 定义一个临时存储NFC标签的变量，用于后续写入
    private var pendingTag: Tag? = null

    interface NfcReadListener {
        fun onNfcTagRead(tagId: String, messages: List<String>)
        fun onNfcError(errorMessage: String)
    }

    fun setNfcReadListener(listener: NfcReadListener) {
        this.nfcReadListener = listener
    }

    fun isNfcSupported(): Boolean = nfcAdapter != null

    fun isNfcEnabled(): Boolean = nfcAdapter?.isEnabled == true

    fun enableNfcForegroundDispatch(activity: Activity) {
        if (!isNfcSupported()) {
            Toast.makeText(context, R.string.nfc_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        if (!isNfcEnabled()) {
            Toast.makeText(context, R.string.nfc_disabled, Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(context, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, pendingIntentFlags)

        val ndefIntentFilter = android.content.IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            try {
                addDataType("*/*")
            } catch (e: android.content.IntentFilter.MalformedMimeTypeException) {
                Log.e(tag, "Failed to add MIME type for NDEF dispatch.", e)
                throw RuntimeException("Failed to add MIME type.", e)
            }
        }

        val techIntentFilter = android.content.IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        val tagIntentFilter = android.content.IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)

        val intentFiltersArray = arrayOf(ndefIntentFilter, techIntentFilter, tagIntentFilter)
        val techListsArray = arrayOf(arrayOf(Ndef::class.java.name), arrayOf(NdefFormatable::class.java.name))

        nfcAdapter?.enableForegroundDispatch(activity, pendingIntent, intentFiltersArray, techListsArray)
        Log.d(tag, "NFC Foreground Dispatch Enabled")
    }

    fun disableNfcForegroundDispatch(activity: Activity) {
        nfcAdapter?.disableForegroundDispatch(activity)
        Log.d(tag, "NFC Foreground Dispatch Disabled")
    }

    // 准备电子罚单JSON数据用于写入NFC标签
    fun prepareViolationData(policeId: String, violationContent: String,
                             violationLocation: String, violationTime: String): String {
        val jsonObject = JSONObject().apply {
            put("policeId", policeId)
            put("violationContent", violationContent)
            put("violationLocation", violationLocation)
            put("violationTime", violationTime)
        }

        val jsonString = jsonObject.toString()
        Log.d(tag, "Prepared violation data: $jsonString")

        // 创建NDEF消息并存储等待写入
        pendingNdefMessage = createNdefMessage(jsonString)

        Toast.makeText(context, "数据已准备好写入NFC: $jsonString", Toast.LENGTH_SHORT).show()
        return jsonString
    }

    // 创建NDEF消息
    private fun createNdefMessage(content: String): NdefMessage {
        val mimeRecord = NdefRecord.createMime("application/json", content.toByteArray(Charsets.UTF_8))
        return NdefMessage(arrayOf(mimeRecord))
    }

    fun handleIntent(intent: Intent) {
        val action = intent.action

        if (NfcAdapter.ACTION_NDEF_DISCOVERED == action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == action) {

            // 从Intent中获取NFC标签
            val nfcTag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }

            if (nfcTag == null) {
                Log.e(tag, "No NFC tag detected in the intent")
                nfcReadListener?.onNfcError("标签未检测到")
                return
            }

            // 获取标签ID
            val tagId = bytesToHexString(nfcTag.id)
            Log.d(tag, "NFC Tag detected with ID: $tagId")

            // 检查是否有待写入的数据
            if (pendingNdefMessage != null) {
                // 写入模式 - 保存Tag对象以供后续使用
                pendingTag = nfcTag
                // 调用字符串版本的writeToTag，传递tagId而不是Tag对象
                writeToTag(tagId, pendingNdefMessage!!)
                pendingNdefMessage = null // 清除待写入数据
                pendingTag = null // 清除存储的Tag对象
                return
            }

            // 读取模式
            val messages = mutableListOf<String>()

            // 尝试读取NDEF消息
            val ndef = Ndef.get(nfcTag)
            if (ndef != null) {
                try {
                    ndef.connect()

                    val ndefMessage = ndef.cachedNdefMessage
                    if (ndefMessage != null) {
                        val records = ndefMessage.records

                        if (records.isEmpty()) {
                            Log.d(tag, "Tag contains no NDEF records")
                            messages.add("NFC标签没有NDEF记录")
                        } else {
                            // 处理每条记录
                            for (record in records) {
                                val payload = record.payload

                                // 根据TNF和类型处理不同的记录
                                when (record.tnf) {
                                    // MIME类型记录 - 我们的应用使用application/json
                                    NdefRecord.TNF_MIME_MEDIA -> {
                                        val mimeType = String(record.type, Charsets.UTF_8)
                                        if (mimeType == "application/json") {
                                            try {
                                                // 尝试解析为JSON
                                                val jsonString = String(payload, Charsets.UTF_8)
                                                val json = JSONObject(jsonString)

                                                // 提取电子罚单信息并格式化
                                                val policeId = json.optString("policeId", "")
                                                val violationContent = json.optString("violationContent", "")
                                                val violationLocation = json.optString("violationLocation", "")
                                                val violationTime = json.optString("violationTime", "")

                                                val formattedMessage = """
                                                    警员ID: $policeId
                                                    违章内容: $violationContent
                                                    违章地点: $violationLocation
                                                    违章时间: $violationTime
                                                """.trimIndent()

                                                messages.add(formattedMessage)
                                            } catch (e: JSONException) {
                                                // 如果不是有效JSON，显示原始内容
                                                messages.add(String(payload, Charsets.UTF_8))
                                            }
                                        } else {
                                            // 其他MIME类型的数据
                                            messages.add(String(payload, Charsets.UTF_8))
                                        }
                                    }
                                    // 普通文本记录
                                    NdefRecord.TNF_WELL_KNOWN -> {
                                        if (isTextRecord(record)) {
                                            messages.add(parseTextRecord(record))
                                        } else if (isUriRecord(record)) {
                                            messages.add(parseUriRecord(record))
                                        } else {
                                            messages.add(String(payload, Charsets.UTF_8))
                                        }
                                    }
                                    // 其他类型记录
                                    else -> {
                                        // 尝试以UTF-8解析
                                        try {
                                            messages.add(String(payload, Charsets.UTF_8))
                                        } catch (e: Exception) {
                                            // 如果解析失败，显示十六进制
                                            messages.add(bytesToHexString(payload))
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Log.d(tag, "Empty NDEF message")
                        messages.add("NFC标签为空")
                    }

                    ndef.close()
                } catch (e: Exception) {
                    Log.e(tag, "Error reading NDEF message: ${e.message}", e)
                    nfcReadListener?.onNfcError("读取NFC标签出错: ${e.message}")
                    return
                }
            } else {
                // 非NDEF格式标签
                messages.add("NFC标签ID: $tagId (非NDEF格式或为空)")
            }

            // 通知监听器
            Toast.makeText(context, "已读取NFC标签", Toast.LENGTH_SHORT).show()
            nfcReadListener?.onNfcTagRead(tagId, messages)
        }
    }

    // 修改writeToTag方法，接收String而不是Tag
    private fun writeToTag(tagId: String, message: NdefMessage) {
        // 使用之前保存的Tag对象
        val tag = pendingTag
        if (tag == null) {
            Log.e(this.tag, "No pending Tag available for writing")
            nfcReadListener?.onNfcError("没有可用的NFC标签")
            return
        }

        // 先尝试使用NDEF格式直接写入
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()

                // 检查标签是否可写
                if (!ndef.isWritable) {
                    Toast.makeText(context, "NFC标签不可写入", Toast.LENGTH_SHORT).show()
                    nfcReadListener?.onNfcError("NFC标签不可写")
                    return
                }

                // 检查标签容量
                if (ndef.maxSize < message.byteArrayLength) {
                    Toast.makeText(context, "NFC标签容量不足", Toast.LENGTH_SHORT).show()
                    nfcReadListener?.onNfcError("NFC标签容量不足")
                    return
                }

                // 写入标签
                ndef.writeNdefMessage(message)
                Toast.makeText(context, "NFC标签写入成功", Toast.LENGTH_SHORT).show()
                ndef.close()
                return
            } catch (e: Exception) {
                Log.e(this.tag, "Error writing to NDEF tag: ${e.message}", e)
                Toast.makeText(context, "NFC标签写入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                nfcReadListener?.onNfcError("NFC标签写入错误: ${e.message}")
                return
            }
        }

        // 如果不是NDEF格式，尝试格式化并写入
        val ndefFormatable = NdefFormatable.get(tag)
        if (ndefFormatable != null) {
            try {
                ndefFormatable.connect()
                ndefFormatable.format(message)
                Toast.makeText(context, "NFC标签写入成功", Toast.LENGTH_SHORT).show()
                ndefFormatable.close()
            } catch (e: Exception) {
                Log.e(this.tag, "Error formatting and writing to NFC tag: ${e.message}", e)
                Toast.makeText(context, "NFC标签写入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                nfcReadListener?.onNfcError("NFC标签格式化错误: ${e.message}")
            }
        } else {
            Toast.makeText(context, "NFC标签不兼容NDEF格式", Toast.LENGTH_SHORT).show()
            nfcReadListener?.onNfcError("NFC标签不兼容NDEF格式")
        }
    }

    // 辅助方法：将字节数组转换为十六进制字符串
    private fun bytesToHexString(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02X", b))
        }
        return sb.toString()
    }

    // 辅助方法：检查是否为文本记录
    private fun isTextRecord(record: NdefRecord): Boolean {
        return record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                java.util.Arrays.equals(record.type, "T".toByteArray(Charsets.US_ASCII))
    }

    // 辅助方法：检查是否为URI记录
    private fun isUriRecord(record: NdefRecord): Boolean {
        return record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                java.util.Arrays.equals(record.type, "U".toByteArray(Charsets.US_ASCII))
    }

    // 辅助方法：解析文本记录
    private fun parseTextRecord(record: NdefRecord): String {
        try {
            val payload = record.payload
            val textEncoding = if ((payload[0].toInt() and 0x80) == 0) "UTF-8" else "UTF-16"
            val languageCodeLength = payload[0].toInt() and 0x3F

            return String(
                payload,
                1 + languageCodeLength,
                payload.size - 1 - languageCodeLength,
                charset(textEncoding)
            )
        } catch (e: Exception) {
            Log.e(tag, "Error parsing text record", e)
            return "无法解析TEXT记录 (raw: ${bytesToHexString(record.payload)})"
        }
    }

    // 辅助方法：解析URI记录
    private fun parseUriRecord(record: NdefRecord): String {
        try {
            val payload = record.payload
            val prefix = getUriPrefix(payload[0].toInt())
            return prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(tag, "Error parsing URI record", e)
            return "解析URI记录出错: ${e.message}"
        }
    }

    // 辅助方法：获取URI前缀
    private fun getUriPrefix(prefixCode: Int): String {
        return when (prefixCode) {
            0x00 -> ""
            0x01 -> "http://www."
            0x02 -> "https://www."
            0x03 -> "http://"
            0x04 -> "https://"
            0x05 -> "tel:"
            0x06 -> "mailto:"
            0x07 -> "ftp://anonymous:anonymous@"
            0x08 -> "ftp://ftp."
            0x09 -> "ftps://"
            0x0A -> "sftp://"
            0x0B -> "smb://"
            0x0C -> "nfs://"
            0x0D -> "ftp://"
            0x0E -> "dav://"
            0x0F -> "news:"
            0x10 -> "telnet://"
            0x11 -> "imap:"
            0x12 -> "rtsp://"
            0x13 -> "urn:"
            0x14 -> "pop:"
            0x15 -> "sip:"
            0x16 -> "sips:"
            0x17 -> "tftp:"
            0x18 -> "btspp://"
            0x19 -> "btl2cap://"
            0x1A -> "btgoep://"
            0x1B -> "tcpobex://"
            0x1C -> "irdaobex://"
            0x1D -> "file://"
            0x1E -> "urn:epc:id:"
            0x1F -> "urn:epc:tag:"
            0x20 -> "urn:epc:pat:"
            0x21 -> "urn:epc:raw:"
            0x22 -> "urn:epc:"
            0x23 -> "urn:nfc:"
            else -> ""
        }
    }
}
