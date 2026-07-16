package com.bsbagley.spooler

import android.app.Application
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bsbagley.spooler.data.ScanHistoryStore
import com.bsbagley.spooler.data.ScanRecord
import com.bsbagley.spooler.data.SettingsStore
import com.bsbagley.spooler.nfc.TagReader
import com.bsbagley.spooler.nfc.toHex
import com.bsbagley.spooler.spoolman.SpoolmanClient
import com.bsbagley.spooler.tag.AnycubicDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data object Reading : ScanUiState
    data class Result(val record: ScanRecord) : ScanUiState
    data class Error(val message: String) : ScanUiState
}

sealed interface SendState {
    data object Idle : SendState
    data object Sending : SendState
    data class Success(val message: String) : SendState
    data class Error(val message: String) : SendState
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ScanHistoryStore(app)
    private val settings = SettingsStore(app)
    private val prettyJson = Json { prettyPrint = true }

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val _history = MutableStateFlow<List<ScanRecord>>(emptyList())
    val history: StateFlow<List<ScanRecord>> = _history.asStateFlow()

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    private val _spoolmanUrl = MutableStateFlow(settings.spoolmanUrl)
    val spoolmanUrl: StateFlow<String> = _spoolmanUrl.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _history.value = store.loadAll()
        }
    }

    /** Entry point from NfcAdapter.ReaderCallback — called on a binder thread. */
    fun onTagScanned(tag: Tag) {
        _uiState.value = ScanUiState.Reading
        _sendState.value = SendState.Idle
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val raw = TagReader.read(tag)
                val decoded = runCatching { AnycubicDecoder.decode(raw.bytes) }
                val now = System.currentTimeMillis()
                val record = ScanRecord(
                    timestampMillis = now,
                    timestampIso = Instant.ofEpochMilli(now).toString(),
                    uidHex = raw.uidHex,
                    atqaHex = raw.atqaHex,
                    sak = raw.sak,
                    pagesRead = raw.pagesRead,
                    complete = raw.complete,
                    hex = raw.bytes.toHex(),
                    filament = decoded.getOrNull(),
                    decodeError = decoded.exceptionOrNull()?.message,
                )
                _history.value = store.add(record)
                _uiState.value = ScanUiState.Result(record)
            } catch (e: IOException) {
                _uiState.value = ScanUiState.Error(
                    "Tag read failed (${e.message ?: "connection lost"}). " +
                        "Hold the tag still against the back of the phone and try again."
                )
            }
        }
    }

    fun showRecord(record: ScanRecord) {
        _uiState.value = ScanUiState.Result(record)
        _sendState.value = SendState.Idle
    }

    fun setSpoolmanUrl(url: String) {
        settings.spoolmanUrl = url
        _spoolmanUrl.value = settings.spoolmanUrl
    }

    /** Creates (or finds) the spool in Spoolman for this scan's decoded filament. */
    fun sendToSpoolman(record: ScanRecord) {
        val filament = record.filament ?: run {
            _sendState.value = SendState.Error("Nothing to send — this scan didn't decode.")
            return
        }
        if (_spoolmanUrl.value.isBlank()) {
            _sendState.value = SendState.Error("Set your Spoolman URL first (⚙ in the top bar).")
            return
        }
        _sendState.value = SendState.Sending
        viewModelScope.launch(Dispatchers.IO) {
            _sendState.value = try {
                when (val result = SpoolmanClient(_spoolmanUrl.value)
                    .importTag(filament, record.uidHex)) {
                    is SpoolmanClient.ImportResult.Created -> SendState.Success(
                        "Created spool #${result.spoolId} (${result.filamentName}" +
                            (result.estimatedWeightG?.let { ", ≈$it g" } ?: "") + ")"
                    )
                    is SpoolmanClient.ImportResult.AlreadyExists -> SendState.Success(
                        "Already in Spoolman as spool #${result.spoolId} (matched by tag UID)"
                    )
                }
            } catch (e: IOException) {
                SendState.Error(e.message ?: "Failed to reach Spoolman.")
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            store.clear()
            _history.value = emptyList()
        }
    }

    /** Full scan record as pretty JSON, for copy/share into Spoolman workflows. */
    fun recordJson(record: ScanRecord): String = prettyJson.encodeToString(record)
}
