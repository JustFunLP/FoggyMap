package de.foggymap.app.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import de.foggymap.app.core.TravelMode
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the phone itself says about how you move – the same "activity recognition" Google Maps and Waze
 * use. It runs on the phone's low-power sensor chip (practically no battery) and tells when you stand
 * still, walk, ride a bike or sit in a vehicle. FoggyMap uses it to switch the GPS off as soon as you
 * stand, to switch it on again when you move off, and as a hint for "bike or car".
 *
 * Needs Google Play services and the permission "Körperliche Aktivität". Without them everything still
 * works, only the GPS pause comes later (FoggyMap then decides from the GPS positions alone).
 * Nothing of this leaves the phone.
 */
object Motion {
    data class State(val mode: TravelMode, val atMs: Long, val entered: Boolean)

    /** Latest transition (null = nothing known yet). [State.atMs] is wall-clock time. */
    val state = MutableStateFlow<State?>(null)
    @Volatile var running = false; private set

    fun playServices(c: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    fun permitted(c: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** Whether asking for the permission makes sense on this phone. */
    fun canAsk(c: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && playServices(c) && !permitted(c)

    private fun pendingIntent(c: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)   // Play services adds the result
        return PendingIntent.getBroadcast(c, 41, Intent(c, MotionReceiver::class.java).setPackage(c.packageName), flags)
    }

    /** Starts listening for transitions; false when not possible (no Play services / no permission). */
    @SuppressLint("MissingPermission")
    fun start(c: Context): Boolean {
        if (!playServices(c) || !permitted(c)) { running = false; return false }
        val kinds = listOf(DetectedActivity.STILL, DetectedActivity.WALKING, DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE, DetectedActivity.IN_VEHICLE)
        val transitions = kinds.flatMap { k ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map { t ->
                ActivityTransition.Builder().setActivityType(k).setActivityTransition(t).build()
            }
        }
        return try {
            ActivityRecognition.getClient(c).requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(c))
                .addOnSuccessListener { running = true; android.util.Log.i("FoggyMap", "motion sensor: activity recognition on") }
                .addOnFailureListener { e -> running = false; android.util.Log.w("FoggyMap", "motion sensor: ${e.message}") }
            true
        } catch (e: Exception) { running = false; false }
    }

    @SuppressLint("MissingPermission")
    fun stop(c: Context) {
        if (!playServices(c)) return
        runCatching { ActivityRecognition.getClient(c).removeActivityTransitionUpdates(pendingIntent(c)) }
        running = false
        state.value = null
    }

    fun modeOf(type: Int): TravelMode? = when (type) {
        DetectedActivity.STILL -> TravelMode.STILL
        DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_FOOT -> TravelMode.WALK
        DetectedActivity.ON_BICYCLE -> TravelMode.BIKE
        DetectedActivity.IN_VEHICLE -> TravelMode.CAR
        else -> null
    }

    internal fun onTransition(type: Int, enter: Boolean, elapsedNanos: Long) {
        val mode = modeOf(type) ?: return
        val atMs = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - elapsedNanos / 1_000_000)
        // An exit alone says little; only "left STILL" matters (you are moving again).
        if (!enter && mode != TravelMode.STILL) return
        state.value = State(mode, atMs, enter)
        android.util.Log.i("FoggyMap", "motion: ${if (enter) "enter" else "exit"} $mode")
    }
}

/** Receives the transitions from Play services (not exported; only our own PendingIntent reaches it). */
class MotionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (e in result.transitionEvents.sortedBy { it.elapsedRealTimeNanos }) {
            Motion.onTransition(e.activityType, e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER, e.elapsedRealTimeNanos)
        }
    }
}
