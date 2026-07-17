package com.bsbagley.spooler.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LabelFieldParserTest {

    @Test
    fun parsesKnownBrandCaseInsensitively() {
        assertEquals("eSUN", LabelFieldParser.parse("esun PLA+ 1kg").brand)
        assertEquals("Bambu Lab", LabelFieldParser.parse("Bambu Lab PETG HF").brand)
        assertNull(LabelFieldParser.parse("Some Unknown Brand PLA").brand)
    }

    @Test
    fun parsesMaterialPreferringMoreSpecificToken() {
        assertEquals("PLA", LabelFieldParser.parse("Filament PLA 1.75mm").material)
        assertEquals("PLA+", LabelFieldParser.parse("PLA+ Premium Filament").material)
        assertEquals("PETG", LabelFieldParser.parse("PETG Carbon").material)
        assertNull(LabelFieldParser.parse("No material mentioned here").material)
    }

    @Test
    fun parsesExplicitHexColorWithHash() {
        val f = LabelFieldParser.parse("Color: #1E90FF Blue Filament")
        assertEquals("1E90FF", f.colorHex)
    }

    @Test
    fun parsesColorKeywordFollowedByHexWithoutHash() {
        val f = LabelFieldParser.parse("Colour code 1E90FF")
        assertEquals("1E90FF", f.colorHex)
    }

    @Test
    fun ignoresBareSixDigitHexWithNoColorContext() {
        // A stray 6-hex-like token (e.g. a batch code) shouldn't be mistaken for a color.
        val f = LabelFieldParser.parse("Batch AB1234 Lot 2026")
        assertNull(f.colorHex)
    }

    @Test
    fun parsesColorNameSeparatelyFromHex() {
        val f = LabelFieldParser.parse("Anycubic PLA White 1.75mm")
        assertEquals("White", f.colorName)
        assertEquals("FFFFFF", f.colorHex)
        assertNull(LabelFieldParser.parse("No color word here").colorName)
    }

    @Test
    fun parsesNamedColorWithWordBoundary() {
        assertEquals("000000", LabelFieldParser.parse("PLA Black 1kg").colorHex)
        // "Blackout" shouldn't match "black" as a substring.
        assertNull(LabelFieldParser.parse("Blackout Industries PLA").colorHex)
    }

    @Test
    fun parsesDiameter() {
        assertEquals(1.75, LabelFieldParser.parse("Diameter 1.75mm").diameterMm!!, 0.001)
        assertEquals(2.85, LabelFieldParser.parse("2.85mm filament").diameterMm!!, 0.001)
    }

    @Test
    fun parsesLengthDirectlyWhenPrinted() {
        assertEquals(330, LabelFieldParser.parse("Length: 330m").lengthMeters)
    }

    @Test
    fun estimatesLengthFromPrintedWeight() {
        // 1kg of 1.75mm PLA-density filament is roughly 330m.
        val f = LabelFieldParser.parse("Net Weight: 1kg  Diameter 1.75mm")
        assertTrue("expected ~330m, got ${f.lengthMeters}", abs(f.lengthMeters!! - 330) <= 15)
    }

    @Test
    fun parsesTempRangeNearKeyword() {
        val f = LabelFieldParser.parse("Nozzle Temp: 200-220C  Bed Temp: 50-60C")
        assertEquals(200, f.extruderMinC)
        assertEquals(220, f.extruderMaxC)
        assertEquals(50, f.bedMinC)
        assertEquals(60, f.bedMaxC)
    }

    @Test
    fun fallsBackToPositionalTempRangesWithoutKeywords() {
        val f = LabelFieldParser.parse("Print info: 190-230°C then 50-60°C")
        assertEquals(190, f.extruderMinC)
        assertEquals(230, f.extruderMaxC)
        assertEquals(50, f.bedMinC)
        assertEquals(60, f.bedMaxC)
    }

    @Test
    fun parsesSkuAfterLabel() {
        assertEquals("AHPLLB-103", LabelFieldParser.parse("SKU: AHPLLB-103").sku)
        assertEquals("XY-2024", LabelFieldParser.parse("P/N XY-2024").sku)
    }

}
