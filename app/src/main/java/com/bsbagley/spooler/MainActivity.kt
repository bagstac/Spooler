package com.bsbagley.spooler

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bsbagley.spooler.ui.MainScreen
import com.bsbagley.spooler.ui.theme.SpoolerTheme

enum class NfcStatus { UNSUPPORTED, DISABLED, READY }

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private val viewModel: ScanViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null
    private var nfcStatus by mutableStateOf(NfcStatus.READY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent {
            SpoolerTheme {
                MainScreen(
                    viewModel = viewModel,
                    nfcStatus = nfcStatus,
                    onOpenNfcSettings = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        nfcStatus = when {
            adapter == null -> NfcStatus.UNSUPPORTED
            !adapter.isEnabled -> NfcStatus.DISABLED
            else -> NfcStatus.READY
        }
        // SKIP_NDEF_CHECK is essential: Anycubic tags are raw Type 2 with no
        // NDEF, and without the flag Android tries (and fails) to parse them
        // before the app ever sees the tag.
        adapter?.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            Bundle().apply {
                // Faster tag-gone detection while the tag is against the phone.
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
            },
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    /** Called on a binder thread, never the main thread. */
    override fun onTagDiscovered(tag: Tag) {
        viewModel.onTagScanned(tag)
    }
}
