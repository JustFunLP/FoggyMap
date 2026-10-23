package de.foggymap.app.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Steadies the position shown on the map (your figure), without slowing it down when you move:
 *  - standing: fixes are averaged, weighted by their accuracy, so the figure stops dancing around and
 *    settles close to where you really are,
 *  - a much worse fix (Wi-Fi/cell while GPS is paused, a GPS outlier) that still agrees with the current
 *    estimate is ignored for a while instead of pulling the figure off the street,
 *  - moving (or a fix clearly elsewhere): the new fix is taken at once.
 *
 * Only for display. Matching streets uses the raw fixes (it has its own, tested noise handling).
 */
class PositionFilter {
    data class Estimate(val lat: Double, val lon: Double, val accuracy: Double, val timeMs: Long)

    var estimate: Estimate? = null; private set
    private var weight = 0.0          // sum of 1/acc² of the averaged fixes (decays with time)

    fun update(lat: Double, lon: Double, accuracy: Double, timeMs: Long, speed: Double?): Estimate {
        val acc = accuracy.coerceIn(MIN_ACC, 500.0)
        val e = estimate
        val w = 1.0 / (acc * acc)
        if (e == null) return reset(lat, lon, acc, timeMs, w)
        val dt = max(0L, timeMs - e.timeMs) / 1000.0
        val d = Geo.distance(LatLon(e.lat, e.lon), LatLon(lat, lon))
        val moving = (speed ?: 0.0) > MOVING_SPEED

        // A clearly worse fix that agrees with what we know: keep the better estimate.
        if (!moving && acc > max(2 * e.accuracy, e.accuracy + 15) && d < acc && dt < HOLD_S) {
            return e
        }
        // Moving, or somewhere clearly else: follow immediately.
        if (moving || d > max(2 * acc, 15.0)) return reset(lat, lon, acc, timeMs, w)

        // Standing: accuracy-weighted running average; older fixes count less over time.
        val old = weight * exp(-dt / MEMORY_S)
        val total = old + w
        val k = w / total
        val nLat = e.lat + (lat - e.lat) * k
        val nLon = e.lon + (lon - e.lon) * k
        weight = total
        val nAcc = max(MIN_ACC, 1.0 / sqrt(total))
        return Estimate(nLat, nLon, nAcc, timeMs).also { estimate = it }
    }

    private fun reset(lat: Double, lon: Double, acc: Double, timeMs: Long, w: Double): Estimate {
        weight = w
        return Estimate(lat, lon, acc, timeMs).also { estimate = it }
    }

    companion object {
        const val MIN_ACC = 3.0
        const val MOVING_SPEED = 0.8
        const val HOLD_S = 60.0
        const val MEMORY_S = 25.0
    }
}
