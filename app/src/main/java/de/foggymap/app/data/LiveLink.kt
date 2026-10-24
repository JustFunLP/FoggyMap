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
data class LiveFriend(val key: String, val name: String, val pos: LatLon, val heading: Double?, val speed: Double?, val atMs: Long,
                      val mode: de.foggymap.app.core.TravelMode? = null)

/**
 * Decides when the live channel ([NostrLive]) is open:
 *  - sending: only while exploring AND "Live-Standort" is switched on (explicit opt-in),
 *  - listening: while the map is on screen and you are in a group.
 * Sends position + direction every ~2.5 s while moving (every 15 s while standing) together with the
 * reveals made since the last message, so friends see your fog clear up almost at once – but only while
 * somebody in the group actually has the map open (they send a small "I am looking" sign every 30 s).
 * When nobody looks, every 20 s / 60 s is enough and the phone's radio can sleep in between.
 */
class LiveLink(private val sync: SyncManager, private val db: RevealDatabase, private val prefs: Prefs) {
    val friends = MutableStateFlow<Map<String, LiveFriend>>(emptyMap())
    /** Somebody in the group has the map open right now (then your live position goes out every 2–3 s). */
    val watched = MutableStateFlow(false)
    private val watchers = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var presenceJob: kotlinx.coroutines.Job? = null

    private var link: NostrLive? = null
    /** Open live connections (for the problem report). */
    val connectedRelays: Int get() = synchronized(this) { link?.connectedRelays ?: 0 }
    val active: Boolean get() = synchronized(this) { link != null }
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
        if (!wanted) {
            if (!listening) friends.value = emptyMap()
            presenceJob?.cancel(); presenceJob = null
            watchers.clear(); watched.value = false
            return
        }
        val l = link ?: sync.liveChannel()?.also { ch ->
            ch.onLive = { onFriend(it) }
            ch.onPresence = { key -> watchers[key] = System.currentTimeMillis(); updateWatched() }
            link = ch; linkCode = prefs.groupCode
        } ?: return
        l.start(listen = listening)
        l.setListening(listening)
        // While the map is on screen: "I am looking" every 30 s (first one as soon as a relay is connected).
        // Also re-checks who is still looking.
        if (presenceJob?.isActive != true) presenceJob = App.scope.launch(Dispatchers.IO) {
            var sentOnce = false
            while (true) {
                val ch = synchronized(this@LiveLink) { link } ?: break
                if (listening) {
                    val n = runCatching { ch.sendPresence() }.getOrDefault(0)
                    if (n > 0) sentOnce = true
                }
                updateWatched()
                kotlinx.coroutines.delay(if (listening && !sentOnce) 3_000 else PRESENCE_MS)
                if (!listening) sentOnce = false
            }
        }
    }

    private fun updateWatched() {
        val now = System.currentTimeMillis()
        watchers.entries.removeAll { now - it.value > WATCH_MS }
        watched.value = watchers.isNotEmpty()
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
        updateWatched()
        val looking = watched.value
        val due = when {
            moved > 2.0 || hasItems -> if (looking) INTERVAL_MS else UNWATCHED_INTERVAL_MS
            else -> if (looking) STANDING_MS else UNWATCHED_STANDING_MS
        }
        if (now - lastSentAt < due) return
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.8f) lastHeading = loc.bearing.toDouble()
        val items = synchronized(pending) {
            var size = 0
            val take = pending.takeWhile { size += it.toJson().toString().length; size <= NostrLive.MAX_ITEMS_JSON }
            pending.subList(0, take.size).clear()
            take
        }
        lastSentAt = now; lastSentPos = pos
        val n = runCatching { l.send(prefs.nickname, pos.lat, pos.lon, lastHeading, if (loc.hasSpeed()) loc.speed.toDouble() else null, items,
            (App.manualMode.value ?: App.tracking.value.mode).name) }.getOrDefault(0)
        if (n == 0 && items.isNotEmpty()) synchronized(pending) { pending.addAll(0, items) }   // not connected yet: try again
    }

    private fun onFriend(live: NostrLive.Live) {
        val key = live.pubkey
        friends.update { old ->
            val prev = old[key]
            val heading = live.heading ?: prev?.heading
            (old.filterValues { System.currentTimeMillis() - it.atMs < STALE_MS } +
                (key to LiveFriend(key, live.name, LatLon(live.lat, live.lon), heading, live.speed, System.currentTimeMillis(),
                    live.mode?.let { m -> runCatching { de.foggymap.app.core.TravelMode.valueOf(m) }.getOrNull() })))
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
        /** Nobody has the map open: the radio may sleep in between. */
        const val UNWATCHED_INTERVAL_MS = 20_000L
        const val UNWATCHED_STANDING_MS = 60_000L
        const val PRESENCE_MS = 30_000L
        /** A "looking" sign counts this long (two missed signs and the sender slows down again). */
        const val WATCH_MS = 75_000L
        /** A friend not heard of for this long disappears from the live view. */
        const val STALE_MS = 120_000L
    }
}
