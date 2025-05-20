package com.wzoun.blenfc

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.wzoun.blenfc.viewmodel.NfcViewModel
import kotlinx.coroutines.launch

class NfcOperationsFragment : Fragment() {

    private lateinit var etJsonMessage: EditText
    private lateinit var btnWriteToMcu: Button
    private lateinit var btnWriteToNfc: Button
    private lateinit var tvNfcReadData: TextView
    private lateinit var nfcViewModel: NfcViewModel

    companion object {
        private const val TAG = "NfcOperationsFragment"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_nfc_operations, container, false)

        etJsonMessage = view.findViewById(R.id.etJsonMessage)
        btnWriteToMcu = view.findViewById(R.id.btnWriteToMcu)
        btnWriteToNfc = view.findViewById(R.id.btnWriteToNfc)
        tvNfcReadData = view.findViewById(R.id.tvNfcReadData)

        nfcViewModel = ViewModelProvider(requireActivity()).get(NfcViewModel::class.java)

        setupObservers()
        setupClickListeners()

        return view
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            nfcViewModel.nfcMessage.collect { message ->
                if (message.isNotEmpty()) {
                    etJsonMessage.setText(message)
                }
            }
        }
    }

    private fun setupClickListeners() {
        btnWriteToNfc.setOnClickListener {
            val jsonData = etJsonMessage.text.toString().trim()
            if (jsonData.isEmpty()) {
                Toast.makeText(context, getString(R.string.json_empty_error), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val mainActivity = activity as? MainActivity
            mainActivity?.writeNfcTagDirectly(jsonData)
            nfcViewModel.setNfcMessage(jsonData)
        }

        btnWriteToMcu.setOnClickListener {
            val jsonData = etJsonMessage.text.toString().trim()
            if (jsonData.isEmpty()) {
                Toast.makeText(context, getString(R.string.json_empty_error), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val mainActivity = activity as? MainActivity
            mainActivity?.let {
                if (it.hasBlePermissions()) {
                    it.sendJsonToMcuViaFragment(jsonData)
                    Log.d(TAG, "Sent JSON to MCU: $jsonData")
                } else {
                    it.requestBlePermissions()
                    Toast.makeText(context, getString(R.string.bluetooth_permissions_required), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun displayNfcReadData(data: String) {
        tvNfcReadData.text = data
        nfcViewModel.setNfcMessage(data)
    }
}
