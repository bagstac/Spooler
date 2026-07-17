package com.bsbagley.spooler.ocr

import kotlin.math.PI
import kotlin.math.roundToInt

/** Best-effort fields extracted from one photo's OCR text. All nullable — absence just means "not found". */
data class ParsedFields(
    val brand: String? = null,
    val sku: String? = null,
    val material: String? = null,
    val colorHex: String? = null,
    val colorName: String? = null,
    val extruderMinC: Int? = null,
    val extruderMaxC: Int? = null,
    val bedMinC: Int? = null,
    val bedMaxC: Int? = null,
    val diameterMm: Double? = null,
    val lengthMeters: Int? = null,
)

/** One capturable field (or field group) in the filament form, targeted by its own photo. */
enum class FilamentField(val label: String, val instructions: String) {
    BRAND("Brand", "Point the camera at the brand name or logo"),
    SKU("SKU", "Point the camera at the SKU or item number"),
    MATERIAL("Material", "Point the camera at the material name (PLA, PETG, ABS…)"),
    COLOR("Color hex", "Point the camera at a printed hex color code, if there is one"),
    COLOR_NAME("Color name", "Point the camera at the color name (e.g. \"White\")"),
    EXTRUDER_TEMP("Extruder temp", "Point the camera at the nozzle/extruder temperature"),
    BED_TEMP("Bed temp", "Point the camera at the bed/plate temperature"),
    DIAMETER("Diameter", "Point the camera at the filament diameter (e.g. 1.75mm)"),
    LENGTH("Length / weight", "Point the camera at the net weight or length"),
}

/**
 * Regex/keyword extraction of common filament-label fields from OCR text.
 * Printed labels vary wildly across brands, so this is deliberately
 * best-effort — every field it produces is still shown in an editable form,
 * never written or sent without the user seeing it first.
 */
object LabelFieldParser {

    // Common filament brands; not exhaustive — anything not matched here still
    // has a manual-entry fallback in the capture flow. Longer/more specific
    // names first (e.g. "Bambu Lab" before a hypothetical bare "Bambu").
    private val BRANDS = listOf(
        "Anycubic", "Bambu Lab", "Prusament", "Polymaker", "eSUN", "SUNLU",
        "Overture", "Hatchbox", "Inland", "Sainsmart", "Fillamentum", "ColorFabb",
        "Verbatim", "Fiberlogy", "Extrudr", "Kingroon", "Devil Design", "Geeetech",
        "Creality", "Elegoo", "Voxelab", "Polylite", "Duramic",
    )

    // Longest/most specific tokens first so "PLA+" and "PLA-CF" win over plain "PLA".
    private val MATERIALS = listOf(
        "PLA-CF", "PLA+", "PLA", "PETG", "ABS", "ASA", "TPU", "TPE",
        "NYLON", "PA-CF", "PA", "PC", "HIPS", "PVA", "SILK",
    )

    private val COLOR_NAMES = linkedMapOf(
        "black" to "000000", "white" to "FFFFFF", "red" to "FF0000", "green" to "008000",
        "blue" to "0000FF", "yellow" to "FFFF00", "orange" to "FFA500", "purple" to "800080",
        "pink" to "FFC0CB", "grey" to "808080", "gray" to "808080", "silver" to "C0C0C0",
        "gold" to "FFD700", "brown" to "A52A2A", "transparent" to "FFFFFF", "clear" to "FFFFFF",
        "natural" to "F5F5DC", "beige" to "F5F5DC", "cyan" to "00FFFF", "magenta" to "FF00FF",
        "lime" to "00FF00", "navy" to "000080", "teal" to "008080", "maroon" to "800000",
        "olive" to "808000",
    )

    /** PLA-ish default, matching SpoolmanClient's estimate — used only to back out a length from a printed weight. */
    private const val DEFAULT_DENSITY_G_CM3 = 1.24

    fun parse(text: String): ParsedFields {
        val diameter = findDiameter(text)
        val extruderRange = findTempRange(text, EXTRUDER_KEYWORDS, ordinal = 0)
        val bedRange = findTempRange(text, BED_KEYWORDS, ordinal = 1)
        return ParsedFields(
            brand = findBrand(text),
            sku = findSku(text),
            material = findMaterial(text),
            colorHex = findHexColor(text) ?: findNamedColorHex(text),
            colorName = findColorName(text),
            extruderMinC = extruderRange?.first,
            extruderMaxC = extruderRange?.second,
            bedMinC = bedRange?.first,
            bedMaxC = bedRange?.second,
            diameterMm = diameter,
            lengthMeters = findLengthMeters(text) ?: findWeightGrams(text)?.let {
                estimateLengthMeters(it, diameter ?: 1.75)
            },
        )
    }

    private fun findBrand(text: String): String? =
        BRANDS.firstOrNull { brand ->
            Regex("(?<![A-Za-z0-9])${Regex.escape(brand)}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
        }

    private fun findMaterial(text: String): String? {
        val upper = text.uppercase()
        // \b doesn't work for tokens ending in a symbol (e.g. "PLA+" — '+' isn't
        // a word char, so \b never lands after it); use alnum lookaround instead.
        return MATERIALS.firstOrNull { token ->
            Regex("(?<![A-Z0-9])${Regex.escape(token)}(?![A-Z0-9])").containsMatchIn(upper)
        }
    }

    private fun findHexColor(text: String): String? {
        // Require a leading '#' or a "color:" style label — a bare 6-hex-digit
        // run is too likely to be a SKU/batch number to trust blindly.
        Regex("#([0-9A-Fa-f]{6})\\b").find(text)?.let { return it.groupValues[1].uppercase() }
        Regex("(?i)colou?r.{0,15}?([0-9A-Fa-f]{6})\\b").find(text)?.let { return it.groupValues[1].uppercase() }
        return null
    }

    private fun findNamedColorHex(text: String): String? {
        val lower = text.lowercase()
        return COLOR_NAMES.entries.firstOrNull { (name, _) ->
            Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(lower)
        }?.value
    }

    private fun findColorName(text: String): String? {
        val lower = text.lowercase()
        return COLOR_NAMES.keys.firstOrNull { name ->
            Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(lower)
        }?.replaceFirstChar { it.uppercase() }
    }

    private fun findDiameter(text: String): Double? =
        Regex("""(\d\.\d{1,2})\s*mm""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun findLengthMeters(text: String): Int? =
        Regex("""(\d{2,4})\s*m\b(?!m)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun findWeightGrams(text: String): Int? {
        Regex("""(\d+(?:\.\d+)?)\s*kg""", RegexOption.IGNORE_CASE).find(text)?.let {
            return (it.groupValues[1].toDouble() * 1000).roundToInt()
        }
        Regex("""(\d{3,5})\s*g\b""", RegexOption.IGNORE_CASE).find(text)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        return null
    }

    private fun estimateLengthMeters(weightGrams: Int, diameterMm: Double): Int {
        val radiusCm = diameterMm / 2.0 / 10.0
        val lengthCm = weightGrams / (PI * radiusCm * radiusCm * DEFAULT_DENSITY_G_CM3)
        return (lengthCm / 100.0).roundToInt()
    }

    private val EXTRUDER_KEYWORDS = listOf("nozzle", "extruder", "print temp", "printing temp", "hotend")
    private val BED_KEYWORDS = listOf("bed", "plate", "platform", "build surface")

    /**
     * Finds a "NN-NN" (or "NN~NN" / "NN to NN") degree range near one of
     * [keywords]. If no keyword-anchored match exists, falls back to the
     * [ordinal]-th bare temperature range found anywhere in the text (labels
     * conventionally print nozzle temp before bed temp).
     */
    private fun findTempRange(text: String, keywords: List<String>, ordinal: Int): Pair<Int, Int>? {
        for (keyword in keywords) {
            val match = Regex(
                "(?i)$keyword.{0,20}?(\\d{2,3})\\s*[-–~]\\s*(\\d{2,3})",
            ).find(text)
            if (match != null) {
                return match.groupValues[1].toInt() to match.groupValues[2].toInt()
            }
        }
        val bareRanges = Regex("""(\d{2,3})\s*[-–~]\s*(\d{2,3})\s*°?C""", RegexOption.IGNORE_CASE)
            .findAll(text).toList()
        return bareRanges.getOrNull(ordinal)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    }

    private fun findSku(text: String): String? {
        Regex("""(?i)(?:sku|p/n|item\s*(?:no\.?|#)?)\s*[:#]?\s*([A-Z0-9][A-Z0-9\-]{3,})""")
            .find(text)?.let { return it.groupValues[1].uppercase() }
        return null
    }
}
