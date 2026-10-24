package de.foggymap.app

import android.app.Application
import android.content.Context
import android.location.Location
import de.foggymap.app.core.CellKey
import de.foggymap.app.core.LatLon
import de.foggymap.app.core.TravelMode
import de.foggymap.app.data.Prefs
import de.foggymap.app.data.RevealDatabase
import de.foggymap.app.data.SyncManager
import de.foggymap.app.data.TileRepository
import de.foggymap.app.data.Updater
import de.foggymap.app.core.Geo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FoggyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        App.init(this)
    }
}

/** Live state of the exploration service, shown by the UI and the notification. */
data class TrackingUi(
    val active: Boolean = false,
    val accuracy: Float? = null,
    val mode: TravelMode = TravelMode.STILL,
    val powerProfile: String = "",
    val street: String? = null,
    val message: String? = null,
    val loadingMap: Boolean = false,
    val sessionSegments: Int = 0,
    val sessionMeters: Double = 0.0,
    /** Name of the most recently revealed street (for the "new section" banner). */
    val lastRevealed: String? = null,
    val queuedFixes: Int = 0,
)

data class Coverage(val exploredMeters: Double, val totalMeters: Double) {
    val percent: Double get() = if (totalMeters <= 0) 0.0 else (exploredMeters / totalMeters * 100).coerceIn(0.0, 100.0)
}

/** Tiny service locator – the app is small enough not to need a DI framework. */
object App {
    lateinit var context: Context; private set
    lateinit var db: RevealDatabase; private set
    lateinit var prefs: Prefs; private set
    lateinit var tiles: TileRepository; private set
    lateinit var sync: SyncManager; private set
    lateinit var updater: Updater; private set
    lateinit var live: de.foggymap.app.data.LiveLink; private set
    lateinit var badges: de.foggymap.app.data.BadgeRepository; private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Incremented whenever reveals were stored – the map loads the new rows. */
    val dbVersion = MutableStateFlow(0L)
    /** Road ids that arrived from the group (the running explorer must not reveal them again). */
    val remoteRoads = MutableSharedFlow<List<String>>(extraBufferCapacity = 32)
    val tracking = MutableStateFlow(TrackingUi())
    val location = MutableStateFlow<Location?>(null)
    /** Way of moving chosen by hand (null = automatic). */
    val manualMode = MutableStateFlow<de.foggymap.app.core.TravelMode?>(null)
    private val displayFilter = de.foggymap.app.core.PositionFilter()

    /** Your position for the map: steadied while you stand (see PositionFilter), immediate while you move. */
    fun showLocation(loc: Location) {
        val e = synchronized(displayFilter) {
            displayFilter.update(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toDouble() else 30.0, loc.time,
                if (loc.hasSpeed()) loc.speed.toDouble() else null)
        }
        location.value = Location(loc).apply { latitude = e.lat; longitude = e.lon; accuracy = e.accuracy.toFloat() }
    }
    val coverage = MutableStateFlow<Coverage?>(null)
    /**
     * Your recent path as a light trail (drawn immediately, before any map data is needed) –
     * the newest point last. Only on this phone, never uploaded.
     */
    val trail = MutableStateFlow<List<LatLon>>(emptyList())

    fun init(app: Application) {
        context = app
        de.foggymap.app.data.Diagnostics.install(app)
        db = RevealDatabase(app)
        prefs = Prefs(app)
        tiles = TileRepository(app)
        sync = SyncManager(app, db, prefs)
        updater = Updater(app)
        live = de.foggymap.app.data.LiveLink(sync, db, prefs)
        badges = de.foggymap.app.data.BadgeRepository(app, prefs)
        manualMode.value = runCatching { de.foggymap.app.core.TravelMode.valueOf(prefs.manualMode) }.getOrNull()
        sync.init()
        de.foggymap.app.data.UpdateCheckWorker.schedule(app)
        scope.launch { refreshCoverage() }
    }

    fun notifyDbChanged(remoteRoads: List<String> = emptyList()) {
        dbVersion.update { it + 1 }
        if (remoteRoads.isNotEmpty()) this.remoteRoads.tryEmit(remoteRoads)
        scope.launch { refreshCoverage() }
    }

    private var areaJob: Job? = null

    /**
     * Downloads the map data of the surroundings in the background, once per area: on Wi-Fi the
     * whole city (~8 km), on mobile data the district (~3 km). [radius] forces a manual download.
     */
    fun ensureArea(p: LatLon, radius: Double? = null) {
        if (areaJob?.isActive == true) return
        val r = radius ?: if (tiles.isUnmetered()) TileRepository.AUTO_RADIUS_WIFI else TileRepository.AUTO_RADIUS_MOBILE
        if (radius == null && !prefs.areaLat.isNaN()) {
            val d = Geo.distance(LatLon(prefs.areaLat, prefs.areaLon), p)
            val fresh = System.currentTimeMillis() - prefs.areaAt < 60L * 24 * 3600 * 1000
            if (fresh && d + 1500 < prefs.areaRadius && prefs.areaRadius >= r) return
        }
        areaJob = scope.launch(Dispatchers.IO) {
            tiles.prefetchArea(p, r)
            if (tiles.prefetch.value.message == null) {
                prefs.areaLat = p.lat; prefs.areaLon = p.lon; prefs.areaRadius = r; prefs.areaAt = System.currentTimeMillis()
            }
        }
    }

    /** Explored share of the street length in all map cells loaded so far ("deine Umgebung"). */
    suspend fun refreshCoverage() = withContext(Dispatchers.IO) {
        val totals = tiles.cellTotals()
        if (totals.isEmpty()) { coverage.value = null; return@withContext }
        val explored = db.roadLengthByCell { lat, lon -> CellKey.of(LatLon(lat, lon)).name }
        var ex = 0.0; var tot = 0.0
        for ((cell, t) in totals) { tot += t; ex += minOf(explored[cell] ?: 0.0, t) }
        coverage.value = Coverage(ex, tot)
    }
}
