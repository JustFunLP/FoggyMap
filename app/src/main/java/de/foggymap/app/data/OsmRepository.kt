package de.foggymap.app.data

import android.content.Context
import de.foggymap.app.BuildConfig
import de.foggymap.app.core.BuildingFootprint
import de.foggymap.app.core.BuildingParser
import de.foggymap.app.core.BuildingTileKey
import de.foggymap.app.core.CellKey
import de.foggymap.app.core.OsmParser
import de.foggymap.app.core.OverpassQuery
import de.foggymap.app.core.WorldModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * OpenStreetMap data from the public Overpass API, stored permanently (gzip) so every area is
 * downloaded once and works offline afterwards. Only bounding boxes are sent – never GPS tracks.
 *
 *  - street cells (~1.8 × 1.7 km): streets/railways/rivers only – small and quick
 *  - building tiles (~450 × 420 m): footprints, loaded only where something was revealed
 */
class OsmRepository(context: Context) {
    private val dir = File(context.filesDir, "osm").apply { mkdirs() }
    private val indexFile = File(dir, "cells.json")
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(70, TimeUnit.SECONDS).callTimeout(80, TimeUnit.SECONDS)
        .build()
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )
    private val roadMemory = object : LinkedHashMap<CellKey, WorldModel>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CellKey, WorldModel>?) = size > 3
    }
    private val buildingMemory = object : LinkedHashMap<BuildingTileKey, List<BuildingFootprint>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BuildingTileKey, List<BuildingFootprint>>?) = size > 40
    }
    // Separate locks: a slow building download never delays the street data you need right now.
    private val roadLock = Mutex()
    private val buildingLock = Mutex()
    @Volatile private var preferredEndpoint = 0

    sealed class Result<out T> {
        class Ok<T>(val value: T, val fromCache: Boolean) : Result<T>()
        class Failed(val message: String, val offline: Boolean) : Result<Nothing>()
    }

    init { migrateOldCache() }

    // ------------------------------------------------------------------ street cells

    suspend fun load(cell: CellKey, allowNetwork: Boolean = true): Result<WorldModel> = roadLock.withLock {
        synchronized(roadMemory) { roadMemory[cell] }?.let { return Result.Ok(it, true) }
        withContext(Dispatchers.IO) {
            when (val r = fetchCached(File(dir, "${cell.name}.json.gz"), OverpassQuery.roads(cell.queryBox()), allowNetwork)) {
                is Result.Failed -> r
                is Result.Ok -> try {
                    val model = WorldModel(OsmParser.parse(r.value, cell.queryBox()))
                    synchronized(roadMemory) { roadMemory[cell] = model }
                    recordTotal(cell, model)
                    Result.Ok(model, r.fromCache)
                } catch (e: Exception) {
                    File(dir, "${cell.name}.json.gz").delete()
                    Result.Failed("Straßendaten fehlerhaft: ${e.message}", false)
                }
            }
        }
    }

    // ------------------------------------------------------------------ building tiles

    fun cachedBuildings(tile: BuildingTileKey): List<BuildingFootprint>? = synchronized(buildingMemory) { buildingMemory[tile] }

    suspend fun loadBuildings(tile: BuildingTileKey, allowNetwork: Boolean = true): Result<List<BuildingFootprint>> = buildingLock.withLock {
        cachedBuildings(tile)?.let { return Result.Ok(it, true) }
        withContext(Dispatchers.IO) {
            val f = File(dir, "${tile.name}.json.gz")
            when (val r = fetchCached(f, OverpassQuery.buildings(tile.queryBox()), allowNetwork)) {
                is Result.Failed -> r
                is Result.Ok -> try {
                    val list = BuildingParser.parse(OsmParser.parse(r.value, tile.queryBox()))
                    synchronized(buildingMemory) { buildingMemory[tile] = list }
                    Result.Ok(list, r.fromCache)
                } catch (e: Exception) {
                    f.delete()
                    Result.Failed("Gebäudedaten fehlerhaft: ${e.message}", false)
                }
            }
        }
    }

    // ------------------------------------------------------------------ download + disk cache

    private fun fetchCached(f: File, query: String, allowNetwork: Boolean): Result<String> {
        val stale = f.exists() && System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS
        if (f.exists() && (!stale || !allowNetwork)) readGz(f)?.let { return Result.Ok(it, true) }
        if (!allowNetwork) return Result.Failed("offline", true)
        return try {
            val json = fetch(query)
            writeGz(f, json)
            trimCache()
            Result.Ok(json, false)
        } catch (e: IOException) {
            val offline = e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException
            if (f.exists()) readGz(f)?.let { return Result.Ok(it, true) }   // stale but usable
            Result.Failed(e.message ?: "Netzwerkfehler", offline)
        }
    }

    private fun fetch(query: String): String {
        var last: IOException? = null
        for (k in endpoints.indices) {
            val idx = (preferredEndpoint + k) % endpoints.size
            try {
                val req = Request.Builder().url(endpoints[idx])
                    .post(FormBody.Builder().add("data", query).build())
                    .header("User-Agent", "FoggyMap/${BuildConfig.VERSION_NAME} (Android; personal exploration game)")
                    .build()
                client.newCall(req).execute().use { rsp ->
                    if (!rsp.isSuccessful) throw IOException("OpenStreetMap-Server antwortet ${rsp.code}")
                    val body = rsp.body?.string() ?: throw IOException("Leere Antwort")
                    // Overpass reports timeouts/overload inside a 200 response.
                    if (body.contains("\"remark\"") && body.contains("runtime error")) throw IOException("Overpass überlastet")
                    preferredEndpoint = idx
                    return body
                }
            } catch (e: IOException) { last = e }
        }
        throw last ?: IOException("Kein OpenStreetMap-Server erreichbar")
    }

    // ------------------------------------------------------------------ street totals per cell (for the percentage)

    @Synchronized private fun recordTotal(cell: CellKey, model: WorldModel) {
        val core = cell.core()
        val total = model.completeSegments.filter { core.contains(it.points[it.points.size / 2]) }.sumOf { it.length }
        val idx = readIndex()
        idx.put(cell.name, total)
        indexFile.writeText(idx.toString())
    }

    @Synchronized fun cellTotals(): Map<String, Double> {
        val idx = readIndex()
        return idx.keys().asSequence().associateWith { idx.optDouble(it, 0.0) }
    }

    private fun readIndex(): JSONObject = try { JSONObject(indexFile.readText()) } catch (_: Exception) { JSONObject() }

    fun cacheSizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    @Synchronized fun clearCache() {
        dir.listFiles()?.forEach { it.delete() }
        synchronized(roadMemory) { roadMemory.clear() }
        synchronized(buildingMemory) { buildingMemory.clear() }
    }

    /** Version 1.0.≤18 stored small combined cells ("c_…") – drop them once. */
    private fun migrateOldCache() {
        val old = dir.listFiles { f -> f.name.startsWith("c_") } ?: return
        if (old.isEmpty()) return
        old.forEach { it.delete() }
        indexFile.delete()
    }

    private fun trimCache() {
        val files = dir.listFiles { f -> f.name.endsWith(".json.gz") }?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        for (f in files) { if (size <= MAX_CACHE_BYTES) break; size -= f.length(); f.delete() }
    }

    private fun readGz(f: File): String? = try { GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() } } catch (_: Exception) { null }
    private fun writeGz(f: File, s: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        GZIPOutputStream(tmp.outputStream()).bufferedWriter().use { it.write(s) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    companion object {
        const val MAX_AGE_MS = 90L * 24 * 3600 * 1000
        const val MAX_CACHE_BYTES = 300L * 1024 * 1024
    }
}
