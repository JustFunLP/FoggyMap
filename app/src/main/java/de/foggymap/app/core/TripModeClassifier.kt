package de.foggymap.app.core

/**
 * On foot, by bike or by car – decided from the speeds of the surrounding minutes, not from one moment:
 * a car crawling through town at 20 km/h is still a car, a jogger is on foot.
 *
 * Every counted distance is held back for [DECIDE_AFTER_MS] so that what happens right afterwards
 * (speeding up to 50 km/h at the next green light) can still decide it.
 *  - any speed above [CAR_SPEED] within 3 minutes before or 90 s after → car,
 *  - slow for the whole next 90 s (parked and walking away) → on foot,
 *  - bike speeds around (above [BIKE_SPEED]) → bike,
 *  - otherwise on foot (walking and running).
 */
class TripModeClassifier {
    private class Piece(val timeMs: Long, val speed: Double, val meters: Double)
    private val pending = ArrayDeque<Piece>()
    private val speeds = ArrayDeque<Pair<Long, Double>>()
    private var latest = 0L

    /** Adds a counted distance; returns the distances decided now (mode → metres). */
    fun add(timeMs: Long, speed: Double, meters: Double): Map<TravelMode, Double> {
        latest = maxOf(latest, timeMs)
        speeds.addLast(timeMs to speed)
        while (speeds.isNotEmpty() && latest - speeds.first().first > BEFORE_MS + DECIDE_AFTER_MS) speeds.removeFirst()
        if (meters > 0) pending.addLast(Piece(timeMs, speed, meters))
        return decide(all = false)
    }

    /** Only a speed (no distance counted, e.g. a fix the street matcher did not use). */
    fun speed(timeMs: Long, speed: Double) = add(timeMs, speed, 0.0)

    /** End of the tour: decide everything that is still open. */
    fun flush(): Map<TravelMode, Double> = decide(all = true)

    /** Mode right now, for the status line. */
    fun current(nowMs: Long = latest): TravelMode {
        val recent = speeds.filter { nowMs - it.first <= 60_000 }.map { it.second }
        val last = recent.lastOrNull() ?: return TravelMode.STILL
        return when {
            speeds.any { nowMs - it.first <= BEFORE_MS && it.second > CAR_SPEED } && last > 1.0 -> TravelMode.CAR
            last < 0.5 -> TravelMode.STILL
            recent.any { it > BIKE_SPEED } -> TravelMode.BIKE
            else -> TravelMode.WALK
        }
    }

    private fun decide(all: Boolean): Map<TravelMode, Double> {
        val out = HashMap<TravelMode, Double>()
        while (pending.isNotEmpty() && (all || latest - pending.first().timeMs >= DECIDE_AFTER_MS)) {
            val p = pending.removeFirst()
            val m = modeOf(p)
            out[m] = (out[m] ?: 0.0) + p.meters
        }
        return out
    }

    private fun modeOf(p: Piece): TravelMode {
        val after = speeds.filter { it.first in p.timeMs..(p.timeMs + DECIDE_AFTER_MS) }.map { it.second }
        val around = speeds.filter { it.first in (p.timeMs - BEFORE_MS)..(p.timeMs + DECIDE_AFTER_MS) }.map { it.second }
        return when {
            p.speed < SLOW && after.size >= 3 && after.all { it < SLOW } -> TravelMode.WALK
            around.any { it > CAR_SPEED } -> TravelMode.CAR
            around.any { it > BIKE_SPEED } && p.speed > 2.0 -> TravelMode.BIKE
            else -> TravelMode.WALK
        }
    }

    companion object {
        /** ~34 km/h: bikes hardly ever, cars all the time. */
        const val CAR_SPEED = 9.5
        /** ~16 km/h: faster than running. */
        const val BIKE_SPEED = 4.5
        const val SLOW = 2.2
        const val BEFORE_MS = 180_000L
        const val DECIDE_AFTER_MS = 90_000L
    }
}
