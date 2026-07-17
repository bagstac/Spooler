package com.bsbagley.spooler.spoolman

import com.bsbagley.spooler.tag.FilamentInfo
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Minimal Spoolman v1 REST client (port of the SpoolIntegrater PWA's
 * spoolman-client.js). Import flow: find-or-create vendor -> find-or-create
 * filament (matched on vendor + material + color) -> create spool.
 *
 * The tag UID is stored in the spool's lot_nr and used to avoid creating
 * duplicate spools when the same physical spool is scanned twice.
 *
 * All calls are blocking — invoke on Dispatchers.IO.
 */
class SpoolmanClient(baseUrl: String) {

    class SpoolmanException(message: String) : IOException(message)

    sealed interface ImportResult {
        data class Created(
            val spoolId: Int,
            val filamentName: String,
            val estimatedWeightG: Int?,
        ) : ImportResult

        data class AlreadyExists(val spoolId: Int) : ImportResult
    }

    private val apiBase = baseUrl.trim().trimEnd('/') + "/api/v1"
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Turns a decoded tag into a Spoolman spool, creating vendor/filament as needed. */
    fun importTag(info: FilamentInfo, tagUid: String): ImportResult {
        // Dedupe: same physical spool scanned before?
        findSpoolByLotNr(tagUid)?.let { return ImportResult.AlreadyExists(it) }

        val vendorId = findOrCreateVendor(info.brand.ifEmpty { "Anycubic" })
        val filament = findOrCreateFilament(vendorId, info)
        val filamentId = filament["id"]!!.jsonPrimitive.int
        val filamentName = filament["name"]?.jsonPrimitive?.content ?: info.material

        val estWeight = estimateWeightGrams(info)
        val spool = request("POST", "/spool", buildJsonObject {
            put("filament_id", filamentId)
            put("lot_nr", tagUid)
            put("comment", "SKU ${info.sku} · imported by Spooler")
            if (estWeight != null) put("initial_weight", estWeight)
        })!!.jsonObject

        return ImportResult.Created(
            spoolId = spool["id"]!!.jsonPrimitive.int,
            filamentName = filamentName,
            estimatedWeightG = estWeight,
        )
    }

    // ---- Vendors ------------------------------------------------------------

    private fun findOrCreateVendor(name: String): Int {
        val existing = request("GET", "/vendor")?.jsonArray?.firstOrNull {
            it.jsonObject["name"]?.jsonPrimitive?.content.equals(name, ignoreCase = true)
        }
        if (existing != null) return existing.jsonObject["id"]!!.jsonPrimitive.int

        return request("POST", "/vendor", buildJsonObject { put("name", name) })!!
            .jsonObject["id"]!!.jsonPrimitive.int
    }

    // ---- Filaments ----------------------------------------------------------

    private fun findOrCreateFilament(vendorId: Int, info: FilamentInfo): JsonObject {
        val existing = request("GET", "/filament")?.jsonArray?.firstOrNull { el ->
            val f = el.jsonObject
            f["vendor"]?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull == vendorId &&
                (f["material"]?.jsonPrimitive?.content ?: "")
                    .equals(info.material, ignoreCase = true) &&
                (f["color_hex"]?.jsonPrimitive?.content ?: "")
                    .equals(info.colorHex, ignoreCase = true)
        }
        if (existing != null) return existing.jsonObject

        return request("POST", "/filament", buildJsonObject {
            put("vendor_id", vendorId)
            put("name", info.material.ifEmpty { info.sku })
            if (info.material.isNotEmpty()) put("material", info.material)
            put("color_hex", info.colorHex)
            put("density", DEFAULT_DENSITY_G_CM3)
            put("diameter", info.diameterMm ?: 1.75)
            info.extruderMaxC?.let { put("settings_extruder_temp", it) }
            info.bedMaxC?.let { put("settings_bed_temp", it) }
        })!!.jsonObject
    }

    // ---- Spools -------------------------------------------------------------

    private fun findSpoolByLotNr(lotNr: String): Int? =
        request("GET", "/spool")?.jsonArray?.firstOrNull {
            it.jsonObject["lot_nr"]?.jsonPrimitive?.content == lotNr
        }?.jsonObject?.get("id")?.jsonPrimitive?.int

    // ---- Plumbing -----------------------------------------------------------

    private fun request(method: String, endpoint: String, body: JsonObject? = null) =
        try {
            val requestBody = body?.toString()
                ?.toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(apiBase + endpoint)
                .method(method, requestBody)
                .build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val detail = runCatching {
                        json.parseToJsonElement(text).jsonObject["message"]
                            ?.jsonPrimitive?.content
                    }.getOrNull() ?: text.take(200)
                    throw SpoolmanException("$method $endpoint → HTTP ${res.code}: $detail")
                }
                if (text.isBlank()) null else json.parseToJsonElement(text)
            }
        } catch (e: SpoolmanException) {
            throw e
        } catch (e: IOException) {
            throw SpoolmanException("Can't reach Spoolman at $apiBase (${e.message})")
        } catch (e: IllegalArgumentException) {
            // OkHttp throws this (not IOException) for a malformed URL, e.g. a
            // stray space from autocorrect — surface it instead of crashing.
            throw SpoolmanException("Invalid Spoolman URL '$apiBase' (${e.message})")
        } catch (e: SerializationException) {
            // A 200 response that isn't JSON — the URL points at something
            // that answers HTTP but isn't a Spoolman API (router page, proxy).
            throw SpoolmanException("$apiBase didn't return JSON — is this really a Spoolman server?")
        }

    private companion object {
        /** PLA-ish default; Spoolman requires density and the tag doesn't carry it. */
        const val DEFAULT_DENSITY_G_CM3 = 1.24

        /** mass = π·r²·length·density, from the tag's length + diameter. */
        fun estimateWeightGrams(info: FilamentInfo): Int? {
            val lengthCm = (info.lengthMeters ?: return null) * 100.0
            val radiusCm = (info.diameterMm ?: return null) / 2.0 / 10.0
            return (PI * radiusCm * radiusCm * lengthCm * DEFAULT_DENSITY_G_CM3).roundToInt()
        }
    }
}
