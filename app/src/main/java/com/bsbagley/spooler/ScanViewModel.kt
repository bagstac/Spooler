package com.bsbagley.spooler

import android.app.Application
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bsbagley.spooler.data.ScanHistoryStore
import com.bsbagley.spooler.data.ScanRecord
import com.bsbagley.spooler.data.SettingsStore
import com.bsbagley.spooler.filamentdb.OpenFilamentDatabaseClient
import com.bsbagley.spooler.nfc.TagReader
import com.bsbagley.spooler.nfc.toHex
import com.bsbagley.spooler.spoolman.SpoolmanClient
import com.bsbagley.spooler.tag.AnycubicDecoder
import com.bsbagley.spooler.tag.AnycubicEncoder
import com.bsbagley.spooler.tag.FilamentInfo
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

sealed interface LookupState {
    data object Idle : LookupState
    data object Loading : LookupState
    data class Found(val result: OpenFilamentDatabaseClient.LookupResult) : LookupState
    data class NotFound(val message: String) : LookupState
}

sealed interface WriteState {
    data object Idle : WriteState

    /** Write is armed: the next tag presented gets [bytes] written to it. */
    class Armed(val bytes: ByteArray) : WriteState
    data object Writing : WriteState
    data class Success(val message: String) : WriteState
    data class Error(val message: String) : WriteState
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ScanHistoryStore(app)
    private val settings = SettingsStore(app)
    private val haptics = Haptics(app)
    private val prettyJson = Json { prettyPrint = true }

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val _history = MutableStateFlow<List<ScanRecord>>(emptyList())
    val history: StateFlow<List<ScanRecord>> = _history.asStateFlow()

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    private val _spoolmanUrl = MutableStateFlow(settings.spoolmanUrl)
    val spoolmanUrl: StateFlow<String> = _spoolmanUrl.asStateFlow()

    private val _showRawDump = MutableStateFlow(settings.showRawDump)
    val showRawDump: StateFlow<Boolean> = _showRawDump.asStateFlow()

    private val _showHistory = MutableStateFlow(settings.showHistory)
    val showHistory: StateFlow<Boolean> = _showHistory.asStateFlow()

    private val _writeState = MutableStateFlow<WriteState>(WriteState.Idle)
    val writeState: StateFlow<WriteState> = _writeState.asStateFlow()

    private val _lookupState = MutableStateFlow<LookupState>(LookupState.Idle)
    val lookupState: StateFlow<LookupState> = _lookupState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _history.value = store.loadAll()
        }
    }

    /** Entry point from NfcAdapter.ReaderCallback — called on a binder thread. */
    fun onTagScanned(tag: Tag) {
        haptics.tagDetected()

        // An armed write claims the tag; otherwise it's a normal read.
        val armed = _writeState.value as? WriteState.Armed
        if (armed != null) {
            _writeState.value = WriteState.Writing
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    TagReader.writeAndVerify(tag, armed.bytes, AnycubicEncoder.START_PAGE)
                    _writeState.value = WriteState.Success("Tag written and verified.")
                    haptics.success()
                    // Show the freshly written tag as a normal scan result.
                    runCatching { readAndRecord(tag) }
                } catch (e: IOException) {
                    _writeState.value = WriteState.Error(
                        "Write failed (${e.message ?: "connection lost"}). " +
                            "The tag may be write-protected or moved too soon — arm and try again."
                    )
                    haptics.error()
                }
            }
            return
        }

        _uiState.value = ScanUiState.Reading
        _sendState.value = SendState.Idle
        viewModelScope.launch(Dispatchers.IO) {
            try {
                readAndRecord(tag)
                haptics.success()
            } catch (e: IOException) {
                _uiState.value = ScanUiState.Error(
                    "Tag read failed (${e.message ?: "connection lost"}). " +
                        "Hold the tag still against the back of the phone and try again."
                )
                haptics.error()
            }
        }
    }

    private fun readAndRecord(tag: Tag) {
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
    }

    /** Arms a write: the next tag presented will be overwritten with [info]. */
    fun armWrite(info: FilamentInfo) {
        _writeState.value = WriteState.Armed(AnycubicEncoder.encode(info))
    }

    /** Cancels an armed write or dismisses a write result banner. */
    fun resetWrite() {
        _writeState.value = WriteState.Idle
    }

    fun showRecord(record: ScanRecord) {
        _uiState.value = ScanUiState.Result(record)
        _sendState.value = SendState.Idle
    }

    /** Returns Main Screen to the idle "hold a tag / enter new spool" state. */
    fun clearResult() {
        _uiState.value = ScanUiState.Idle
        _sendState.value = SendState.Idle
    }

    /** Clears any stale Spoolman send result — call whenever a fresh manual-entry form opens. */
    fun resetSend() {
        _sendState.value = SendState.Idle
    }

    fun setSpoolmanUrl(url: String) {
        settings.spoolmanUrl = url
        _spoolmanUrl.value = settings.spoolmanUrl
    }

    fun setShowRawDump(enabled: Boolean) {
        settings.showRawDump = enabled
        _showRawDump.value = enabled
    }

    fun setShowHistory(enabled: Boolean) {
        settings.showHistory = enabled
        _showHistory.value = enabled
    }

    /** Creates (or finds) the spool in Spoolman for this scan's decoded filament. */
    fun sendToSpoolman(record: ScanRecord) {
        val filament = record.filament ?: run {
            _sendState.value = SendState.Error("Nothing to send — this scan didn't decode.")
            return
        }
        sendFilamentToSpoolman(filament, record.uidHex)
    }

    /**
     * Sends a manually-entered filament (e.g. from an OCR-read printed label,
     * which has no NFC UID to dedupe on) to Spoolman under a synthetic UID.
     */
    fun sendManualEntryToSpoolman(info: FilamentInfo) {
        sendFilamentToSpoolman(info, "manual-${System.currentTimeMillis()}")
    }

    private fun sendFilamentToSpoolman(filament: FilamentInfo, dedupeUid: String) {
        if (_spoolmanUrl.value.isBlank()) {
            _sendState.value = SendState.Error("Set your Spoolman URL first (⚙ in the top bar).")
            return
        }
        _sendState.value = SendState.Sending
        viewModelScope.launch(Dispatchers.IO) {
            _sendState.value = try {
                when (val result = SpoolmanClient(_spoolmanUrl.value)
                    .importTag(filament, dedupeUid)) {
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

    /**
     * Looks up color hex, printing temps, diameter, and length from the Open
     * Filament Database once brand + material + color name are known, so the
     * user isn't forced to photograph every remaining field. Matches on the
     * color's name (e.g. "White"), not an RGB guess — label text gives color
     * words, not hex codes, and name matching is exact where hex-distance
     * matching against an OCR guess would only ever be approximate. Any miss
     * (no match, or unreachable) leaves the caller's fields untouched — they
     * fall back to photo capture.
     */
    fun lookupFilamentDetails(brand: String, material: String, colorName: String) {
        _lookupState.value = LookupState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _lookupState.value = try {
                val result = OpenFilamentDatabaseClient.lookup(brand, material, colorName)
                if (result != null) {
                    LookupState.Found(result)
                } else {
                    LookupState.NotFound(
                        "No match in the Open Filament Database — capture the rest from photos.",
                    )
                }
            } catch (e: Exception) {
                LookupState.NotFound(
                    "Couldn't reach the Open Filament Database — capture the rest from photos.",
                )
            }
        }
    }

    fun resetLookup() {
        _lookupState.value = LookupState.Idle
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
