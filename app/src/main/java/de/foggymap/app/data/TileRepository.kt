package de.foggymap.app.data

import android.content.Context
import android.net.ConnectivityManager
import de.foggymap.app.BuildConfig
import de.foggymap.app.core.BuildingFootprint
import de.foggymap.app.core.CellKey
import de.foggymap.app.core.Geo
import de.foggymap.app.core.LatLon
import de.foggymap.app.core.Mvt
import de.foggymap.app.core.TileKey
import de.foggymap.app.core.TileNetwork
import de.foggymap.app.core.WorldModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.cos

/**
 * Street and building data from the vector tiles of OpenFreeMap – the same tiles the map shows,
 * served by a fast CDN. A z14 tile (~1.5 km) is a few hundred KB and loads in well under a second,
 * so exploring never waits minutes for a server. Tiles are stored permanently (offline use) and the
 * surroundings are fetched in the background ("ganze Stadt").
 *
 * OpenFreeMap rules for app downloads: at most 4 parallel requests, at most 3,000 tiles / 50 MB per
 * download, a unique User-Agent. Only tile coordinates are requested – never the GPS track.
 */
class TileRepository(private val context: Context) {
    private val dir = File(context.filesDir, "tiles").apply { mkdirs() }
    private val indexFile = File(dir, "cells.json")
    private val tileJsonFile = File(dir, "tilejson.json")
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
        .build()
    private val userAgent = "FoggyMap/${BuildConfig.VERSION_NAME} (Android; https://github.com/JustFunLP/FoggyMap)"

    /** All requests together never exceed 4 parallel connections … */
    private val network = Semaphore(4)
    /** … and the background download uses at most 2 of them, so what you need right now is never queued behind it. */
    private val background = Semaphore(2)
    private val tileLocks = ConcurrentHashMap<TileKey, Mutex>()
    private val buildLock = Mutex()
    @Volatile private var template: String? = null

    /** Street graph of a cell; the building footprints follow a moment later (computed in the background). */
    class Cell(val model: WorldModel) {
        val buildings = kotlinx.coroutines.CompletableDeferred<List<BuildingFootprint>>()
    }

    private val memory = object : LinkedHashMap<CellKey, Cell>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CellKey, Cell>?) = size > 3
    }

    sealed class Result<out T> {
        class Ok<T>(val value: T, val fromCache: Boolean) : Result<T>()
        class Failed(val message: String, val offline: Boolean) : Result<Nothing>()
    }

    data class Prefetch(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val bytes: Long = 0,
                        val radiusKm: Int = 0, val message: String? = null)
    val prefetch = MutableStateFlow(Prefetch())

    init { File(context.filesDir, "osm").takeIf { it.exists() }?.deleteRecursively() }   // old Overpass cache (≤ 1.0.21)

    // ------------------------------------------------------------------ cells (street graph + buildings)

    fun cached(cell: CellKey): Cell? = synchronized(memory) { memory[cell] }

    suspend fun load(cell: CellKey, allowNetwork: Boolean = true): Result<Cell> {
        cached(cell)?.let { return Result.Ok(it, true) }
        val tiles = cell.tiles()
        val results = coroutineScope { tiles.map { t -> async(Dispatchers.IO) { ensureTile(t, allowNetwork, false) } }.awaitAll() }
        results.firstOrNull { it is Result.Failed }?.let { return it as Result.Failed }
        val fromCache = results.all { (it as Result.Ok<File?>).fromCache }
        return buildLock.withLock {
            cached(cell)?.let { return@withLock Result.Ok(it, true) }
            withContext(Dispatchers.Default) {
                try {
                    val decoded = HashMap<TileKey, Map<String, Mvt.Layer>>()
                    for ((i, t) in tiles.withIndex()) {
                        val f = (results[i] as Result.Ok<File?>).value ?: continue   // empty tile (sea)
                        val bytes = readGz(f) ?: throw IOException("Kachel ${t.name} beschädigt")
                        decoded[t] = Mvt.decode(bytes, TileNetwork.LAYERS)
                        f.setLastModified(System.currentTimeMillis())
                    }
                    val out = TileNetwork.build(decoded, cell.queryBox())
                    val c = Cell(WorldModel(out.data))
                    synchronized(memory) { memory[cell] = c }
                    recordTotal(cell, c.model)
                    de.foggymap.app.App.scope.launch(Dispatchers.Default) {
                        c.buildings.complete(runCatching { out.buildings }.getOrDefault(emptyList()))
                    }
                    Result.Ok(c, fromCache)
                } catch (e: Exception) {
                    // A broken file must not block the area forever: drop the tiles, they are fetched again.
                    tiles.forEach { tileFile(it).delete() }
                    Result.Failed("Kartendaten fehlerhaft: ${e.message}", false)
                }
            }
        }
    }

    // ------------------------------------------------------------------ tiles on disk

    /** Number and size of the stored map tiles (for the problem report). */
    fun cacheInfo(): Pair<Int, Long> {
        val files = dir.listFiles()?.filter { it.name.endsWith(".pbf.gz") } ?: emptyList()
        return files.size to files.sumOf { it.length() }
    }

    private fun tileFile(t: TileKey) = File(dir, "${t.name}.pbf.gz")
    private fun emptyMarker(t: TileKey) = File(dir, "${t.name}.empty")
    fun hasTile(t: TileKey) = tileFile(t).exists() || emptyMarker(t).exists()

    /** File of the tile (null = tile has no data), downloading it if needed. */
    private suspend fun ensureTile(t: TileKey, allowNetwork: Boolean, isBackground: Boolean): Result<File?> {
        tileFile(t).takeIf { it.exists() }?.let { return Result.Ok(it, true) }
        if (emptyMarker(t).exists()) return Result.Ok(null, true)
        if (!allowNetwork) return Result.Failed("offline", true)
        val lock = tileLocks.getOrPut(t) { Mutex() }
        return lock.withLock {
            tileFile(t).takeIf { it.exists() }?.let { return@withLock Result.Ok(it, true) }
            if (emptyMarker(t).exists()) return@withLock Result.Ok(null, true)
            try {
                val bytes = if (isBackground) background.withPermit { network.withPermit { download(t) } }
                    else network.withPermit { download(t) }
                if (bytes == null || bytes.isEmpty()) { emptyMarker(t).createNewFile(); Result.Ok(null, false) }
                else { writeGz(tileFile(t), bytes); Result.Ok(tileFile(t), false) }
            } catch (e: IOException) {
                val offline = e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException
                Result.Failed(e.message ?: "Netzwerkfehler", offline)
            }
        }
    }

    private suspend fun download(t: TileKey): ByteArray? = withContext(Dispatchers.IO) {
        val url = urlTemplate().replace("{z}", "14").replace("{x}", t.x.toString()).replace("{y}", t.y.toString())
        val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
        client.newCall(req).execute().use { rsp ->
            when {
                rsp.code == 204 || rsp.code == 404 -> null
                !rsp.isSuccessful -> throw IOException("Kartenserver antwortet ${rsp.code}")
                else -> {
                    val raw = rsp.body?.bytes() ?: ByteArray(0)
                    // Some servers send pre-compressed tiles without Content-Encoding.
                    if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) GZIPInputStream(raw.inputStream()).readBytes() else raw
                }
            }
        }
    }

    /** Current tile URL from OpenFreeMap's TileJSON (it changes with every weekly planet build). */
    @Synchronized private fun urlTemplate(): String {
        template?.let { return it }
        val cached = runCatching { JSONObject(tileJsonFile.readText()) }.getOrNull()
        val fresh = cached != null && System.currentTimeMillis() - tileJsonFile.lastModified() < 3L * 24 * 3600 * 1000
        if (cached != null && fresh) return parseTemplate(cached).also { template = it }
        return try {
            val req = Request.Builder().url(TILEJSON).header("User-Agent", userAgent).build()
            val body = client.newCall(req).execute().use { rsp ->
                if (!rsp.isSuccessful) throw IOException("Kartenserver antwortet ${rsp.code}")
                rsp.body?.string() ?: throw IOException("Leere Antwort")
            }
            val json = JSONObject(body)
            val tpl = parseTemplate(json)
            tileJsonFile.writeText(body)
            tpl.also { template = it }
        } catch (e: Exception) {
            if (cached != null) parseTemplate(cached).also { template = it }
            else throw (e as? IOException ?: IOException(e.message))
        }
    }

    private fun parseTemplate(json: JSONObject): String {
        val arr: JSONArray = json.getJSONArray("tiles")
        val tpl = arr.getString(0)
        if (!tpl.startsWith("https://") || !tpl.contains("{z}")) throw IOException("Unerwartete Kachel-Adresse")
        return tpl
    }

    // ------------------------------------------------------------------ background download of the surroundings

    /**
     * Downloads all tiles within [radiusM] of [center] that are not stored yet, nearest first.
     * Respects OpenFreeMap's per-download limits (≤ 3,000 tiles, ≤ 50 MB).
     */
    suspend fun prefetchArea(center: LatLon, radiusM: Double): Int {
        val tiles = tilesAround(center, radiusM).filter { !hasTile(it) }
        val radiusKm = (radiusM / 1000).toInt()
        if (tiles.isEmpty()) { prefetch.value = Prefetch(false, 0, 0, 0, radiusKm); return 0 }
        prefetch.value = Prefetch(true, 0, tiles.size, 0, radiusKm)
        var done = 0; var bytes = 0L; var failed = 0
        val chunked = tiles.take(MAX_TILES_PER_DOWNLOAD).chunked(8)
        for (chunk in chunked) {
            if (bytes > MAX_BYTES_PER_DOWNLOAD) break
            val res = coroutineScope { chunk.map { t -> async(Dispatchers.IO) { ensureTile(t, true, true) } }.awaitAll() }
            for (r in res) {
                done++
                if (r is Result.Ok) bytes += r.value?.length() ?: 0 else failed++
            }
            prefetch.value = Prefetch(true, done, tiles.size, bytes, radiusKm)
            if (failed > 6 && failed > done / 3) break   // offline or server trouble: stop, try again later
        }
        trimCache()
        prefetch.value = Prefetch(false, done, tiles.size, bytes, radiusKm,
            if (failed > 0) "$failed Kacheln nicht geladen – wird später wiederholt" else null)
        return done - failed
    }

    fun tilesAround(center: LatLon, radiusM: Double): List<TileKey> {
        val dLat = radiusM / Geo.M_PER_DEG_LAT
        val dLon = radiusM / (Geo.M_PER_DEG_LAT * cos(Math.toRadians(center.lat)))
        val all = TileKey.covering(de.foggymap.app.core.BBox(center.lat - dLat, center.lon - dLon, center.lat + dLat, center.lon + dLon))
        return all.map { it to Geo.distance(center, it.bounds().center) }
            .filter { it.second <= radiusM + 1100 }
            .sortedBy { it.second }.map { it.first }
    }

    fun isUnmetered(): Boolean = runCatching {
        !context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
    }.getOrDefault(false)

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
    fun tileCount(): Int = dir.listFiles { f -> f.name.endsWith(".pbf.gz") }?.size ?: 0

    @Synchronized fun clearCache() {
        dir.listFiles()?.forEach { if (it != tileJsonFile) it.delete() }
        synchronized(memory) { memory.clear() }
    }

    private fun trimCache() {
        val files = dir.listFiles { f -> f.name.endsWith(".pbf.gz") }?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        for (f in files) { if (size <= MAX_CACHE_BYTES) break; size -= f.length(); f.delete() }
    }

    private fun readGz(f: File): ByteArray? = try { GZIPInputStream(f.inputStream().buffered()).use { it.readBytes() } } catch (_: Exception) { null }
    private fun writeGz(f: File, data: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        GZIPOutputStream(tmp.outputStream()).use { it.write(data) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    companion object {
        const val TILEJSON = "https://tiles.openfreemap.org/planet"
        const val MAX_CACHE_BYTES = 600L * 1024 * 1024
        const val MAX_TILES_PER_DOWNLOAD = 3000
        const val MAX_BYTES_PER_DOWNLOAD = 48L * 1024 * 1024
        /** Automatic background download around you: on Wi-Fi the whole city, on mobile data the district. */
        const val AUTO_RADIUS_WIFI = 8_000.0
        const val AUTO_RADIUS_MOBILE = 3_000.0
        /** "Ganze Stadt herunterladen" in the settings. */
        const val CITY_RADIUS = 12_000.0
    }
}
