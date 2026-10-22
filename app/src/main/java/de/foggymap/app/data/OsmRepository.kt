package de.foggymap.app.data

import android.content.Context
import de.foggymap.app.BuildConfig
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
 * Street/building data per map cell from the public Overpass API.
 * Every cell is stored permanently (gzip) so exploration keeps working offline in known areas.
 * Only the cell bounding box is sent to Overpass – never GPS tracks.
 */
class OsmRepository(context: Context) {
    private val dir = File(context.filesDir, "osm").apply { mkdirs() }
    private val indexFile = File(dir, "cells.json")
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(75, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )
    private val memory = object : LinkedHashMap<CellKey, WorldModel>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CellKey, WorldModel>?) = size > 6
    }
    private val lock = Mutex()
    private var preferredEndpoint = 0

    sealed class Result {
        class Ok(val model: WorldModel, val fromCache: Boolean) : Result()
        class Failed(val message: String, val offline: Boolean) : Result()
    }

    fun isCached(cell: CellKey) = file(cell).exists()

    suspend fun load(cell: CellKey, allowNetwork: Boolean = true): Result = lock.withLock {
        memory[cell]?.let { return Result.Ok(it, true) }
        withContext(Dispatchers.IO) {
            val f = file(cell)
            val stale = f.exists() && System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS
            var json: String? = null
            var fromCache = false
            if (f.exists() && (!stale || !allowNetwork)) { json = readGz(f); fromCache = true }
            var error: String? = null
            var offline = false
            if (json == null && allowNetwork) {
                try {
                    json = fetch(cell)
                    writeGz(f, json)
                    trimCache()
                } catch (e: IOException) {
                    error = e.message ?: "Netzwerkfehler"
                    offline = e is java.net.UnknownHostException || e is java.net.ConnectException ||
                        e is java.net.SocketTimeoutException
                    if (f.exists()) { json = readGz(f); fromCache = true }   // stale but usable
                }
            }
            if (json == null) return@withContext Result.Failed(error ?: "Kartendaten nicht verfügbar (offline)", offline || !allowNetwork)
            try {
                val model = WorldModel(OsmParser.parse(json, cell.queryBox()))
                memory[cell] = model
                recordTotal(cell, model)
                Result.Ok(model, fromCache)
            } catch (e: Exception) {
                f.delete()
                Result.Failed("Kartendaten fehlerhaft: ${e.message}", false)
            }
        }
    }

    private fun fetch(cell: CellKey): String {
        val query = OverpassQuery.forBox(cell.queryBox())
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

    // ------------------------------------------------------------ street totals per cell (for the percentage)

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
        synchronized(memory) { memory.clear() }
    }

    private fun trimCache() {
        val files = dir.listFiles { f -> f.name.endsWith(".json.gz") }?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        for (f in files) { if (size <= MAX_CACHE_BYTES) break; size -= f.length(); f.delete() }
    }

    private fun file(cell: CellKey) = File(dir, "${cell.name}.json.gz")
    private fun readGz(f: File): String? = try { GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() } } catch (_: Exception) { null }
    private fun writeGz(f: File, s: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        GZIPOutputStream(tmp.outputStream()).bufferedWriter().use { it.write(s) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    companion object {
        const val MAX_AGE_MS = 60L * 24 * 3600 * 1000
        const val MAX_CACHE_BYTES = 250L * 1024 * 1024
    }
}
