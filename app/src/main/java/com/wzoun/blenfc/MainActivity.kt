package com.wzoun.blenfc

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var pagerAdapter: MainPagerAdapter

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var pendingIntent: PendingIntent
    private lateinit var intentFiltersArray: Array<IntentFilter>
    private lateinit var techListsArray: Array<Array<String>>

    private var currentTagToWrite: String? = null

    private var selectedServiceCharUuidsForMCU: Pair<UUID, UUID>? = null


    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE_BLE = 101 // Can be combined if requesting all at once
        private const val PERMISSION_REQUEST_CODE_NFC = 102 // Can be combined
        private const val PERMISSION_REQUEST_CODE_ALL = 103
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 添加这一行来设置应用标题
        title = getString(R.string.app_title)
        // 或使用 supportActionBar?.title = getString(R.string.app_title)

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        pagerAdapter = MainPagerAdapter(this)
        viewPager.adapter = pagerAdapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = pagerAdapter.getPageTitle(position)
        }.attach()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, getString(R.string.nfc_not_supported), Toast.LENGTH_LONG).show()
        } else {
            if (!nfcAdapter!!.isEnabled) {
                Toast.makeText(this, getString(R.string.nfc_disabled), Toast.LENGTH_LONG).show()
            }
            initializeNfcForegroundDispatch()
        }
        requestAllPermissions()
    }


    private fun initializeNfcForegroundDispatch() {
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        val ndef = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            try {
                addDataType("application/json")
                addDataType("text/plain")
            } catch (e: IntentFilter.MalformedMimeTypeException) {
                Log.e(TAG, "Malformed MIME type for NDEF filter", e)
                throw RuntimeException("Failed to add MIME type.", e)
            }
        }
        val tagIntentFilter = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED) // Renamed for clarity
        intentFiltersArray = arrayOf(ndef, tagIntentFilter)
        techListsArray = arrayOf(arrayOf(Ndef::class.java.name))
    }

    public override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFiltersArray, techListsArray)
    }

    public override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "New Intent: ${intent.action}, Data to write: $currentTagToWrite")

        val tagFromIntent: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

        if (tagFromIntent != null && currentTagToWrite != null) {
            performNfcWrite(tagFromIntent, currentTagToWrite!!)
            // currentTagToWrite = null // Cleared in performNfcWrite
            return
        }

        if (NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {

            if (tagFromIntent != null) {
                val nfcOperationsFragment = findNfcOperationsFragment()
                val messages: Array<NdefMessage>? = getNdefMessagesFromIntent(intent)

                if (messages != null && messages.isNotEmpty()) {
                    messages.firstOrNull()?.records?.firstOrNull()?.let { record ->
                        val payload = record.payload
                        val mimeType = record.toMimeType() ?: String(record.type, Charsets.US_ASCII)
                        var contentToDisplay: String

                        Log.d(TAG, "NFC Record TNF: ${record.tnf}, MimeType: $mimeType")

                        contentToDisplay = when {
                            "application/json" == mimeType -> String(payload, Charsets.UTF_8)
                            record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                                try {
                                    val langCodeLength = payload[0].toInt() and 0x3F
                                    val textEncoding = if ((payload[0].toInt() and 0x80) == 0) Charsets.UTF_8 else Charsets.UTF_16
                                    String(payload, langCodeLength + 1, payload.size - langCodeLength - 1, textEncoding)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing RTD_TEXT record", e)
                                    getString(R.string.nfc_parse_error_rtd_text, payload.joinToString("") { "%02x".format(it) })
                                }
                            }
                            else -> {
                                val hexPayload = payload.joinToString("") { "%02x".format(it) }
                                val asciiPayload = String(payload, Charsets.US_ASCII).filter {
                                    it.isLetterOrDigit() || it.isWhitespace() || it in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
                                }
                                getString(R.string.nfc_unparsed_data_display, mimeType, hexPayload, asciiPayload)
                            }
                        }
                        Log.d(TAG, "Read content: $contentToDisplay")
                        nfcOperationsFragment?.displayNfcReadData(contentToDisplay)
                        Toast.makeText(this, getString(R.string.nfc_tag_read_toast), Toast.LENGTH_SHORT).show()
                    } ?: run {
                        nfcOperationsFragment?.displayNfcReadData(getString(R.string.nfc_tag_empty_or_no_records))
                        Toast.makeText(this, getString(R.string.nfc_tag_no_ndef_records), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val tagId = tagFromIntent.id.joinToString("") { "%02X".format(it) }
                    nfcOperationsFragment?.displayNfcReadData(getString(R.string.nfc_tag_not_ndef_or_empty_with_id, tagId))
                    Toast.makeText(this, getString(R.string.nfc_tag_empty), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getNdefMessagesFromIntent(intent: Intent): Array<NdefMessage>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.mapNotNull { it as? NdefMessage }?.toTypedArray()
        }
    }


    private fun findNfcOperationsFragment(): NfcOperationsFragment? {
        return supportFragmentManager.fragments.find { it is NfcOperationsFragment } as? NfcOperationsFragment
    }

    private fun findConnectionFragment(): ConnectionFragment? {
        return supportFragmentManager.fragments.find { it is ConnectionFragment } as? ConnectionFragment
    }


    fun writeNfcTagDirectly(jsonData: String) {
        if (nfcAdapter == null) {
            Toast.makeText(this, getString(R.string.nfc_not_supported), Toast.LENGTH_LONG).show()
            return
        }
        if (!nfcAdapter!!.isEnabled) {
            Toast.makeText(this, getString(R.string.nfc_disabled), Toast.LENGTH_LONG).show()
            return
        }
        if (!hasNfcPermission()) {
            requestNfcPermission() // Will prompt user, they can try again after granting
            return
        }
        currentTagToWrite = jsonData
        Toast.makeText(this, getString(R.string.status_nfc_reading_for_write), Toast.LENGTH_LONG).show()
    }

    private fun performNfcWrite(tag: Tag, dataToWrite: String) {
        val ndefRecord = NdefRecord.createMime("application/json", dataToWrite.toByteArray(Charsets.UTF_8))
        val ndefMessage = NdefMessage(arrayOf(ndefRecord))
        val ndef = Ndef.get(tag)

        if (ndef == null) {
            Toast.makeText(this, getString(R.string.nfc_tag_not_ndef_compatible), Toast.LENGTH_SHORT).show()
            currentTagToWrite = null
            return
        }

        try {
            ndef.connect()
            if (!ndef.isWritable) {
                Toast.makeText(this, getString(R.string.nfc_tag_not_writable), Toast.LENGTH_SHORT).show()
                currentTagToWrite = null
                return
            }
            if (ndef.maxSize < ndefMessage.toByteArray().size) {
                Toast.makeText(this, getString(R.string.nfc_tag_too_small), Toast.LENGTH_SHORT).show()
                currentTagToWrite = null
                return
            }
            ndef.writeNdefMessage(ndefMessage)
            Toast.makeText(this, getString(R.string.nfc_write_success_toast), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.nfc_write_failed_toast, e.message), Toast.LENGTH_LONG).show()
            Log.e(TAG, "NFC Write failed", e)
        } finally {
            try {
                ndef.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing NDEF", e)
            }
            currentTagToWrite = null // Clear after attempt, regardless of success/failure
        }
    }

    fun sendJsonToMcuViaFragment(jsonData: String) {
        val connectionFragment = findConnectionFragment()
        if (connectionFragment != null) {
            if (selectedServiceCharUuidsForMCU != null) { // Check if a characteristic is actually selected
                connectionFragment.sendDataToSelectedCharacteristic(jsonData)
            } else {
                Toast.makeText(this, getString(R.string.error_no_ble_characteristic_selected), Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.error_connection_fragment_not_found), Toast.LENGTH_SHORT).show()
        }
    }

    fun setSelectedCharacteristicUuids(serviceUuid: UUID, characteristicUuid: UUID) {
        this.selectedServiceCharUuidsForMCU = Pair(serviceUuid, characteristicUuid)
        Log.d(TAG, "Characteristic $characteristicUuid in service $serviceUuid set in MainActivity for MCU writes.")
        findConnectionFragment()?.updateSelectedCharacteristicDisplay()
    }

    fun getSelectedCharacteristicUuids(): Pair<UUID, UUID>? {
        return selectedServiceCharUuidsForMCU
    }

    fun clearSelectedCharacteristicUuids() {
        this.selectedServiceCharUuidsForMCU = null
        Log.d(TAG, "Selected characteristic cleared in MainActivity.")
        findConnectionFragment()?.updateSelectedCharacteristicDisplay()
    }

    fun hasBlePermissions(): Boolean {
        val context = applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED && // Usually granted at install
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED // Usually granted at install
        }
    }

    private fun hasNfcPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.NFC) == PackageManager.PERMISSION_GRANTED
    }

    fun requestBlePermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            // BLUETOOTH and BLUETOOTH_ADMIN are often granted at install for older SDKs, but doesn't hurt to include
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE_BLE)
        }
    }

    private fun requestNfcPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NFC) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.NFC), PERMISSION_REQUEST_CODE_NFC)
        }
    }

    private fun requestAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        // BLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasBlePermissions()) { // Simplified check
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }
        // NFC
        if (!hasNfcPermission()) {
            permissionsToRequest.add(Manifest.permission.NFC)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE_ALL)
        }
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE_ALL, PERMISSION_REQUEST_CODE_BLE, PERMISSION_REQUEST_CODE_NFC -> {
                var allNeededPermissionsGranted = true
                if (grantResults.isEmpty()) {
                    allNeededPermissionsGranted = false // No permissions granted (e.g., dialog cancelled)
                }

                val essentialPermissions = listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NFC
                )

                grantResults.forEachIndexed { index, result ->
                    val permission = permissions.getOrNull(index)
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        Log.w(TAG, "Permission denied: $permission")
                        if (permission in essentialPermissions) {
                            allNeededPermissionsGranted = false
                        }
                    } else {
                        Log.i(TAG, "Permission granted: $permission")
                    }
                }

                if (allNeededPermissionsGranted || (hasBlePermissions() && hasNfcPermission()) ) { // Check current state as well
                    Toast.makeText(this, getString(R.string.permissions_granted_toast), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.permissions_denied_toast), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
