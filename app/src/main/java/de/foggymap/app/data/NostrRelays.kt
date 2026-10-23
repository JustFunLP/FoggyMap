package de.foggymap.app.data

import de.foggymap.app.core.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Talks to several public Nostr relays at once (WebSocket, NIP-01). A short connection per sync,
 * nothing stays open in the background. Data is only lost if *all* relays lose it.
 */
class NostrRelays(val urls: List<String>, private val userAgent: String,
                  private val client: OkHttpClient = OkHttpClient.Builder()
                      .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()) {

    data class Ack(val relay: String, val eventId: String, val ok: Boolean, val message: String)

    /** Sends [events] to every relay; returns one acknowledgement per relay and event. */
    suspend fun publish(events: List<NostrEvent>, timeoutMs: Long = 15_000): List<Ack> = coroutineScope {
        if (events.isEmpty()) return@coroutineScope emptyList()
        urls.map { url -> async(Dispatchers.IO) { publishTo(url, events, timeoutMs) } }.awaitAll().flatten()
    }

    private suspend fun publishTo(url: String, events: List<NostrEvent>, timeoutMs: Long): List<Ack> {
        val pending = Collections.synchronizedSet(events.map { it.id }.toMutableSet())
        val acks = Collections.synchronizedList(ArrayList<Ack>())
        val done = CompletableDeferred<String?>()
        val ws = client.newWebSocket(Request.Builder().url(url).header("User-Agent", userAgent).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                for (e in events) webSocket.send(JSONArray().put("EVENT").put(e.toJson()).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val a = try { JSONArray(text) } catch (_: Exception) { return }
                if (a.optString(0) == "OK") {
                    val id = a.optString(1)
                    acks += Ack(url, id, a.optBoolean(2), a.optString(3))
                    pending.remove(id)
                    if (pending.isEmpty()) done.complete(null)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.complete(t.message ?: "Verbindungsfehler") }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.complete(reason) }
        })
        val problem = withTimeoutOrNull(timeoutMs) { done.await() } ?: if (pending.isEmpty()) null else "Zeitüberschreitung"
        ws.close(1000, null)
        synchronized(pending) { pending.forEach { acks += Ack(url, it, false, problem ?: "keine Antwort") } }
        return acks
    }

    /** Events matching [filter] from all relays, de-duplicated (for replaceable events only the newest per author and "d"). */
    suspend fun query(filter: JSONObject, timeoutMs: Long = 15_000): List<NostrEvent> = coroutineScope {
        val all = urls.map { url -> async(Dispatchers.IO) { queryFrom(url, filter, timeoutMs) } }.awaitAll().flatten()
        val newest = HashMap<String, NostrEvent>()
        for (e in all.distinctBy { it.id }) {
            val key = if (e.kind in 30000..39999) "${e.pubkey}|${e.kind}|${e.tag("d")}" else e.id
            val old = newest[key]
            if (old == null || e.createdAt > old.createdAt) newest[key] = e
        }
        newest.values.toList()
    }

    /** Which relays answered the last query (for the status line). */
    @Volatile var reachable: Int = 0; private set

    private suspend fun queryFrom(url: String, filter: JSONObject, timeoutMs: Long): List<NostrEvent> {
        val sub = "fm" + (100000..999999).random()
        val out = Collections.synchronizedList(ArrayList<NostrEvent>())
        val done = CompletableDeferred<Boolean>()
        val ws = client.newWebSocket(Request.Builder().url(url).header("User-Agent", userAgent).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONArray().put("REQ").put(sub).put(filter).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val a = try { JSONArray(text) } catch (_: Exception) { return }
                when (a.optString(0)) {
                    "EVENT" -> if (a.optString(1) == sub) a.optJSONObject(2)?.let { NostrEvent.fromJson(it) }?.let { out += it }
                    "EOSE" -> if (a.optString(1) == sub) done.complete(true)
                    "CLOSED" -> if (a.optString(1) == sub) done.complete(true)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.complete(false) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.complete(false) }
        })
        val ok = withTimeoutOrNull(timeoutMs) { done.await() } ?: false
        try { ws.send(JSONArray().put("CLOSE").put(sub).toString()) } catch (_: Exception) { }
        ws.close(1000, null)
        if (ok) synchronized(this) { reachable++ }
        return ArrayList(out)
    }

    fun resetReachable() { reachable = 0 }

    companion object {
        /** Public relays that accept app data (NIP-78) – checked by the CI relay test. */
        val DEFAULT = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://nostr.mom",
            "wss://offchain.pub",
        )
    }
}
