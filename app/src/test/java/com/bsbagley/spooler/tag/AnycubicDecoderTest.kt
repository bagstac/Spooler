package com.bsbagley.spooler.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AnycubicDecoderTest {

    /** Builds a full 45-page dump (pages 0..44) with a known Anycubic payload. */
    private fun syntheticDump(): ByteArray {
        val bytes = ByteArray(45 * 4)
        fun page(page: Int, vararg values: Int) {
            values.forEachIndexed { i, v -> bytes[page * 4 + i] = v.toByte() }
        }
        fun ascii(startPage: Int, text: String) {
            text.forEachIndexed { i, ch -> bytes[startPage * 4 + i] = ch.code.toByte() }
        }

        page(4, 0x7B, 0x00, 0x65, 0x00)          // header
        ascii(5, "AHPLLB-103")                    // SKU (pages 5-8)
        ascii(10, "AC")                           // brand (pages 10-13)
        ascii(15, "PLA")                          // material (pages 15-18)
        page(20, 0xFF, 0x33, 0x66, 0xE6)          // color A B G R -> #E66633
        page(24, 0xBE, 0x00, 0xE6, 0x00)          // extruder 190-230
        page(29, 0x32, 0x00, 0x3C, 0x00)          // bed 50-60
        page(30, 0xAF, 0x00, 0x4A, 0x01)          // diameter 175 (1.75mm), length 330m
        return bytes
    }

    @Test
    fun decodesFullDumpStartingAtPage0() {
        val info = AnycubicDecoder.decode(syntheticDump())

        assertEquals("AHPLLB-103", info.sku)
        assertEquals("AC", info.brandCode)
        assertEquals("Anycubic", info.brand)
        assertEquals("PLA", info.material)
        assertEquals("E66633", info.colorHex)
        assertEquals(listOf(0xFF, 0x33, 0x66, 0xE6), info.colorAbgr)
        assertEquals(190, info.extruderMinC)
        assertEquals(230, info.extruderMaxC)
        assertEquals(50, info.bedMinC)
        assertEquals(60, info.bedMaxC)
        assertEquals(1.75, info.diameterMm!!, 0.0001)
        assertEquals(330, info.lengthMeters)
    }

    @Test
    fun decodesUserMemoryDumpStartingAtPage4() {
        // Same payload but sliced so the 0x7B header is the first byte.
        val info = AnycubicDecoder.decode(syntheticDump().copyOfRange(16, 45 * 4))
        assertEquals("AHPLLB-103", info.sku)
        assertEquals("PLA", info.material)
    }

    @Test
    fun zeroedOptionalFieldsDecodeAsNull() {
        val bytes = syntheticDump()
        // Zero out extruder temps (page 24) and diameter/length (page 30).
        for (i in 24 * 4 until 25 * 4) bytes[i] = 0
        for (i in 30 * 4 until 31 * 4) bytes[i] = 0

        val info = AnycubicDecoder.decode(bytes)
        assertNull(info.extruderMinC)
        assertNull(info.extruderMaxC)
        assertNull(info.diameterMm)
        assertNull(info.lengthMeters)
    }

    @Test
    fun rejectsDumpWithoutHeader() {
        assertThrows(AnycubicDecoder.DecodeException::class.java) {
            AnycubicDecoder.decode(ByteArray(45 * 4)) // all zeros
        }
    }

    @Test
    fun rejectsTruncatedDump() {
        assertThrows(AnycubicDecoder.DecodeException::class.java) {
            AnycubicDecoder.decode(syntheticDump().copyOfRange(0, 20 * 4)) // ends before page 30
        }
    }
}
