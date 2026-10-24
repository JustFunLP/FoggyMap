package de.foggymap.app.data

import de.foggymap.app.core.GroupKey
import de.foggymap.app.core.NostrEvent
import de.foggymap.app.core.NostrPacking
import de.foggymap.app.core.RevealItem
import de.foggymap.app.core.Schnorr
import de.foggymap.app.core.hex
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Live channel of a group: every 2–3 seconds position, direction and the newest reveals, while
 * "Live-Standort" is switched on. Uses *ephemeral* Nostr events (kind 2xxxx, NIP-16): relays forward
 * them to whoever is listening right now and do not store them. Everything is encrypted with the
 * group key like the rest of the group data. Connections stay open only while needed.
 *
 * Battery: only [MAX_RELAYS] of the relays are used at a time (any two such choices out of five share at
 * least one relay, so members still meet), and members who have the map open send a tiny "I am looking"
 * sign ([KIND_PRESENCE]) every 30 s – senders only go fast (2–3 s) while somebody is looking.
 *
 * Pure JVM (no Android types), so the CI can test it against the real relays.
 */
class NostrLive(
    private val urls: List<String>,
    private val userAgent: String,
    private val secret: ByteArray,
    private val key: GroupKey,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).pingInterval(50, TimeUnit.SECONDS).build(),
) {
    data class Live(val pubkey: String, val name: String, val lat: Double, val lon: Double, val heading: Double?,
                    val speed: Double?, val atMs: Long, val items: List<RevealItem>, val mode: String? = null)

    val myPubkey: String = Schnorr.publicKey(secret).hex()
    /** Called (on a background thread) for every live message of another member. */
    @Volatile var onLive: ((Live) -> Unit)? = null
    /** Called (on a background thread) when another member signals that they have the map open. */
    @Volatile var onPresence: ((String) -> Unit)? = null

    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val open = ConcurrentHashMap.newKeySet<String>()
    private val backoff = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, Int>()
    /** The relays in use right now (at most [MAX_RELAYS]); a relay failing twice is swapped for a spare. */
    private val inUse = java.util.Collections.synchronizedList(ArrayList<String>())
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nostr-live").apply { isDaemon = true } }
    @Volatile private var running = false
    @Volatile private var listening = false
    private val seen = object : LinkedHashMap<String, Boolean>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 500
    }
    private val sub = "fl" + (100000..999999).random()
    private val presenceSub = "fp" + (100000..999999).random()

    val connectedRelays: Int get() = open.size

    /** Opens the connections. [listen]: also receive the others' live messages. */
    @Synchronized fun start(listen: Boolean) {
        listening = listen
        if (!running) {
            running = true
            inUse.clear(); inUse.addAll(urls.take(MAX_RELAYS))
            inUse.toList().forEach { connect(it) }
        } else open.forEach { url -> sockets[url]?.let { if (listen) subscribe(it) else unsubscribe(it) } }
    }

    @Synchronized fun setListening(listen: Boolean) {
        if (listen == listening) return
        listening = listen
        open.forEach { url -> sockets[url]?.let { if (listen) subscribe(it) else unsubscribe(it) } }
    }

    @Synchronized fun stop() {
        running = false
        sockets.values.forEach { runCatching { it.close(1000, null) } }
        sockets.clear(); open.clear()
    }

    /** "I have the map open": tells senders to go fast. Returns how many relays took it. */
    fun sendPresence(): Int {
        val now = System.currentTimeMillis() / 1000
        val e = NostrEvent.sign(secret, now, KIND_PRESENCE, listOf(listOf("t", key.tag)), key.seal(JSONObject().put("w", 1).toString()))
        val msg = JSONArray().put("EVENT").put(e.toJson()).toString()
        return open.count { url -> sockets[url]?.send(msg) == true }
    }

    /** Sends one live message to every connected relay; returns how many took it. */
    fun send(name: String, lat: Double, lon: Double, heading: Double?, speed: Double?, items: List<RevealItem>, mode: String? = null): Int {
        val o = JSONObject().put("n", name).put("a", Math.round(lat * 1e6) / 1e6).put("o", Math.round(lon * 1e6) / 1e6)
        mode?.let { o.put("m", it) }
        heading?.let { o.put("h", Math.round(it).toInt()) }
        speed?.let { o.put("s", Math.round(it * 10) / 10.0) }
        if (items.isNotEmpty()) o.put("i", JSONArray(NostrPacking.encodeItems(items)))
        val now = System.currentTimeMillis() / 1000
        val e = NostrEvent.sign(secret, now, KIND, listOf(listOf("t", key.tag), listOf("k", "live")), key.seal(o.toString()))
        val msg = JSONArray().put("EVENT").put(e.toJson()).toString()
        return open.count { url -> sockets[url]?.send(msg) == true }
    }

    private fun connect(url: String) {
        if (!running) return
        val ws = client.newWebSocket(Request.Builder().url(url).header("User-Agent", userAgent).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open += url; backoff.remove(url); failures.remove(url)
                subscribePresence(webSocket)
                if (listening) subscribe(webSocket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) { handle(text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(url, webSocket)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(url, webSocket)
        })
        sockets[url] = ws
    }

    private fun lost(url: String, ws: WebSocket) {
        if (sockets[url] !== ws) return
        open -= url
        if (!running) return
        // Failing again and again: use a spare relay instead (keeps MAX_RELAYS working connections).
        val failed = (failures[url] ?: 0) + 1
        failures[url] = failed
        if (failed >= 2) {
            val spare = synchronized(inUse) { urls.firstOrNull { it !in inUse && (failures[it] ?: 0) < 2 } }
            if (spare != null) {
                synchronized(inUse) { inUse.remove(url); inUse.add(spare) }
                sockets.remove(url)
                connect(spare)
                return
            }
        }
        val wait = minOf((backoff[url] ?: 2_500L) * 2, 120_000L)
        backoff[url] = wait
        timer.schedule({ if (running && sockets[url] === ws) connect(url) }, wait, TimeUnit.MILLISECONDS)
    }

    private fun subscribe(ws: WebSocket) {
        val filter = JSONObject().put("kinds", JSONArray().put(KIND)).put("#t", JSONArray().put(key.tag))
            .put("since", System.currentTimeMillis() / 1000 - 15)
        ws.send(JSONArray().put("REQ").put(sub).put(filter).toString())
    }

    private fun unsubscribe(ws: WebSocket) { ws.send(JSONArray().put("CLOSE").put(sub).toString()) }

    private fun subscribePresence(ws: WebSocket) {
        val filter = JSONObject().put("kinds", JSONArray().put(KIND_PRESENCE)).put("#t", JSONArray().put(key.tag))
            .put("since", System.currentTimeMillis() / 1000 - 15)
        ws.send(JSONArray().put("REQ").put(presenceSub).put(filter).toString())
    }

    private fun handle(text: String) {
        val a = try { JSONArray(text) } catch (_: Exception) { return }
        if (a.optString(0) != "EVENT" || (a.optString(1) != sub && a.optString(1) != presenceSub)) return
        val e = a.optJSONObject(2)?.let { NostrEvent.fromJson(it) } ?: return
        if ((e.kind != KIND && e.kind != KIND_PRESENCE) || e.pubkey == myPubkey) return
        synchronized(seen) { if (seen.put(e.id, true) != null) return }
        if (System.currentTimeMillis() / 1000 - e.createdAt > 120) return       // stale
        if (!e.verify()) return
        val o = key.open(e.content)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        if (e.kind == KIND_PRESENCE) { onPresence?.invoke(e.pubkey); return }
        if (!o.has("a") || !o.has("o")) return
        val items = o.optJSONArray("i")?.let { NostrPacking.decodeItems(it.toString()) } ?: emptyList()
        onLive?.invoke(Live(e.pubkey, o.optString("n", "?"), o.optDouble("a"), o.optDouble("o"),
            if (o.has("h")) o.optDouble("h") else null, if (o.has("s")) o.optDouble("s") else null, e.createdAt * 1000, items,
            o.optString("m").ifEmpty { null }))
    }

    companion object {
        /** Ephemeral app event (20000–29999: relays forward, never store). */
        const val KIND = 20078
        /** "I have the map open" (separate kind: older app versions never see it). */
        const val KIND_PRESENCE = 20079
        /** Relays used at the same time (of 5: any two choices of 3 overlap). */
        const val MAX_RELAYS = 3
        /** Upper size of the reveals carried in one live message (JSON characters). */
        const val MAX_ITEMS_JSON = 24_000
    }
}
