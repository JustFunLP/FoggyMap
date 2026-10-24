package de.foggymap.app.core

/**
 * On foot, by bike, by car or by train – decided from the surrounding minutes, not from one moment:
 * a car crawling through town at 20 km/h is still a car, a jogger is on foot, and someone moving fast
 * right on a railway line, away from any street, is on a train.
 *
 * Every counted distance is held back for [DECIDE_AFTER_MS] so that what happens right afterwards
 * (speeding up to 50 km/h at the next green light) can still decide it.
 *  - mostly on a railway (and away from streets) with train speeds around → train,
 *  - any speed above [CAR_SPEED] within 3 minutes before or 90 s after → car,
 *  - slow for the whole next 90 s (parked and walking away) → on foot,
 *  - bike speeds around (above [BIKE_SPEED]) → bike,
 *  - otherwise on foot (walking and running).
 * The phone's activity recognition ([activity]: vehicle / bicycle / on foot) breaks ties that speed alone
 * cannot: a car in a traffic jam, a slow cyclist, a fast runner. Chosen by hand ([manual]) always wins.
 */
class TripModeClassifier {
    private class Piece(val timeMs: Long, val speed: Double, val meters: Double)
    private class Sample(val timeMs: Long, val speed: Double, val onRail: Boolean)
    private val pending = ArrayDeque<Piece>()
    private val speeds = ArrayDeque<Sample>()
    private class Hint(val timeMs: Long, val mode: TravelMode)
    private val hints = ArrayDeque<Hint>()
    private var latest = 0L
    /** Set by hand in the app (null = automatic). */
    var manual: TravelMode? = null

    /** Adds a counted distance; returns the distances decided now (mode → metres). */
    fun add(timeMs: Long, speed: Double, meters: Double, onRail: Boolean = false): Map<TravelMode, Double> {
        latest = maxOf(latest, timeMs)
        speeds.addLast(Sample(timeMs, speed, onRail))
        while (speeds.isNotEmpty() && latest - speeds.first().timeMs > BEFORE_MS + DECIDE_AFTER_MS) speeds.removeFirst()
        if (meters > 0) pending.addLast(Piece(timeMs, speed, meters))
        return decide(all = false)
    }

    /** The phone's activity recognition entered [mode] at [timeMs] (valid until the next one). */
    fun activity(timeMs: Long, mode: TravelMode) {
        hints.addLast(Hint(timeMs, mode))
        while (hints.size > 50) hints.removeFirst()
    }

    /** What activity recognition said around [t] (it reports with up to a minute delay). */
    private fun hintAt(t: Long): TravelMode? = hints.lastOrNull { it.timeMs <= t + HINT_DELAY_MS }?.mode

    /** Only a speed (no distance counted, e.g. a fix the street matcher did not use). */
    fun speed(timeMs: Long, speed: Double, onRail: Boolean = false) = add(timeMs, speed, 0.0, onRail)

    /** End of the tour: decide everything that is still open. */
    fun flush(): Map<TravelMode, Double> = decide(all = true)

    /** Mode right now, for the status line. */
    fun current(nowMs: Long = latest): TravelMode {
        manual?.let { return it }
        val recent = speeds.filter { nowMs - it.timeMs <= 60_000 }.map { it.speed }
        val last = recent.lastOrNull() ?: return TravelMode.STILL
        val hint = hintAt(nowMs)
        return when {
            onTrain(speeds.filter { nowMs - it.timeMs <= DECIDE_AFTER_MS }) -> TravelMode.TRAIN
            hint == TravelMode.CAR -> if (last > SLOW) TravelMode.CAR else TravelMode.STILL
            hint == TravelMode.BIKE && last >= 1.0 && recent.none { it > BIKE_MAX } -> TravelMode.BIKE
            hint == TravelMode.WALK && last >= 0.5 && recent.none { it > CAR_SPEED } -> TravelMode.WALK
            speeds.any { nowMs - it.timeMs <= BEFORE_MS && it.speed > CAR_SPEED } && last > SLOW -> TravelMode.CAR
            last < SLOW && speeds.any { nowMs - it.timeMs <= BEFORE_MS && it.speed > CAR_SPEED } -> TravelMode.STILL   // traffic light or parked
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

    /** Most of the moving samples right on a railway (away from streets), at train speeds. */
    private fun onTrain(samples: List<Sample>): Boolean {
        val moving = samples.filter { it.speed > 3.0 }
        return moving.size >= 6 && moving.count { it.onRail } >= 0.75 * moving.size && moving.any { it.speed > TRAIN_SPEED }
    }

    private fun modeOf(p: Piece): TravelMode {
        manual?.let { return it }
        val window = speeds.filter { it.timeMs in (p.timeMs - BEFORE_MS)..(p.timeMs + DECIDE_AFTER_MS) }
        val after = speeds.filter { it.timeMs in p.timeMs..(p.timeMs + DECIDE_AFTER_MS) }.map { it.speed }
        val around = window.map { it.speed }
        val hint = hintAt(p.timeMs)
        return when {
            onTrain(window) -> TravelMode.TRAIN
            hint == TravelMode.CAR && (p.speed > SLOW || around.any { it > BIKE_SPEED }) -> TravelMode.CAR
            hint == TravelMode.BIKE && p.speed > 1.5 && around.none { it > BIKE_MAX } -> TravelMode.BIKE
            hint == TravelMode.WALK && around.none { it > CAR_SPEED } -> TravelMode.WALK
            p.speed < SLOW && after.size >= 3 && after.all { it < SLOW } -> TravelMode.WALK
            around.any { it > CAR_SPEED } -> TravelMode.CAR
            around.any { it > BIKE_SPEED } && p.speed > 2.0 -> TravelMode.BIKE
            else -> TravelMode.WALK
        }
    }

    companion object {
        /** ~34 km/h: bikes hardly ever, cars all the time. */
        const val CAR_SPEED = 9.5
        /** ~25 km/h on the rails. */
        const val TRAIN_SPEED = 7.0
        /** ~16 km/h: faster than running. */
        const val BIKE_SPEED = 4.5
        const val SLOW = 2.2
        /** ~47 km/h: no bike in town goes faster (activity recognition said "bike" but it is not). */
        const val BIKE_MAX = 13.0
        const val HINT_DELAY_MS = 60_000L
        const val BEFORE_MS = 180_000L
        const val DECIDE_AFTER_MS = 90_000L
    }
}
