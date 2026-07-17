package com.bsbagley.spooler.tag

import kotlinx.serialization.Serializable

/**
 * Decoder for Anycubic ACE filament tag dumps, per the community
 * reverse-engineering (DnG-Crafts/ACE-RFID). Port of `parseAnycubicBytes`
 * from the SpoolIntegrater PWA. Each Type 2 page = 4 bytes:
 *
 *   page 4      header   : 7B 00 65 00
 *   page 5-8    SKU      : ASCII (e.g. "AHPLLB-103")
 *   page 10-13  brand    : ASCII (e.g. "AC")
 *   page 15-18  material : ASCII (e.g. "PLA")
 *   page 20     color    : A B G R
 *   page 24     extruder : int16 LE min, int16 LE max (degC)
 *   page 29     hotbed   : int16 LE min, int16 LE max (degC)
 *   page 30     diameter : int16 LE (bytes 0-1), length int16 LE meters (bytes 2-3)
 */
object AnycubicDecoder {

    private val BRANDS = mapOf("AC" to "Anycubic")

    class DecodeException(message: String) : Exception(message)

    /**
     * @param bytes Dump starting at page 0 (full dump) or page 4
     *              (user memory only) — auto-detected via the 0x7B header.
     */
    fun decode(bytes: ByteArray): FilamentInfo {
        fun b(i: Int) = bytes[i].toInt() and 0xFF

        // Header magic 0x7B lives at page 4: byte 16 in a full dump, byte 0
        // in a user-memory-only dump.
        val base = when {
            bytes.size > 16 && b(16) == 0x7B -> 16
            bytes.isNotEmpty() && b(0) == 0x7B -> 0
            else -> throw DecodeException("Not an Anycubic tag (no 0x7B header at page 4).")
        }

        fun at(page: Int) = base + (page - 4) * 4

        val needed = at(30) + 4
        if (bytes.size < needed) {
            throw DecodeException(
                "Dump too short: need $needed bytes (through page 30), got ${bytes.size}."
            )
        }

        fun ascii(page: Int, pages: Int): String {
            val sb = StringBuilder()
            for (i in at(page) until at(page) + pages * 4) {
                val v = b(i)
                // Stop at NUL or anything outside printable ASCII — tag bytes
                // are untrusted input and these strings flow into the UI,
                // shared JSON, and Spoolman names/comments.
                if (v < 0x20 || v > 0x7E) break
                sb.append(v.toChar())
            }
            return sb.toString().trim()
        }

        fun i16(addr: Int) = b(addr) or (b(addr + 1) shl 8)

        val sku = ascii(5, 4)
        val brandCode = ascii(10, 4)
        val material = ascii(15, 4)

        val c = at(20)
        val alpha = b(c)
        val blue = b(c + 1)
        val green = b(c + 2)
        val red = b(c + 3)

        val extMin = i16(at(24))
        val extMax = i16(at(24) + 2)
        val bedMin = i16(at(29))
        val bedMax = i16(at(29) + 2)
        val diameterRaw = i16(at(30))
        val lengthM = i16(at(30) + 2)

        return FilamentInfo(
            sku = sku,
            brandCode = brandCode,
            brand = BRANDS[brandCode] ?: brandCode.ifEmpty { "Anycubic" },
            material = material,
            colorHex = "%02X%02X%02X".format(red, green, blue),
            colorAbgr = listOf(alpha, blue, green, red),
            extruderMinC = extMin.takeIf { it != 0 },
            extruderMaxC = extMax.takeIf { it != 0 },
            bedMinC = bedMin.takeIf { it != 0 },
            bedMaxC = bedMax.takeIf { it != 0 },
            diameterMm = when {
                diameterRaw >= 100 -> diameterRaw / 100.0 // stored in hundredths of mm (175 -> 1.75)
                diameterRaw > 0 -> diameterRaw.toDouble()
                else -> null
            },
            lengthMeters = lengthM.takeIf { it != 0 },
        )
    }
}

/** Decoded, human-meaningful filament data; serializable for copy/share as JSON. */
@Serializable
data class FilamentInfo(
    val sku: String,
    val brandCode: String,
    val brand: String,
    val material: String,
    val colorHex: String,
    val colorAbgr: List<Int>,
    val extruderMinC: Int? = null,
    val extruderMaxC: Int? = null,
    val bedMinC: Int? = null,
    val bedMaxC: Int? = null,
    val diameterMm: Double? = null,
    val lengthMeters: Int? = null,
)
