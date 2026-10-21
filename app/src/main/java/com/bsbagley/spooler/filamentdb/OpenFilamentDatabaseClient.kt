package com.bsbagley.spooler.filamentdb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Read-only client for the Open Filament Database
 * (https://api.openfilamentdatabase.org) — a community-maintained catalog of
 * filament brands, materials, and color variants published as static JSON
 * (see https://github.com/OpenFilamentCollective/open-filament-database).
 *
 * Used to fill in printing temperatures, diameter, and spool length once
 * brand + material + color name are already known, so the user doesn't have
 * to photograph every remaining field. Every miss — network error, no
 * matching brand/material/color — returns null; the caller then falls back
 * to the existing per-field photo capture.
 *
 * The API addresses entities by path (brands/{brand}/materials/{material}/
 * filaments/{filament}/variants/{variant}.json), and each index response
 * carries the exact relative `path` to descend further — so this client
 * never guesses slugification rules, it just follows the paths the API hands
 * back and matches on human-readable `name`/`material` fields. Color is
 * matched by the variant's own `name` (e.g. "White", "Black") rather than by
 * comparing hex values — labels print color words, not hex codes, and
 * matching names is exact where RGB-distance matching against an
 * OCR-guessed hex would only ever be approximate.
 */
object OpenFilamentDatabaseClient {

    class LookupResult(
        val colorHex: String?,
        val extruderMinC: Int?,
        val extruderMaxC: Int?,
        val bedMinC: Int?,
        val bedMaxC: Int?,
        val diameterMm: Double?,
        val lengthMeters: Int?,
        /** OFD filament product-line name, e.g. "PLA Matte" — used for Spoolman's filament name field. */
        val filamentName: String?,
    )

    private const val BASE = "https://api.openfilamentdatabase.org/api/v1/brands"

    /** PLA-ish default, matching the rest of the app — used only to back out a length from a listed spool weight. */
    private const val DEFAULT_DENSITY_G_CM3 = 1.24

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** A color variant plus which filament (product line) it came from, for building its detail URL. */
    private class VariantCandidate(val filament: JsonObject, val filamentBase: String, val variant: JsonObject)

    /**
     * Blocking — makes several sequential HTTP calls (brand -> material ->
     * every filament under it -> the matched variant's own file).
     * Call from Dispatchers.IO. Returns null on any miss.
     */
    fun lookup(brand: String, material: String, colorName: String): LookupResult? {
        val brandsIndex = getJson("$BASE/index.json") ?: return null
        val brandEntry = findByName(brandsIndex["brands"]?.jsonArray, brand) ?: return null
        val brandPath = brandEntry["path"]?.jsonPrimitive?.content ?: return null
        val brandBase = resolve("$BASE/", brandPath).removeSuffix("index.json")

        val brandDetail = getJson(resolve(brandBase, "index.json")) ?: return null
        val materialEntry = findMaterialByName(brandDetail["materials"]?.jsonArray, material) ?: return null
        val materialPath = materialEntry["path"]?.jsonPrimitive?.content ?: return null
        val materialBase = resolve(brandBase, materialPath).removeSuffix("index.json")

        val materialDetail = getJson(resolve(materialBase, "index.json")) ?: return null
        val filamentPaths = materialDetail["filaments"]?.jsonArray
            ?.mapNotNull { it.jsonObject["path"]?.jsonPrimitive?.content }
            ?: return null

        val candidates = mutableListOf<VariantCandidate>()
        for (filamentPath in filamentPaths) {
            val filamentUrl = resolve(materialBase, filamentPath)
            val filamentBase = filamentUrl.removeSuffix("index.json")
            val filamentDetail = getJson(filamentUrl) ?: continue
            for (variantEl in filamentDetail["variants"]?.jsonArray.orEmpty()) {
                candidates.add(VariantCandidate(filamentDetail, filamentBase, variantEl.jsonObject))
            }
        }
        if (candidates.isEmpty()) return null

        // findByName matches on a "name" field, which color variants carry too
        // (e.g. {"name": "White", "color_hex": "#F1F0ED", ...}) — same matcher,
        // no need for a separate color-specific lookup function.
        val matchedVariant = findByName(JsonArray(candidates.map { it.variant }), colorName) ?: return null
        val matchedCandidate = candidates.first { it.variant === matchedVariant }

        val variantPath = matchedVariant["path"]?.jsonPrimitive?.content
        val size = variantPath?.let { getJson(resolve(matchedCandidate.filamentBase, it)) }
            ?.get("sizes")?.jsonArray?.firstOrNull()?.jsonObject

        val diameter = size?.get("diameter")?.jsonPrimitive?.doubleOrNull
        val weightGrams = size?.get("filament_weight")?.jsonPrimitive?.intOrNull
        val length = weightGrams?.let { estimateLengthMeters(it, diameter ?: 1.75) }
        val filament = matchedCandidate.filament

        return LookupResult(
            colorHex = matchedVariant["color_hex"]?.jsonPrimitive?.content,
            extruderMinC = filament["min_print_temperature"]?.jsonPrimitive?.intOrNull,
            extruderMaxC = filament["max_print_temperature"]?.jsonPrimitive?.intOrNull,
            bedMinC = filament["min_bed_temperature"]?.jsonPrimitive?.intOrNull,
            bedMaxC = filament["max_bed_temperature"]?.jsonPrimitive?.intOrNull,
            diameterMm = diameter,
            lengthMeters = length,
            filamentName = filament["name"]?.jsonPrimitive?.content,
        )
    }

    /**
     * All brand names in the database, alphabetized — for the manual-entry
     * form's Brand autocomplete. Blocking; call from Dispatchers.IO. Skips
     * brands with zero listed materials, since a lookup against those could
     * never succeed anyway. Returns an empty list on any network failure —
     * the Brand field stays a plain editable text field either way.
     */
    fun listBrandNames(): List<String> {
        val brandsIndex = getJson("$BASE/index.json") ?: return emptyList()
        return extractBrandNames(brandsIndex)
    }

    internal fun extractBrandNames(brandsIndex: JsonObject): List<String> =
        brandsIndex["brands"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.filter { (it["material_count"]?.jsonPrimitive?.intOrNull ?: 0) > 0 }
            ?.mapNotNull { it["name"]?.jsonPrimitive?.content }
            ?.sorted()
            ?: emptyList()

    /**
     * All material names offered by a specific brand — for the Spool Info
     * Material dropdown, scoped to whatever Brand the user selected.
     * Blocking; call from Dispatchers.IO. Returns empty on any miss.
     */
    fun listMaterialsForBrand(brandName: String): List<String> {
        val brandsIndex = getJson("$BASE/index.json") ?: return emptyList()
        val brandEntry = findByName(brandsIndex["brands"]?.jsonArray, brandName) ?: return emptyList()
        val brandPath = brandEntry["path"]?.jsonPrimitive?.content ?: return emptyList()
        val brandBase = resolve("$BASE/", brandPath).removeSuffix("index.json")
        val brandDetail = getJson(resolve(brandBase, "index.json")) ?: return emptyList()
        return brandDetail["materials"]?.jsonArray
            ?.mapNotNull { (it as? JsonObject)?.get("material")?.jsonPrimitive?.content }
            ?.sorted()
            ?: emptyList()
    }

    /**
     * All filament product-line names for a brand (e.g. "PLA Matte", "PLA
     * Basic", "PETG-CF") — the Spool Info Material dropdown prefers these
     * over base material names. Blocking; call from Dispatchers.IO.
     */
    fun listFilamentNamesForBrand(brandName: String): List<String> {
        val brandsIndex = getJson("$BASE/index.json") ?: return emptyList()
        val brandEntry = findByName(brandsIndex["brands"]?.jsonArray, brandName) ?: return emptyList()
        val brandPath = brandEntry["path"]?.jsonPrimitive?.content ?: return emptyList()
        val brandBase = resolve("$BASE/", brandPath).removeSuffix("index.json")
        val brandDetail = getJson(resolve(brandBase, "index.json")) ?: return emptyList()
        val materialPaths = brandDetail["materials"]?.jsonArray
            ?.mapNotNull { (it as? JsonObject)?.get("path")?.jsonPrimitive?.content }
            ?: return emptyList()

        val names = mutableListOf<String>()
        for (matPath in materialPaths) {
            val matUrl = resolve(brandBase, matPath)
            val matDetail = getJson(matUrl) ?: continue
            matDetail["filaments"]?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.content }
                ?.forEach { names.add(it) }
        }
        return names.sorted()
    }

    /**
     * Every distinct material name across all brands — for the Settings
     * material-type filter. Blocking; call from Dispatchers.IO. Iterates
     * every brand, so it's slow on first fetch — cache the result.
     */
    fun listAllMaterials(): List<String> {
        val brandsIndex = getJson("$BASE/index.json") ?: return emptyList()
        val brands = brandsIndex["brands"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.filter { (it["material_count"]?.jsonPrimitive?.intOrNull ?: 0) > 0 }
            ?: return emptyList()

        val all = mutableSetOf<String>()
        for (brand in brands) {
            val path = brand["path"]?.jsonPrimitive?.content ?: continue
            val brandBase = resolve("$BASE/", path).removeSuffix("index.json")
            val detail = getJson(resolve(brandBase, "index.json")) ?: continue
            detail["materials"]?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.get("material")?.jsonPrimitive?.content }
                ?.forEach { all.add(it) }
        }
        return all.sorted()
    }

    private fun getJson(url: String): JsonObject? {
        val text = try {
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                res.body?.string()
            }
        } catch (e: IOException) {
            null
        } ?: return null
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    // ---- Pure helpers (unit-tested independently of the network) ------------

    internal fun resolve(baseDirUrl: String, relativePath: String): String =
        baseDirUrl.trimEnd('/') + "/" + relativePath.trimStart('/')

    /** Exact case-insensitive match on "name" first, else a case-insensitive substring match either direction. */
    internal fun findByName(entries: JsonArray?, name: String): JsonObject? {
        val target = name.trim()
        val objs = entries?.mapNotNull { it as? JsonObject } ?: return null
        objs.firstOrNull { it["name"]?.jsonPrimitive?.content.equals(target, ignoreCase = true) }
            ?.let { return it }
        return objs.firstOrNull { entry ->
            val entryName = entry["name"]?.jsonPrimitive?.content ?: return@firstOrNull false
            entryName.contains(target, ignoreCase = true) || target.contains(entryName, ignoreCase = true)
        }
    }

    internal fun findMaterialByName(entries: JsonArray?, material: String): JsonObject? {
        val objs = entries?.mapNotNull { it as? JsonObject } ?: return null
        val normalized = normalizeMaterial(material)
        // Exact match on normalized form (e.g. "PLA+"/"PLA-CF" → "PLA")
        objs.firstOrNull { it["material"]?.jsonPrimitive?.content.equals(normalized, ignoreCase = true) }
            ?.let { return it }
        // Exact match on raw input
        objs.firstOrNull { it["material"]?.jsonPrimitive?.content.equals(material.trim(), ignoreCase = true) }
            ?.let { return it }
        // Substring fallback: "PLA Matte" contains "PLA", "Silk PLA" contains "PLA"
        return objs.firstOrNull { entry ->
            val entryMaterial = entry["material"]?.jsonPrimitive?.content ?: return@firstOrNull false
            entryMaterial in material.trim().split(' ', '+', '-').map { it.trim() }.filter { it.isNotBlank() }
                || material.trim().contains(entryMaterial, ignoreCase = true)
        }
    }

    /** "PLA+" / "PLA-CF" -> "PLA": specific formulations are filament product lines here, not material categories. */
    internal fun normalizeMaterial(material: String): String =
        material.trim().substringBefore('+').substringBefore('-').trim()

    internal fun estimateLengthMeters(weightGrams: Int, diameterMm: Double): Int {
        val radiusCm = diameterMm / 2.0 / 10.0
        val lengthCm = weightGrams / (PI * radiusCm * radiusCm * DEFAULT_DENSITY_G_CM3)
        return (lengthCm / 100.0).roundToInt()
    }
}
