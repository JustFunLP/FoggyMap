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
import de.foggymap.app.data.BatteryMode

/**
 * Battery-aware location subscription.
 *
 * Where the energy goes and what we do about it:
 *  - GPS while standing still is the biggest waste → after ~2.5 min without movement GPS is switched
 *    off. Then only (a) a low-power Wi-Fi/cell fix, (b) positions other apps request anyway
 *    (passive, free) and (c) a short single GPS fix every 45–90 s are used. The hardware
 *    "significant motion" sensor (practically free) wakes GPS immediately when you walk on;
 *    the GPS samples catch cases the sensor misses (car, bike, phones without that sensor).
 *  - No GPS signal for minutes (indoors, underground) → also drop to low power instead of letting
 *    the receiver search forever.
 *  - Update rate follows the speed: on foot every 3 s / 4 m, by car every 2 s / 10 m.
 *  - Screen off → fixes are *batched* by the location hardware (Android 12+): the phone's main
 *    processor wakes only every 10–30 s to receive a whole batch. Matching works on the batch
 *    because every fix carries its own timestamp.
 */
class LocationController(private val context: Context, private val onLocations: (List<Location>) -> Unit) {
    enum class Profile(val label: String) { FAST("GPS aktiv"), MOVING("GPS aktiv"), STILL("GPS-Pause (Stillstand)") }

    private val lm = context.getSystemService(LocationManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var profile: Profile? = null
    private var mode: BatteryMode = BatteryMode.BALANCED
    private var screenOn = true
    private var subscribed = false
    var onSignificantMotion: (() -> Unit)? = null

    /** Hardware batching is not reliable on every device (and not at all on the emulator). */
    var batchingAllowed = true
    private var batchDelayMs = 0L
    private var lastDelivery = android.os.SystemClock.elapsedRealtime()

    private val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) { lastDelivery = android.os.SystemClock.elapsedRealtime(); onLocations(listOf(location)) }
        override fun onLocationChanged(locations: MutableList<Location>) { lastDelivery = android.os.SystemClock.elapsedRealtime(); onLocations(locations.toList()) }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val passive = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) = onLocations(listOf(location))
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    private var passiveOn = false
    private var probeSignal: android.os.CancellationSignal? = null
    private val probe = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (profile != Profile.STILL) return
            probeSignal?.cancel()
            val signal = android.os.CancellationSignal().also { probeSignal = it }
            try {
                LocationManagerCompat.getCurrentLocation(lm, LocationManager.GPS_PROVIDER, signal,
                    ContextCompat.getMainExecutor(context), androidx.core.util.Consumer<Location> { loc ->
                        if (loc != null && profile == Profile.STILL) onLocations(listOf(loc))
                    })
                main.postDelayed({ signal.cancel() }, 25_000)
            } catch (_: Exception) { }
            main.postDelayed(this, probeInterval())
        }
    }

    private fun probeInterval(): Long = when (mode) {
        BatteryMode.PRECISE -> 20_000
        BatteryMode.BALANCED -> 45_000
        BatteryMode.SAVER -> 90_000
    }

    private val motion = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) { main.post { onSignificantMotion?.invoke() } }
    }

    val current get() = profile

    fun configure(mode: BatteryMode, screenOn: Boolean = this.screenOn) {
        val changed = mode != this.mode || screenOn != this.screenOn
        this.mode = mode; this.screenOn = screenOn
        if (changed) profile?.let { apply(it, force = true) }
    }

    @SuppressLint("MissingPermission")
    fun apply(p: Profile, force: Boolean = false) {
        if (p == profile && !force && subscribed) return
        profile = p
        stopUpdates()
        val params = params(p)
        val batch = if (batchingAllowed && !screenOn && mode != BatteryMode.PRECISE && p != Profile.STILL) params.interval * params.batchFactor else 0L
        batchDelayMs = batch
        lastDelivery = android.os.SystemClock.elapsedRealtime()
        val request = LocationRequestCompat.Builder(params.interval)
            .setQuality(params.quality)
            .setMinUpdateDistanceMeters(params.minDistance)
            .setMinUpdateIntervalMillis(params.interval / 2)
            .setMaxUpdateDelayMillis(batch)
            .build()
        val provider = providerFor(p)
        try {
            LocationManagerCompat.requestLocationUpdates(lm, provider, request, ContextCompat.getMainExecutor(context), listener)
            subscribed = true
        } catch (_: SecurityException) { subscribed = false } catch (_: IllegalArgumentException) {
            // Provider missing on this device – fall back to GPS.
            try {
                LocationManagerCompat.requestLocationUpdates(lm, LocationManager.GPS_PROVIDER, request, ContextCompat.getMainExecutor(context), listener)
                subscribed = true
            } catch (_: Exception) { subscribed = false }
        }
        if (p == Profile.STILL) {
            armMotionSensor()
            startPassive()
            main.removeCallbacks(probe)
            main.postDelayed(probe, probeInterval())
        } else {
            disarmMotionSensor()
            stopStillHelpers()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPassive() {
        if (passiveOn) return
        try {
            LocationManagerCompat.requestLocationUpdates(lm, LocationManager.PASSIVE_PROVIDER,
                LocationRequestCompat.Builder(10_000).setMinUpdateDistanceMeters(10f).build(),
                ContextCompat.getMainExecutor(context), passive)
            passiveOn = true
        } catch (_: Exception) { }
    }

    private fun stopStillHelpers() {
        main.removeCallbacks(probe)
        probeSignal?.cancel(); probeSignal = null
        if (passiveOn) { runCatching { LocationManagerCompat.removeUpdates(lm, passive) }; passiveOn = false }
    }

    /**
     * Called periodically by the service. If batched delivery produced nothing for far longer than the
     * batch period, the device's batching is broken: switch it off for good and re-subscribe.
     * Returns true when batching was disabled.
     */
    fun checkBatchingStall(): Boolean {
        if (batchDelayMs <= 0 || profile == null || profile == Profile.STILL) return false
        val silent = android.os.SystemClock.elapsedRealtime() - lastDelivery
        if (silent < maxOf(60_000L, batchDelayMs * 3)) return false
        batchingAllowed = false
        profile?.let { apply(it, force = true) }
        return true
    }

    fun stop() {
        stopUpdates()
        stopStillHelpers()
        disarmMotionSensor()
        profile = null
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? = try {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    } catch (_: SecurityException) { null }

    private fun stopUpdates() {
        if (subscribed) LocationManagerCompat.removeUpdates(lm, listener)
        subscribed = false
    }

    private fun providerFor(p: Profile): String {
        val gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val network = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        val fused = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)
        return when {
            p == Profile.STILL && fused -> LocationManager.FUSED_PROVIDER
            p == Profile.STILL && network -> LocationManager.NETWORK_PROVIDER
            gps -> LocationManager.GPS_PROVIDER
            fused -> LocationManager.FUSED_PROVIDER
            else -> LocationManager.GPS_PROVIDER
        }
    }

    private data class Params(val interval: Long, val minDistance: Float, val quality: Int, val batchFactor: Long)

    private fun params(p: Profile): Params = when (mode) {
        BatteryMode.PRECISE -> when (p) {
            Profile.FAST -> Params(1000, 5f, LocationRequestCompat.QUALITY_HIGH_ACCURACY, 1)
            Profile.MOVING -> Params(1500, 2f, LocationRequestCompat.QUALITY_HIGH_ACCURACY, 1)
            Profile.STILL -> Params(15_000, 15f, LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY, 1)
        }
        BatteryMode.BALANCED -> when (p) {
            Profile.FAST -> Params(2000, 10f, LocationRequestCompat.QUALITY_HIGH_ACCURACY, 5)
            Profile.MOVING -> Params(3000, 4f, LocationRequestCompat.QUALITY_HIGH_ACCURACY, 5)
            Profile.STILL -> Params(30_000, 30f, LocationRequestCompat.QUALITY_LOW_POWER, 1)
        }
        // GPS stays switched on between fixes shorter than ~10 s anyway, so rarer fixes save little energy
        // but leave gaps (short streets passed by car got no fix). Saver mode saves by bigger batches
        // (fewer wake-ups of the main processor) and longer pauses when standing.
        BatteryMode.SAVER -> when (p) {
            Profile.FAST -> Params(2000, 10f, LocationRequestCompat.QUALITY_HIGH_ACCURACY, 12)
            Profile.MOVING -> Params(3000, 5f, LocationRequestCompat.QUALITY_HIGH_ACCURACY, 10)
            Profile.STILL -> Params(60_000, 50f, LocationRequestCompat.QUALITY_LOW_POWER, 1)
        }
    }

    private fun armMotionSensor() {
        val s = sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        runCatching { sensors.requestTriggerSensor(motion, s) }
    }

    private fun disarmMotionSensor() {
        val s = sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        runCatching { sensors.cancelTriggerSensor(motion, s) }
    }
}
