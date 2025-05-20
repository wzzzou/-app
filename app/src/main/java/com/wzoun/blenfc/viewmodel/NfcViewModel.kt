package com.wzoun.blenfc.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NfcViewModel : ViewModel() {
    private val _nfcMessage = MutableStateFlow("")
    val nfcMessage: StateFlow<String> = _nfcMessage.asStateFlow()

    fun setNfcMessage(message: String) {
        _nfcMessage.value = message
    }
}
