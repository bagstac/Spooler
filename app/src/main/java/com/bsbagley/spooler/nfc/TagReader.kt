package com.bsbagley.spooler.nfc

import android.nfc.Tag
import android.nfc.tech.NfcA
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Reads raw memory pages from an NFC Forum Type 2 tag (NTAG21x and clones)
 * using the READ (0x30) command, which returns 4 pages (16 bytes) per call.
 *
 * Anycubic filament tags are Type-2-compatible clone chips (Shanghai Feiju,
 * SAK 0x00, no NDEF), so this raw path is the only way to read them — NDEF
 * APIs and Web NFC cannot. Kept as explicit low-level commands so
 * WRITE (0xA2, page, 4 bytes) can slot in alongside later.
 */
object TagReader {

    private const val CMD_READ: Byte = 0x30
    private const val PAGES_PER_READ = 4

    /** Pages to attempt. NTAG213 = 45 pages (0..44); clones may NAK sooner. */
    private const val LAST_PAGE = 44

    /** The Anycubic payload ends at page 30; a read is usable if we got that far. */
    private const val MIN_USEFUL_PAGES = 31

    /**
     * Reads pages 0..[LAST_PAGE], stopping gracefully at the first NAK or
     * comms failure so a partial dump is still returned.
     *
     * Must be called off the main thread (transceive blocks).
     */
    fun read(tag: Tag): RawTagRead {
        val nfcA = NfcA.get(tag)
            ?: throw IOException("Tag does not support NfcA (techs: ${tag.techList.joinToString()})")

        nfcA.connect()
        try {
            nfcA.timeout = 1_000
            val out = ByteArrayOutputStream()
            var page = 0
            while (page <= LAST_PAGE) {
                val response = try {
                    nfcA.transceive(byteArrayOf(CMD_READ, page.toByte()))
                } catch (e: IOException) {
                    break // NAK on an out-of-range page, or tag left the field
                }
                if (response.size < 16) break // explicit NAK (single status byte)
                out.write(response, 0, 16)
                page += PAGES_PER_READ
            }

            val bytes = out.toByteArray()
            val pagesRead = bytes.size / 4
            if (pagesRead == 0) {
                throw IOException("Tag did not answer any READ command")
            }
            return RawTagRead(
                uidHex = tag.id.toHex(":"),
                atqaHex = nfcA.atqa.toHex(""),
                sak = nfcA.sak.toInt() and 0xFF,
                techList = tag.techList.toList(),
                bytes = bytes,
                pagesRead = pagesRead,
                complete = pagesRead >= MIN_USEFUL_PAGES,
            )
        } finally {
            try {
                nfcA.close()
            } catch (_: IOException) {
            }
        }
    }
}

/** One raw scan: transport-level identifiers plus the dumped memory pages. */
class RawTagRead(
    val uidHex: String,
    val atqaHex: String,
    val sak: Int,
    val techList: List<String>,
    val bytes: ByteArray,
    val pagesRead: Int,
    val complete: Boolean,
)

fun ByteArray.toHex(separator: String = ""): String =
    joinToString(separator) { "%02X".format(it) }
