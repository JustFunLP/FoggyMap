package de.foggymap.app.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Battery-aware location subscription. There are no settings: the rate follows how you move.
 *
 * Like Google Maps and Waze, positions come from Google's *fused location provider* (Play services):
 * satellites combined with Wi-Fi, cell and motion sensors, with its own power management and reliable
 * batching. Phones without Play services use Android's own location service the same way.
 *
 *  - Moving: on foot every 3 s, by car every 2 s.
 *  - Standing: the phone's activity recognition ([Motion], low-power sensor chip) reports "still" and
 *    the GPS shows no movement for ~20 s after that → the GPS is switched OFF. Without activity
 *    recognition: throttled after ~30 s, off after ~2.5 min (~40 s at a place where you often stand,
 *    [de.foggymap.app.core.RestSpots]). While off only free sources are used (positions other apps request
 *    anyway; without activity recognition also a cheap Wi-Fi/cell fix). Activity recognition and the
 *    hardware "significant motion" sensor wake GPS as soon as you move off; a short single GPS fix every
 *    4 min (1.5 min without activity recognition) is the safety net for rides both miss.
 *  - No GPS signal for minutes (indoors, underground) → also off instead of letting the receiver search.
 *  - Screen off and nobody watching you live → positions are *batched*: the phone's main processor wakes
 *    only every 20–30 s for a whole batch (every fix keeps its own time, matching works the same).
 */
class LocationController(private val context: Context, private val onLocations: (List<Location>) -> Unit) {
    enum class Profile(val label: String) {
        FAST("GPS aktiv (Fahrt)"), MOVING("GPS aktiv"), SLOW("GPS gedrosselt (kurzer Halt)"), STILL("GPS aus (du stehst)")
    }

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var profile: Profile? = null
    private var screenOn = true
    /** Paused at a place where you often stand: check less often. */
    var atKnownPlace = false
    /** Activity recognition is running: no GPS probes needed while standing. */
    var motionAware = false
    /**
     * Live position shared with a group member who is looking right now: no batching, so they see you
     * move every 2–3 s also with your screen off.
     */
    var liveMode = false
        set(v) { if (field != v) { field = v; profile?.let { apply(it, force = true) } } }
    var onSignificantMotion: (() -> Unit)? = null

    /** Hardware batching is not reliable on every device (and not at all on the emulator). */
    var batchingAllowed = true
    private var batchDelayMs = 0L
    private var lastDelivery = android.os.SystemClock.elapsedRealtime()

    private fun deliver(list: List<Location>) {
        if (list.isEmpty()) return
        lastDelivery = android.os.SystemClock.elapsedRealtime()
        onLocations(list)
    }

    /** Where positions come from: Google's fused provider when available, otherwise Android's LocationManager. */
    private interface Engine {
        val name: String
        fun request(p: Params, batchMs: Long): Boolean
        fun remove()
        /** Positions other apps request anyway (costs nothing). */
        fun passive(on: Boolean)
        /** One single satellite fix (used while standing without activity recognition). */
        fun probe(onFix: (Location) -> Unit)
        fun cancelProbe()
    }

    private val engine: Engine = if (Motion.playServices(context)) GmsEngine() else PlatformEngine()
    val engineName get() = engine.name

    @SuppressLint("MissingPermission")
    private inner class GmsEngine : Engine {
        override val name = "Google Fused Location"
        private val client = LocationServices.getFusedLocationProviderClient(context)
        private var active = false
        private var passiveOn = false
        private val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) = deliver(result.locations)
        }
        private val passiveCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) = onLocations(result.locations)
        }
        private var probeToken: com.google.android.gms.tasks.CancellationTokenSource? = null

        override fun request(p: Params, batchMs: Long): Boolean = try {
            val req = LocationRequest.Builder(p.priority, p.interval)
                .setMinUpdateIntervalMillis(p.interval / 2)
                .setMinUpdateDistanceMeters(p.minDistance)
                .setMaxUpdateDelayMillis(batchMs)
                .setWaitForAccurateLocation(false)
                .build()
            client.requestLocationUpdates(req, callback, Looper.getMainLooper())
            active = true; true
        } catch (_: SecurityException) { false } catch (_: Exception) { false }

        override fun remove() { if (active) runCatching { client.removeLocationUpdates(callback) }; active = false }

        override fun passive(on: Boolean) {
            if (on == passiveOn) return
            passiveOn = on
            try {
                if (on) client.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 10_000)
                    .setMinUpdateDistanceMeters(10f).build(), passiveCallback, Looper.getMainLooper())
                else client.removeLocationUpdates(passiveCallback)
            } catch (_: Exception) { passiveOn = false }
        }

        override fun probe(onFix: (Location) -> Unit) {
            cancelProbe()
            val token = com.google.android.gms.tasks.CancellationTokenSource().also { probeToken = it }
            try {
                client.getCurrentLocation(CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setDurationMillis(25_000).setMaxUpdateAgeMillis(5_000).build(), token.token)
                    .addOnSuccessListener { loc -> if (loc != null) onFix(loc) }
            } catch (_: Exception) { }
        }

        override fun cancelProbe() { probeToken?.cancel(); probeToken = null }
    }

    @SuppressLint("MissingPermission")
    private inner class PlatformEngine : Engine {
        override val name = "Android-Standort"
        private val lm = context.getSystemService(LocationManager::class.java)
        private var active = false
        private var passiveOn = false
        private var probeSignal: android.os.CancellationSignal? = null
        private val listener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) = deliver(listOf(location))
            override fun onLocationChanged(locations: MutableList<Location>) = deliver(locations.toList())
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        private val passiveListener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) = onLocations(listOf(location))
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        override fun request(p: Params, batchMs: Long): Boolean {
            val quality = when (p.priority) {
                Priority.PRIORITY_HIGH_ACCURACY -> LocationRequestCompat.QUALITY_HIGH_ACCURACY
                Priority.PRIORITY_BALANCED_POWER_ACCURACY -> LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
                else -> LocationRequestCompat.QUALITY_LOW_POWER
            }
            val request = LocationRequestCompat.Builder(p.interval).setQuality(quality).setMinUpdateDistanceMeters(p.minDistance)
                .setMinUpdateIntervalMillis(p.interval / 2).setMaxUpdateDelayMillis(batchMs).build()
            val provider = providerFor(p.priority != Priority.PRIORITY_HIGH_ACCURACY)
            return try {
                LocationManagerCompat.requestLocationUpdates(lm, provider, request, ContextCompat.getMainExecutor(context), listener)
                active = true; true
            } catch (_: SecurityException) { false } catch (_: IllegalArgumentException) {
                try {
                    LocationManagerCompat.requestLocationUpdates(lm, LocationManager.GPS_PROVIDER, request, ContextCompat.getMainExecutor(context), listener)
                    active = true; true
                } catch (_: Exception) { false }
            }
        }

        override fun remove() { if (active) runCatching { LocationManagerCompat.removeUpdates(lm, listener) }; active = false }

        override fun passive(on: Boolean) {
            if (on == passiveOn) return
            passiveOn = on
            try {
                if (on) LocationManagerCompat.requestLocationUpdates(lm, LocationManager.PASSIVE_PROVIDER,
                    LocationRequestCompat.Builder(10_000).setMinUpdateDistanceMeters(10f).build(), ContextCompat.getMainExecutor(context), passiveListener)
                else LocationManagerCompat.removeUpdates(lm, passiveListener)
            } catch (_: Exception) { passiveOn = false }
        }

        override fun probe(onFix: (Location) -> Unit) {
            cancelProbe()
            val signal = android.os.CancellationSignal().also { probeSignal = it }
            try {
                LocationManagerCompat.getCurrentLocation(lm, LocationManager.GPS_PROVIDER, signal, ContextCompat.getMainExecutor(context),
                    androidx.core.util.Consumer<Location> { loc -> if (loc != null) onFix(loc) })
                main.postDelayed({ signal.cancel() }, 25_000)
            } catch (_: Exception) { }
        }

        override fun cancelProbe() { probeSignal?.cancel(); probeSignal = null }

        private fun providerFor(lowPower: Boolean): String {
            val gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val network = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
            val fused = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)
            return when {
                lowPower && fused -> LocationManager.FUSED_PROVIDER
                lowPower && network -> LocationManager.NETWORK_PROVIDER
                fused && gps -> LocationManager.FUSED_PROVIDER
                gps -> LocationManager.GPS_PROVIDER
                fused -> LocationManager.FUSED_PROVIDER
                else -> LocationManager.GPS_PROVIDER
            }
        }
    }

    private var subscribed = false
    private val probe = object : Runnable {
        override fun run() {
            if (profile != Profile.STILL) return
            engine.probe { loc -> if (profile == Profile.STILL) onLocations(listOf(loc)) }
            main.postDelayed(this, probeInterval())
        }
    }

    /** With activity recognition the probe is only a last safety net (in case a "moving again" report is missed). */
    private fun probeInterval(): Long = when {
        motionAware -> 240_000
        atKnownPlace -> 150_000
        else -> 90_000
    }

    private val motion = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) { main.post { onSignificantMotion?.invoke() } }
    }

    val current get() = profile

    @Synchronized fun configure(screenOn: Boolean) {
        val changed = screenOn != this.screenOn
        this.screenOn = screenOn
        if (changed) profile?.let { apply(it, force = true) }
    }

    @Synchronized fun apply(p: Profile, force: Boolean = false) {
        if (p == profile && !force && subscribed) return
        profile = p
        stopUpdates()
        val params = params(p)
        val batch = if (batchingAllowed && !screenOn && !liveMode && p != Profile.STILL) params.interval * params.batchFactor else 0L
        batchDelayMs = batch
        lastDelivery = android.os.SystemClock.elapsedRealtime()
        // While standing the GPS is OFF: only free sources (other apps' positions) – plus a cheap Wi-Fi/cell
        // fix once a minute when there is no activity recognition to tell that you move off.
        subscribed = if (p == Profile.STILL && motionAware) false else engine.request(params, batch)
        if (p == Profile.STILL || p == Profile.SLOW) armMotionSensor() else disarmMotionSensor()
        if (p == Profile.STILL) {
            engine.passive(true)
            main.removeCallbacks(probe)
            main.postDelayed(probe, probeInterval())
        } else stopStillHelpers()
    }

    private fun stopStillHelpers() {
        main.removeCallbacks(probe)
        engine.cancelProbe()
        engine.passive(false)
    }

    /**
     * Called periodically by the service. If batched delivery produced nothing for far longer than the
     * batch period, the device's batching is broken: switch it off for good and re-subscribe.
     * Returns true when batching was disabled.
     */
    @Synchronized fun checkBatchingStall(): Boolean {
        if (batchDelayMs <= 0 || profile == null || profile == Profile.STILL) return false
        val silent = android.os.SystemClock.elapsedRealtime() - lastDelivery
        if (silent < maxOf(90_000L, batchDelayMs * 3)) return false
        batchingAllowed = false
        profile?.let { apply(it, force = true) }
        return true
    }

    @Synchronized fun stop() {
        stopUpdates()
        stopStillHelpers()
        disarmMotionSensor()
        profile = null
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? = try {
        val lm = context.getSystemService(LocationManager::class.java)
        listOfNotNull(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    } catch (_: SecurityException) { null }

    private fun stopUpdates() {
        if (subscribed) engine.remove()
        subscribed = false
    }

    /** One fresh, accurate position right now (no need to walk first). */
    fun pingNow(onFix: (Location) -> Unit) = currentLocation(context, onFix)

    private data class Params(val interval: Long, val minDistance: Float, val priority: Int, val batchFactor: Long)

    // GPS stays switched on between fixes shorter than ~10 s anyway, so rarer fixes while moving save little
    // energy but leave gaps (short streets passed by car got no fix). Savings come from switching the GPS
    // off while you stand, and from batching (fewer wake-ups of the main processor) with the screen off.
    // No minimum distance while GPS is on: fixes keep coming while you stand, which is how standing still
    // is recognised (and a silent receiver really means "no signal" or broken batching).
    private fun params(p: Profile): Params = when (p) {
        Profile.FAST -> Params(2000, 0f, Priority.PRIORITY_HIGH_ACCURACY, 10)
        Profile.MOVING -> Params(3000, 0f, Priority.PRIORITY_HIGH_ACCURACY, 10)
        Profile.SLOW -> Params(10_000, 0f, Priority.PRIORITY_HIGH_ACCURACY, 3)
        // No satellites: Wi-Fi/cell at most once a minute, and only when you moved 50 m.
        Profile.STILL -> Params(60_000, 50f, Priority.PRIORITY_BALANCED_POWER_ACCURACY, 1)
    }

    private fun armMotionSensor() {
        val s = sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        runCatching { sensors.requestTriggerSensor(motion, s) }
    }

    private fun disarmMotionSensor() {
        val s = sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        runCatching { sensors.cancelTriggerSensor(motion, s) }
    }

    companion object {
        /**
         * Asks once for the current position with high accuracy (satellites + Wi-Fi). Usually answers within a
         * few seconds; a cold GPS can take up to ~30 s. Used when the app opens and when exploring starts.
         */
        @SuppressLint("MissingPermission")
        fun currentLocation(context: Context, onFix: (Location) -> Unit) {
            if (Motion.playServices(context)) {
                try {
                    LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(
                        CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY).setDurationMillis(30_000)
                            .setMaxUpdateAgeMillis(3_000).build(), null)
                        .addOnSuccessListener { loc -> if (loc != null) onFix(loc) }
                    return
                } catch (_: SecurityException) { return } catch (_: Exception) { }
            }
            val lm = context.getSystemService(LocationManager::class.java) ?: return
            val executor = ContextCompat.getMainExecutor(context)
            val signal = android.os.CancellationSignal()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
                    val req = android.location.LocationRequest.Builder(0).setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY)
                        .setDurationMillis(30_000).build()
                    lm.getCurrentLocation(LocationManager.FUSED_PROVIDER, req, signal, executor) { loc -> if (loc != null) onFix(loc) }
                } else {
                    LocationManagerCompat.getCurrentLocation(lm, LocationManager.GPS_PROVIDER, signal, executor,
                        androidx.core.util.Consumer<Location> { loc -> if (loc != null) onFix(loc) })
                }
                Handler(Looper.getMainLooper()).postDelayed({ signal.cancel() }, 35_000)
            } catch (_: SecurityException) { } catch (_: IllegalArgumentException) { }
        }
    }
}
