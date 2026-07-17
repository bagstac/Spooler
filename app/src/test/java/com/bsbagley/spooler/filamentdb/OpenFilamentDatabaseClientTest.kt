package com.bsbagley.spooler.filamentdb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFilamentDatabaseClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun resolveJoinsBaseDirAndRelativePath() {
        assertEquals(
            "https://api.example.org/brands/anycubic/index.json",
            OpenFilamentDatabaseClient.resolve("https://api.example.org/brands/", "anycubic/index.json"),
        )
        // Trailing/leading slash variations shouldn't produce doubled or missing slashes.
        assertEquals(
            "https://api.example.org/brands/anycubic/index.json",
            OpenFilamentDatabaseClient.resolve("https://api.example.org/brands", "/anycubic/index.json"),
        )
    }

    @Test
    fun findByNameMatchesExactCaseInsensitively() {
        val entries = json.parseToJsonElement(
            """[{"name":"Anycubic","path":"anycubic/index.json"},{"name":"eSUN","path":"esun/index.json"}]""",
        ).jsonArray
        val match = OpenFilamentDatabaseClient.findByName(entries, "anycubic")
        assertEquals("anycubic/index.json", match?.get("path")?.jsonPrimitive?.content)
    }

    @Test
    fun findByNameFallsBackToContains() {
        val entries = json.parseToJsonElement(
            """[{"name":"Bambu Lab","path":"bambu_lab/index.json"}]""",
        ).jsonArray
        // User-entered "Bambu" should still find "Bambu Lab".
        val match = OpenFilamentDatabaseClient.findByName(entries, "Bambu")
        assertEquals("bambu_lab/index.json", match?.get("path")?.jsonPrimitive?.content)
    }

    @Test
    fun findByNameReturnsNullWhenNoneMatch() {
        val entries = json.parseToJsonElement("""[{"name":"Anycubic","path":"x"}]""").jsonArray
        assertNull(OpenFilamentDatabaseClient.findByName(entries, "Totally Different Brand"))
    }

    @Test
    fun findMaterialByNameNormalizesPlusAndDashVariants() {
        val entries = json.parseToJsonElement(
            """[{"material":"PLA","path":"materials/PLA/index.json"}]""",
        ).jsonArray
        assertEquals(
            "materials/PLA/index.json",
            OpenFilamentDatabaseClient.findMaterialByName(entries, "PLA+")?.get("path")?.jsonPrimitive?.content,
        )
        assertEquals(
            "materials/PLA/index.json",
            OpenFilamentDatabaseClient.findMaterialByName(entries, "PLA-CF")?.get("path")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun normalizeMaterialStripsFormulationSuffixes() {
        assertEquals("PLA", OpenFilamentDatabaseClient.normalizeMaterial("PLA+"))
        assertEquals("PLA", OpenFilamentDatabaseClient.normalizeMaterial("PLA-CF"))
        assertEquals("PETG", OpenFilamentDatabaseClient.normalizeMaterial("PETG"))
    }

    @Test
    fun findByNameMatchesColorVariantsByNameToo() {
        // Same matcher is reused for color variants, which also carry a "name" field.
        val variants = json.parseToJsonElement(
            """[{"name":"Black","color_hex":"#212721","path":"variants/black.json"},
                {"name":"White","color_hex":"#F1F0ED","path":"variants/white.json"}]""",
        ).jsonArray
        val match = OpenFilamentDatabaseClient.findByName(variants, "white")
        assertEquals("#F1F0ED", match?.get("color_hex")?.jsonPrimitive?.content)
    }

    @Test
    fun estimateLengthMetersMatchesKnownPlaSpool() {
        // 1kg of 1.75mm PLA-density filament is roughly 330m, same reference point used elsewhere in the app.
        val length = OpenFilamentDatabaseClient.estimateLengthMeters(1000, 1.75)
        assertTrue("expected ~330m, got $length", kotlin.math.abs(length - 330) <= 15)
    }
}
