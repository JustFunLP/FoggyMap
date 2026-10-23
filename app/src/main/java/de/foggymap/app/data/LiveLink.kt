package de.foggymap.app.data

import android.location.Location
import android.util.Log
import de.foggymap.app.App
import de.foggymap.app.core.LatLon
import de.foggymap.app.core.Geo
import de.foggymap.app.core.RevealItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

/** A group member seen on the live channel in the last minutes. */
data class LiveFriend(val key: String, val name: String, val pos: LatLon, val heading: Double?, val speed: Double?, val atMs: Long)

/**
 * Decides when the live channel ([NostrLive]) is open:
 *  - sending: only while exploring AND "Live-Standort" is switched on (explicit opt-in),
 *  - listening: while the map is on screen and you are in a group.
 * Sends position + direction every ~2.5 s while moving (every 15 s while standing) together with the
 * reveals made since the last message, so friends see your fog clear up almost at once.
 */
class LiveLink(private val sync: SyncManager, private val db: RevealDatabase, private val prefs: Prefs) {
    val friends = MutableStateFlow<Map<String, LiveFriend>>(emptyMap())

    private var link: NostrLive? = null
    private var linkCode = ""
    @Volatile private var sending = false
    @Volatile private var listening = false
    private val pending = ArrayList<RevealItem>()
    private var lastSentAt = 0L
    private var lastSentPos: LatLon? = null
    private var lastHeading: Double? = null

    // The group key derivation (PBKDF2) takes a moment: never on the main thread.
    fun setSending(on: Boolean) { sending = on; refresh() }
    fun setListening(on: Boolean) { listening = on; refresh() }
    /** Group or settings changed. */
    fun refresh() { App.scope.launch(Dispatchers.IO) { synchronized(this@LiveLink) { apply() } } }

    private fun apply() {
        val share = sending && prefs.shareLiveLocation
        val wanted = (share || listening) && sync.usesNostr && prefs.groupCode.isNotEmpty()
        if (!wanted || linkCode != prefs.groupCode) {
            link?.stop(); link = null; linkCode = ""
            if (!share) synchronized(pending) { pending.clear() }
        }
        if (!wanted) { if (!listening) friends.value = emptyMap(); return }
        val l = link ?: sync.liveChannel()?.also { ch ->
            ch.onLive = { onFriend(it) }
            link = ch; linkCode = prefs.groupCode
        } ?: return
        l.start(listen = listening)
        l.setListening(listening)
    }

    /** New own reveals: they travel with the next live message. */
    fun queueItems(items: List<RevealItem>) {
        if (items.isEmpty() || !sending || !prefs.shareLiveLocation) return
        synchronized(pending) { pending += items; if (pending.size > 400) pending.subList(0, pending.size - 400).clear() }
    }

    /** Called for every position while exploring; sends when it is time. */
    fun onPosition(loc: Location) {
        val l = synchronized(this) { link } ?: return
        if (!sending || !prefs.shareLiveLocation) return
        val now = System.currentTimeMillis()
        val pos = LatLon(loc.latitude, loc.longitude)
        val moved = lastSentPos?.let { Geo.distance(it, pos) } ?: Double.MAX_VALUE
        val hasItems = synchronized(pending) { pending.isNotEmpty() }
        val due = if (moved > 2.0 || hasItems) INTERVAL_MS else STANDING_MS
        if (now - lastSentAt < due) return
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.8f) lastHeading = loc.bearing.toDouble()
        val items = synchronized(pending) {
            var size = 0
            val take = pending.takeWhile { size += it.toJson().toString().length; size <= NostrLive.MAX_ITEMS_JSON }
            pending.subList(0, take.size).clear()
            take
        }
        lastSentAt = now; lastSentPos = pos
        val n = runCatching { l.send(prefs.nickname, pos.lat, pos.lon, lastHeading, if (loc.hasSpeed()) loc.speed.toDouble() else null, items) }.getOrDefault(0)
        if (n == 0 && items.isNotEmpty()) synchronized(pending) { pending.addAll(0, items) }   // not connected yet: try again
    }

    private fun onFriend(live: NostrLive.Live) {
        val key = live.pubkey
        friends.update { old ->
            val prev = old[key]
            val heading = live.heading ?: prev?.heading
            (old.filterValues { System.currentTimeMillis() - it.atMs < STALE_MS } +
                (key to LiveFriend(key, live.name, LatLon(live.lat, live.lon), heading, live.speed, System.currentTimeMillis())))
        }
        if (live.items.isNotEmpty()) {
            val added = runCatching { db.insert(live.items, "n:" + key.take(16), synced = true) }.getOrElse { emptyList() }
            if (added.isNotEmpty()) {
                Log.i("FoggyMap", "live: ${added.size} reveals from ${live.name}")
                App.notifyDbChanged(remoteRoads = added.filter { it.id.startsWith("r:") }.map { it.id })
            }
        }
    }

    companion object {
        const val INTERVAL_MS = 2_500L
        const val STANDING_MS = 15_000L
        /** A friend not heard of for this long disappears from the live view. */
        const val STALE_MS = 120_000L
    }
}
