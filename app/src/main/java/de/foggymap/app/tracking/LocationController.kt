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
 *  - GPS while standing still is the biggest waste → after ~75 s without movement GPS is switched
 *    off and only a low-power (Wi-Fi/cell) fix every 30–60 s is requested. The hardware
 *    "significant motion" sensor (practically free) wakes GPS again as soon as you walk on.
 *  - No GPS signal for minutes (indoors, underground) → also drop to low power instead of letting
 *    the receiver search forever.
 *  - Update rate follows the speed: on foot every 3 s / 4 m, by car every 2 s / 10 m.
 *  - Screen off → fixes are *batched* by the location hardware (Android 12+): the phone's main
 *    processor wakes only every 10–30 s to receive a whole batch. Matching works on the batch
 *    because every fix carries its own timestamp.
 */
class LocationController(private val context: Context, private val onLocations: (List<Location>) -> Unit) {
    enum class Profile(val label: String) { FAST("Fahrt"), MOVING("Unterwegs"), STILL("GPS pausiert (Stillstand)") }

    private val lm = context.getSystemService(LocationManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var profile: Profile? = null
    private var mode: BatteryMode = BatteryMode.BALANCED
    private var screenOn = true
    private var subscribed = false
    var onSignificantMotion: (() -> Unit)? = null

    private val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) = onLocations(listOf(location))
        override fun onLocationChanged(locations: MutableList<Location>) = onLocations(locations.toList())
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
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
        val batch = if (!screenOn && mode != BatteryMode.PRECISE && p != Profile.STILL) params.interval * params.batchFactor else 0L
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
        if (p == Profile.STILL) armMotionSensor() else disarmMotionSensor()
    }

    fun stop() {
        stopUpdates()
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
        BatteryMode.SAVER -> when (p) {
            Profile.FAST -> Params(4000, 20f, LocationRequestCompat.QUALITY_HIGH_ACCURACY, 8)
            Profile.MOVING -> Params(5000, 7f, LocationRequestCompat.QUALITY_HIGH_ACCURACY, 6)
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
