package com.bsbagley.spooler.tag

import kotlin.math.roundToInt

/**
 * Inverse of [AnycubicDecoder]: renders a [FilamentInfo] into the Anycubic ACE
 * byte layout as user-memory pages [START_PAGE]..31 (112 bytes), ready to be
 * written with Type 2 WRITE commands.
 *
 * Pages the decoder doesn't model (23, 25–28 parameter pairs and the page-31
 * spool weight) are filled with the values observed on a genuine Anycubic
 * "PLA High Speed" tag so the ACE box sees a complete, familiar record.
 * Pages 0–3 (UID / lock bits / capability container) are intentionally NOT
 * part of the output — callers must never overwrite them.
 */
object AnycubicEncoder {

    const val START_PAGE = 4
    private const val END_PAGE = 31
    private const val ASCII_FIELD_PAGES = 4 // SKU/brand/material are 4 pages (16 bytes) each

    fun encode(info: FilamentInfo): ByteArray {
        val bytes = ByteArray((END_PAGE - START_PAGE + 1) * 4)

        fun at(page: Int) = (page - START_PAGE) * 4

        fun put(page: Int, vararg values: Int) {
            values.forEachIndexed { i, v -> bytes[at(page) + i] = v.toByte() }
        }

        fun putAscii(page: Int, text: String) {
            val encoded = text.encodeToByteArray()
            require(encoded.size <= ASCII_FIELD_PAGES * 4) {
                "'$text' is longer than ${ASCII_FIELD_PAGES * 4} bytes"
            }
            encoded.forEachIndexed { i, b -> bytes[at(page) + i] = b }
        }

        fun putI16(page: Int, byteOffset: Int, value: Int) {
            require(value in 0..0xFFFF) { "Value $value out of int16 range" }
            bytes[at(page) + byteOffset] = (value and 0xFF).toByte()
            bytes[at(page) + byteOffset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        require(info.colorHex.matches(Regex("[0-9A-Fa-f]{6}"))) {
            "colorHex must be 6 hex digits (RRGGBB), got '${info.colorHex}'"
        }
        val red = info.colorHex.substring(0, 2).toInt(16)
        val green = info.colorHex.substring(2, 4).toInt(16)
        val blue = info.colorHex.substring(4, 6).toInt(16)
        val alpha = info.colorAbgr.firstOrNull() ?: 0xFF

        put(4, 0x7B, 0x00, 0x65, 0x00)                    // header magic
        putAscii(5, info.sku)                              // pages 5-8
        putAscii(10, info.brandCode.ifEmpty { "AC" })      // pages 10-13
        putAscii(15, info.material)                        // pages 15-18
        put(20, alpha, blue, green, red)                   // color, ABGR order

        // Aux parameter pairs observed on a real tag (exact meaning per
        // ACE-RFID is undocumented; plausibly dryer temp/time and speeds).
        putI16(23, 0, 50); putI16(23, 2, 150)
        putI16(25, 0, 150); putI16(25, 2, 300)
        putI16(26, 0, 210); putI16(26, 2, 230)
        putI16(27, 0, 300); putI16(27, 2, 600)
        putI16(28, 0, 230); putI16(28, 2, 260)

        putI16(24, 0, info.extruderMinC ?: 190)            // extruder °C
        putI16(24, 2, info.extruderMaxC ?: 230)
        putI16(29, 0, info.bedMinC ?: 50)                  // bed °C
        putI16(29, 2, info.bedMaxC ?: 60)
        putI16(30, 0, ((info.diameterMm ?: 1.75) * 100).roundToInt()) // hundredths of mm
        putI16(30, 2, info.lengthMeters ?: 330)
        putI16(31, 0, 1000)                                // spool weight g (observed)

        return bytes
    }
}
