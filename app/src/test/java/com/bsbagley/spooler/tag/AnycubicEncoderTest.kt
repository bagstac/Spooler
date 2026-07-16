package com.bsbagley.spooler.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnycubicEncoderTest {

    private val info = FilamentInfo(
        sku = "AHPETG-201",
        brandCode = "AC",
        brand = "Anycubic",
        material = "PETG",
        colorHex = "1E90FF",
        colorAbgr = listOf(0xFF, 0xFF, 0x90, 0x1E),
        extruderMinC = 220,
        extruderMaxC = 250,
        bedMinC = 70,
        bedMaxC = 80,
        diameterMm = 1.75,
        lengthMeters = 330,
    )

    @Test
    fun encodeDecodeRoundtripPreservesFields() {
        val decoded = AnycubicDecoder.decode(AnycubicEncoder.encode(info))

        assertEquals(info.sku, decoded.sku)
        assertEquals(info.brandCode, decoded.brandCode)
        assertEquals(info.brand, decoded.brand)
        assertEquals(info.material, decoded.material)
        assertEquals(info.colorHex, decoded.colorHex)
        assertEquals(info.colorAbgr, decoded.colorAbgr)
        assertEquals(info.extruderMinC, decoded.extruderMinC)
        assertEquals(info.extruderMaxC, decoded.extruderMaxC)
        assertEquals(info.bedMinC, decoded.bedMinC)
        assertEquals(info.bedMaxC, decoded.bedMaxC)
        assertEquals(info.diameterMm!!, decoded.diameterMm!!, 0.0001)
        assertEquals(info.lengthMeters, decoded.lengthMeters)
    }

    @Test
    fun outputIsWholePagesStartingAtHeader() {
        val bytes = AnycubicEncoder.encode(info)
        assertEquals(0, bytes.size % 4)
        assertEquals((31 - 4 + 1) * 4, bytes.size) // pages 4..31
        assertEquals(0x7B, bytes[0].toInt() and 0xFF) // header magic at page 4
    }

    @Test
    fun rejectsInvalidColorHex() {
        assertThrows(IllegalArgumentException::class.java) {
            AnycubicEncoder.encode(info.copy(colorHex = "red"))
        }
    }

    @Test
    fun rejectsOverlongSku() {
        assertThrows(IllegalArgumentException::class.java) {
            AnycubicEncoder.encode(info.copy(sku = "THIS-SKU-IS-WAY-TOO-LONG"))
        }
    }
}
