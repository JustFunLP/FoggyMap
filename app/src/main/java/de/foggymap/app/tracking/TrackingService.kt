package de.foggymap.app.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.foggymap.app.App
import de.foggymap.app.BuildConfig
import de.foggymap.app.MainActivity
import de.foggymap.app.R
import de.foggymap.app.TrackingUi
import de.foggymap.app.core.BuildingFootprint
import de.foggymap.app.core.BuildingRevealer
import de.foggymap.app.core.CellKey
import de.foggymap.app.core.RevealKind
import de.foggymap.app.core.Explorer
import de.foggymap.app.core.Fix
import de.foggymap.app.core.Geo
import de.foggymap.app.core.LatLon
import de.foggymap.app.core.TravelMode
import de.foggymap.app.data.TileRepository
import de.foggymap.app.data.RevealDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

/**
 * Foreground service that explores while the user moves – also with the screen off.
 * Started only by an explicit tap on "Erkundung starten"; stops via the notification or the app.
 */
class TrackingService : Service() {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val serial = Dispatchers.Default.limitedParallelism(1)   // explorer is single-threaded
    private val scope = CoroutineScope(SupervisorJob() + serial)
    private lateinit var locations: LocationController
    private var explorer: Explorer? = null

    // Fixes wait here until the map cell under them is loaded (offline exploration, replayed later).
    private val queue = ArrayDeque<Fix>()
    private val queueFile by lazy { File(filesDir, "pending_fixes.jsonl") }
    private var queueDirty = false

    private val loading = HashSet<CellKey>()
    private var lastHeading: Double? = null
    private val failures = HashMap<CellKey, Pair<Int, Long>>()   // attempts, retry-at (elapsed ms)
    /** Building footprints of the loaded cells (same tiles as the streets, no extra download). */
    private val cellBuildings = HashMap<CellKey, kotlinx.coroutines.Deferred<List<BuildingFootprint>>>()
    /** "Radius" mode: area around the path, straight from GPS (needs no map data). */
    private val radius = de.foggymap.app.core.RadiusRevealer()
    private val buildingsChecked = HashSet<CellKey>()
    private var areaRequested = false

    // Battery profile state
    private var stillAnchor: LatLon? = null
    private var stillSince = 0L
    /** Places where you often stand still (learned on the phone, never shared). */
    private val restSpots by lazy { de.foggymap.app.core.RestSpots.decode(App.prefs.restSpots) }
    private var lastGoodFixAt = 0L

    // Statistics, flushed periodically
    private val distanceBuffer = HashMap<TravelMode, Double>()
    private var sessionSegments = 0
    private var sessionMeters = 0.0
    private var lastRevealed: String? = null
    private var startedAt = 0L
    /** GPS quality, GPS stages and battery of this tour (numbers only – for Menü → Info and problem reports). */
    private var stats = de.foggymap.app.core.TourStats(0)
    private var lastNotify = 0L
    private var lastLivePush = 0L
    private var lastLocation: Location? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            locations.configure(screenOn = i?.action != Intent.ACTION_SCREEN_OFF)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        locations = LocationController(this) { list -> scope.launch { onLocations(list) } }
        locations.batchingAllowed = !App.prefs.batchingUnreliable
        locations.onSignificantMotion = { scope.launch { wake("Bewegung erkannt") } }
        val pm = getSystemService(PowerManager::class.java)
        locations.configure(screenOn = pm?.isInteractive ?: true)
        ContextCompat.registerReceiver(this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            App.prefs.trackingWanted = false
            shutdown()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SETTINGS_CHANGED) {
            scope.launch { explorer?.settings = App.prefs.revealSettings(); radius.reset() }
            if (!App.prefs.shareLiveLocation) App.scope.launch { App.sync.clearLive() }
            App.live.refresh()
            locations.liveMode = App.prefs.shareLiveLocation && App.sync.configured
            return START_STICKY
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            App.tracking.update { it.copy(active = false, message = "Standortberechtigung fehlt") }
            stopSelf(); return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Erkundung wird gestartet …", null),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } catch (e: Exception) {
            // Android 12+ forbids starting a location service from the background (e.g. a sticky restart).
            App.tracking.update { it.copy(active = false, message = "Erkundung unterbrochen – in der App fortsetzen") }
            stopSelf(); return START_NOT_STICKY
        }
        if (startedAt == 0L) start()
        return START_STICKY
    }

    private fun start() {
        startedAt = SystemClock.elapsedRealtime()
        stats = de.foggymap.app.core.TourStats(System.currentTimeMillis()).also { it.stage("MOVING", System.currentTimeMillis()) }
        sampleBattery()
        App.prefs.trackingWanted = true
        App.tracking.update { TrackingUi(active = true) }   // the GPS chip already shows "GPS …"
        App.trail.value = emptyList()
        scope.launch {
            val known = withContext(Dispatchers.IO) { App.db.roadIds() }
            explorer = Explorer(known).also { it.settings = App.prefs.revealSettings() }
            if (BuildConfig.DEBUG) explorer?.matcher?.debug = { android.util.Log.d("FoggyMatcher", it) }
            restoreQueue()
            locations.lastKnown()?.let { last -> prefetch(LatLon(last.latitude, last.longitude)) }
            queue.peekLast()?.let { publishTrail(it.pos, force = true) }
        }
        locations.liveMode = App.prefs.shareLiveLocation && App.sync.configured
        App.live.setSending(true)
        locations.apply(LocationController.Profile.MOVING)
        // Know where you are right away – no need to take a few steps first.
        locations.pingNow { loc -> scope.launch { onLocations(listOf(loc)) } }
        lastGoodFixAt = SystemClock.elapsedRealtime()
        scope.launch { App.remoteRoads.collect { ids -> explorer?.markKnown(ids) } }
        scope.launch { housekeeping() }
    }

    // ------------------------------------------------------------------ location handling

    private suspend fun onLocations(list: List<Location>) {
        val ex = explorer ?: return
        for (loc in list.sortedBy { it.time }) {
            if (isMock(loc) && !BuildConfig.DEBUG) continue
            val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
            if (ageMs > 15 * 60_000) continue
            lastLocation = loc
            App.showLocation(loc)
            App.live.onPosition(loc)
            if (loc.hasAccuracy()) stats.addFix(loc.accuracy)
            if (loc.hasAccuracy() && loc.accuracy <= 35f) lastGoodFixAt = SystemClock.elapsedRealtime()
            val fix = Fix(loc.latitude, loc.longitude, if (loc.hasAccuracy() && loc.accuracy > 0f) loc.accuracy.toDouble() else 25.0, loc.time,
                if (loc.hasSpeed()) loc.speed.toDouble() else null,
                if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1f) loc.bearing.toDouble() else null)
            enqueue(fix)
            if (fix.accuracy <= 35.0) publishTrail(fix.pos)
            if (App.prefs.revealMode == de.foggymap.app.core.RevealMode.RADIUS) revealRadius(fix)
            fix.bearing?.let { lastHeading = it }
            updateProfile(fix, loc)
        }
        drain(ex)
        App.tracking.update {
            it.copy(accuracy = lastLocation?.accuracy, queuedFixes = queue.size,
                message = if (it.message == SEARCHING) null else it.message)
        }
        maybeNotify()
        maybePushLive()
    }

    private fun enqueue(fix: Fix) {
        queue.addLast(fix)
        while (queue.size > MAX_QUEUE) queue.removeFirst()
        queueDirty = true
    }

    /** Process queued fixes strictly in time order while their map cell is available. */
    private suspend fun drain(ex: Explorer) {
        while (queue.isNotEmpty()) {
            val head = queue.peekFirst()!!
            val cell = CellKey.of(head.pos)
            if (!ex.hasCell(cell)) {
                requestCell(cell)
                App.tracking.update { it.copy(loadingMap = true) }
                return
            }
            queue.removeFirst(); queueDirty = true
            ex.missingCells(head.pos, head.bearing ?: lastHeading).forEach { requestCell(it) }   // prefetch the cell ahead
            val step = ex.onFix(head)
            if (step.result.accepted && step.result.countedDistance > 0) {
                sessionMeters += step.result.countedDistance
                val m = step.result.mode.let { if (it == TravelMode.STILL) TravelMode.WALK else it }
                distanceBuffer[m] = (distanceBuffer[m] ?: 0.0) + step.result.countedDistance
            }
            if (step.items.isNotEmpty()) {
                val added = withContext(Dispatchers.IO) { App.db.insert(step.items, RevealDatabase.OWNER_ME, synced = false) }
                App.live.queueItems(added)
                if (added.isNotEmpty()) revealBuildingsFor(added)
                if (added.isNotEmpty()) {
                    android.util.Log.i("FoggyMap", "revealed ${step.segments.joinToString { it.name ?: it.id }}: ${added.size} new items")
                    sessionSegments += step.segments.size
                    lastRevealed = step.segments.firstNotNullOfOrNull { it.name } ?: "Weg ohne Namen"
                    App.notifyDbChanged()
                    App.sync.requestSync()
                }
            }
            App.tracking.update {
                it.copy(mode = step.result.mode, street = step.result.current?.name ?: it.street,
                    sessionSegments = sessionSegments, sessionMeters = sessionMeters, lastRevealed = lastRevealed, loadingMap = false,
                    message = if (step.result.accepted) null else it.message)
            }
        }
    }

    private fun requestCell(cell: CellKey) {
        if (cell in loading) return
        val (attempts, retryAt) = failures[cell] ?: (0 to 0L)
        if (SystemClock.elapsedRealtime() < retryAt) return
        loading += cell
        App.scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val result = App.tiles.load(cell)
            // Street corridors revealed earlier under other ids (older app version / friends) – read before switching threads.
            val oldRoads = if (result is TileRepository.Result.Ok) withContext(Dispatchers.IO) { App.db.roadsIntersecting(cell.queryBox()) } else emptyList()
            withContext(serial) {
                loading -= cell
                when (result) {
                    is TileRepository.Result.Ok -> {
                        val model = result.value.model
                        failures.remove(cell)
                        val ex = explorer
                        if (ex != null) {
                            ex.addCell(cell, model)
                            val covered = ex.markCovered(model, oldRoads)
                            cellBuildings[cell] = result.value.buildings
                            cellBuildings.keys.retainAll(ex.loadedCells())
                            android.util.Log.i("FoggyMap", "cell ${cell.name}: ${model.segments.size} segments, ${model.faces.size} blocks, " +
                                "$covered known from before, " +
                                "${SystemClock.elapsedRealtime() - t0} ms (cache=${result.fromCache})")
                        }
                        App.scope.launch { App.refreshCoverage() }
                        if (buildingsChecked.add(cell)) completeBuildings(cell, result.value)
                        // Only now the surroundings are fetched in the background – never competing with the cell you need.
                        if (!areaRequested) { areaRequested = true; App.ensureArea(cell.core().center) }
                        explorer?.let { drain(it) }
                    }
                    is TileRepository.Result.Failed -> {
                        android.util.Log.w("FoggyMap", "cell ${cell.name} failed: ${result.message}")
                        val wait = (5_000L shl attempts.coerceAtMost(5)).coerceAtMost(2 * 60_000L)
                        failures[cell] = (attempts + 1) to SystemClock.elapsedRealtime() + wait
                        App.tracking.update {
                            it.copy(loadingMap = false, message = if (result.offline)
                                "Offline – dein Weg wird gespeichert und aufgedeckt, sobald Netz da ist"
                            else "Kartendaten: ${result.message}")
                        }
                    }
                }
            }
        }
    }

    private fun prefetch(p: LatLon) { CellKey.needed(p).forEach { requestCell(it) } }

    // ------------------------------------------------------------------ buildings

    /** New areas were revealed: the buildings inside them appear right away (same data as the streets). */
    private suspend fun revealBuildingsFor(newItems: List<de.foggymap.app.core.RevealItem>) {
        val areas = newItems.filter { it.kind != RevealKind.BUILDING }
        val box = BuildingRevealer.bboxOf(areas) ?: return
        val sources = cellBuildings.values.toList()
        // Off the explorer thread: footprints of a just loaded cell may still be in the making.
        App.scope.launch(Dispatchers.Default) {
            val seen = HashSet<Long>()
            val candidates = sources.flatMap { it.await() }.filter { b -> b.ring.any { box.contains(it) } && seen.add(b.id) }
            if (candidates.isEmpty()) return@launch
            val existing = App.db.buildingsIntersecting(box)
            val items = BuildingRevealer.reveal(candidates, areas, existing)
            withContext(serial) { storeBuildings(items) }
        }
    }

    /** "Radius" mode: reveal the circle around you right away – no map data needed. */
    private suspend fun revealRadius(fix: Fix) {
        val items = radius.onFix(fix, App.prefs.revealRadius.toDouble())
        if (items.isEmpty()) return
        val added = withContext(Dispatchers.IO) { App.db.insert(items, RevealDatabase.OWNER_ME, synced = false) }
        if (added.isEmpty()) return
        App.live.queueItems(added)
        App.notifyDbChanged(); App.sync.requestSync()
        revealBuildingsFor(added)
    }

    /**
     * When a cell's data arrives: buildings for ground that was revealed before the data was there
     * (radius mode while loading, offline walks, areas from the group).
     */
    private fun completeBuildings(cell: CellKey, data: TileRepository.Cell) {
        App.scope.launch(Dispatchers.Default) {
            val buildings = data.buildings.await()
            val box = cell.core()
            val areas = App.db.areasIntersecting(box)
            if (areas.isEmpty() || buildings.isEmpty()) return@launch
            val existing = App.db.buildingsIntersecting(cell.queryBox())
            val items = BuildingRevealer.reveal(buildings.filter { b -> b.ring.any { box.contains(it) } }, areas, existing)
            withContext(serial) { storeBuildings(items) }
        }
    }

    /** Light trail of your own path: shown at once, also while map data is still on its way. */
    private fun publishTrail(p: LatLon, force: Boolean = false) {
        val cur = App.trail.value
        val last = cur.lastOrNull()
        if (!force && last != null && Geo.distance(last, p) < 3.0) return
        App.trail.value = if (cur.size >= TRAIL_POINTS) cur.drop(cur.size - TRAIL_POINTS + 1) + p else cur + p
    }

    private suspend fun storeBuildings(items: List<de.foggymap.app.core.RevealItem>): Int {
        if (items.isEmpty()) return 0
        val added = withContext(Dispatchers.IO) { App.db.insert(items, RevealDatabase.OWNER_ME, synced = false) }
        if (added.isNotEmpty()) { App.live.queueItems(added); App.notifyDbChanged(); App.sync.requestSync() }
        return added.size
    }

    // ------------------------------------------------------------------ battery profile

    /**
     * The GPS rate follows how you move – there are no settings:
     * moving → full rate, ~30 s standing → throttled, ~2.5 min standing → GPS pause.
     * At a place where you have often stood before (home, office) the pause comes after ~40 s.
     */
    private fun updateProfile(fix: Fix, @Suppress("UNUSED_PARAMETER") loc: Location) {
        val now = SystemClock.elapsedRealtime()
        val speed = fix.speed ?: 0.0
        val current = locations.current
        val anchor = stillAnchor
        val moved = anchor?.let { Geo.distance(it, fix.pos) } ?: Double.MAX_VALUE
        if (moved > maxOf(30.0, fix.accuracy) || speed > 1.5 || (speed > 0.9 && moved > 10.0 && fix.accuracy <= 20.0)) {
            stillAnchor = fix.pos; stillSince = now
            if (current == LocationController.Profile.STILL || current == LocationController.Profile.SLOW) wake("Bewegung")
        }
        val stillFor = now - stillSince
        val knownPlace = fix.accuracy <= 50.0 && restSpots.isKnown(stillAnchor ?: fix.pos)
        val target = when {
            current == LocationController.Profile.STILL -> LocationController.Profile.STILL
            stillFor > (if (knownPlace) STILL_AT_KNOWN_PLACE_MS else STILL_AFTER_MS) -> LocationController.Profile.STILL
            stillFor > SLOW_AFTER_MS -> LocationController.Profile.SLOW
            speed > 7.0 -> LocationController.Profile.FAST
            current == LocationController.Profile.FAST && speed >= 5.0 -> LocationController.Profile.FAST
            else -> LocationController.Profile.MOVING
        }
        if (target == LocationController.Profile.STILL && current != LocationController.Profile.STILL) {
            locations.atKnownPlace = knownPlace
            if (fix.accuracy <= 50.0) {
                restSpots.record(stillAnchor ?: fix.pos, System.currentTimeMillis())
                App.prefs.restSpots = restSpots.encode()
            }
        }
        if (target != current) setProfile(target)
    }

    private fun setProfile(p: LocationController.Profile) {
        android.util.Log.i("FoggyMap", "gps profile -> $p" + if (p == LocationController.Profile.STILL && locations.atKnownPlace) " (known place)" else "")
        locations.apply(p)
        stats.stage(p.name, System.currentTimeMillis())
        App.tracking.update { it.copy(powerProfile = p.label) }
    }

    /** Battery reading (whole phone; only unplugged time counts) and fresh tour numbers for the UI. */
    private fun sampleBattery() {
        runCatching {
            val bm = getSystemService(android.os.BatteryManager::class.java)
            val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 1..100 }
            if (level != null) {
                val charge = bm.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).let { if (it > 0 && it != Long.MIN_VALUE) it else 0L }
                val sticky = ContextCompat.registerReceiver(this, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
                val plugged = (sticky?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                val screen = getSystemService(PowerManager::class.java)?.isInteractive ?: true
                val live = App.prefs.shareLiveLocation && App.sync.configured
                stats.addBattery(de.foggymap.app.core.TourStats.BatterySample(System.currentTimeMillis(), level.toDouble(), charge, plugged, screen, live))
                    ?.let { (pct, hours, l) -> App.prefs.addBattery(pct, hours, l) }
            }
            de.foggymap.app.data.Diagnostics.tour.value = tourSummary()
        }
    }

    private fun tourSummary() = stats.summary(System.currentTimeMillis(), sessionSegments, sessionMeters).apply {
        stats.drain.perHour?.let { put("rate", Math.round(it * 10) / 10.0) }
        stats.drain.livePerHour?.let { put("liveRate", Math.round(it * 10) / 10.0) }
    }

    private fun wake(@Suppress("UNUSED_PARAMETER") reason: String) {
        if (locations.current == LocationController.Profile.STILL || locations.current == LocationController.Profile.SLOW) {
            stillSince = SystemClock.elapsedRealtime()
            lastGoodFixAt = SystemClock.elapsedRealtime()
            setProfile(LocationController.Profile.MOVING)
        }
    }

    // ------------------------------------------------------------------ periodic work

    private suspend fun housekeeping() {
        var tick = 0
        while (scope.isActive) {
            delay(15_000)
            tick++
            val now = SystemClock.elapsedRealtime()
            // No usable GPS for 3 min (indoors/tunnel): stop searching, wait for movement.
            if (locations.checkBatchingStall()) {
                android.util.Log.w("FoggyMap", "GPS batching delivered nothing – disabled on this device")
                App.prefs.batchingUnreliable = true
                lastGoodFixAt = now
            }
            if (locations.current != LocationController.Profile.STILL && now - lastGoodFixAt > NO_SIGNAL_MS) {
                locations.atKnownPlace = false
                setProfile(LocationController.Profile.STILL)
            }
            if (tick % 2 == 0) flushStats()
            if (tick % 4 == 0) sampleBattery()                     // every minute
            if (tick % 2 == 0 && queueDirty) persistQueue()
            if (tick % 12 == 0) App.sync.requestSync(0)            // every 3 minutes
            explorer?.let { ex -> if (queue.isNotEmpty()) drain(ex) }
            maybeNotify(force = tick % 4 == 0)
        }
    }

    private fun flushStats() {
        if (distanceBuffer.isNotEmpty()) { App.prefs.addDistances(distanceBuffer.toMap()); distanceBuffer.clear() }
        val now = SystemClock.elapsedRealtime()
        App.prefs.trackedSeconds = App.prefs.trackedSeconds + (now - startedAt) / 1000
        startedAt = now
        lastLocation?.let { App.prefs.lastLat = it.latitude; App.prefs.lastLon = it.longitude }
    }

    private fun maybePushLive() {
        if (!App.prefs.shareLiveLocation || !App.sync.configured) return
        val loc = lastLocation ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLivePush < 60_000) return
        lastLivePush = now
        App.scope.launch { App.sync.pushLive(loc.latitude, loc.longitude) }
    }

    // ------------------------------------------------------------------ offline queue persistence

    private suspend fun persistQueue() = withContext(Dispatchers.IO) {
        val snapshot = ArrayList(queue)
        queueDirty = false
        val tmp = File(filesDir, "pending_fixes.tmp")
        tmp.bufferedWriter().use { w ->
            for (f in snapshot) {
                w.write(JSONObject().put("a", f.lat).put("o", f.lon).put("c", f.accuracy).put("t", f.timeMs)
                    .apply { f.speed?.let { put("s", it) }; f.bearing?.let { put("b", it) } }.toString())
                w.newLine()
            }
        }
        tmp.renameTo(queueFile)
    }

    private suspend fun restoreQueue() = withContext(Dispatchers.IO) {
        if (!queueFile.exists()) return@withContext
        val restored = queueFile.readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                Fix(o.getDouble("a"), o.getDouble("o"), o.getDouble("c"), o.getLong("t"),
                    if (o.has("s")) o.getDouble("s") else null, if (o.has("b")) o.getDouble("b") else null)
            }.getOrNull()
        }
        withContext(serial) { restored.forEach { queue.addLast(it) } }
    }

    // ------------------------------------------------------------------ notification

    private fun maybeNotify(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 10_000) return
        lastNotify = now
        val t = App.tracking.value
        val text = buildString {
            append(if (sessionSegments == 1) "1 neuer Abschnitt" else "$sessionSegments neue Abschnitte")
            t.accuracy?.let { append(" · ±${it.toInt()} m") }
            if (queue.size > 20) append(" · ${queue.size} Punkte warten auf Karte")
        }
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(text, t.powerProfile.ifEmpty { null })) }
    }

    private fun buildNotification(text: String, sub: String?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_fog)
            .setContentTitle("FoggyMap erkundet")
            .setContentText(text)
            .setSubText(sub)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setColor(0xFF8B5CF6.toInt())
            .addAction(0, "Beenden", stop)
            .build()
    }

    // ------------------------------------------------------------------ teardown

    private fun shutdown() {
        sampleBattery()
        App.prefs.lastTour = tourSummary().toString()
        de.foggymap.app.data.Diagnostics.tour.value = null
        locations.stop()
        App.live.setSending(false)
        App.tracking.update { it.copy(active = false, message = null, loadingMap = false) }
        if (App.prefs.shareLiveLocation) App.scope.launch { App.sync.clearLive() }
        App.scope.launch {
            withContext(serial) { flushStats(); persistQueue() }
            App.sync.requestSync(0)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        locations.stop()
        runCatching { unregisterReceiver(screenReceiver) }
        App.tracking.update { it.copy(active = false, loadingMap = false) }
        val pending = ArrayList(queue)
        App.scope.launch(Dispatchers.IO) {
            // Persist what is left so nothing walked is lost.
            if (pending.isNotEmpty()) runCatching {
                queueFile.bufferedWriter().use { w ->
                    pending.forEach { f ->
                        w.write(JSONObject().put("a", f.lat).put("o", f.lon).put("c", f.accuracy).put("t", f.timeMs).toString()); w.newLine()
                    }
                }
            } else queueFile.delete()
        }
        if (distanceBuffer.isNotEmpty()) App.prefs.addDistances(distanceBuffer.toMap())
        scope.cancel()
        super.onDestroy()
    }

    private fun isMock(loc: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) loc.isMock else @Suppress("DEPRECATION") loc.isFromMockProvider

    companion object {
        const val CHANNEL = "exploration"
        const val SEARCHING = "Suche GPS-Signal …"
        const val NOTIFICATION_ID = 17
        const val ACTION_STOP = "de.foggymap.app.STOP"
        const val ACTION_SETTINGS_CHANGED = "de.foggymap.app.SETTINGS"
        const val MAX_QUEUE = 20_000
        const val TRAIL_POINTS = 900
        const val STILL_AFTER_MS = 150_000L
        const val STILL_AT_KNOWN_PLACE_MS = 40_000L
        const val SLOW_AFTER_MS = 30_000L
        const val NO_SIGNAL_MS = 180_000L

        fun createChannel(context: Context) {
            val ch = NotificationChannel(CHANNEL, "Erkundung", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Zeigt an, dass FoggyMap deinen Weg aufzeichnet"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }

        fun settingsChanged(context: Context) {
            if (App.tracking.value.active) context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_SETTINGS_CHANGED))
        }
    }
}

