package de.foggymap.app.core

import kotlin.math.max
import kotlin.math.min

/** One location fix from Android, reduced to what the matcher needs. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracy: Double,
    val timeMs: Long,
    val speed: Double? = null,      // m/s, only if the provider supplied it
    val bearing: Double? = null,    // degrees, only if the provider supplied it
) {
    val pos get() = LatLon(lat, lon)
}

enum class TravelMode { STILL, WALK, BIKE, CAR, TRAIN }

/**
 * Turns noisy GPS into confirmed street segments.
 *
 * Safeguards against false reveals:
 *  - accuracy threshold and plausibility filter (teleport/jump rejection),
 *  - search radius bound to the reported accuracy,
 *  - heading check: the movement direction must fit the street axis (cross streets at junctions),
 *  - ambiguity margin: if two streets fit almost equally well (parallel streets, junctions) nothing
 *    is credited unless it continues the previously matched street,
 *  - debouncing: a segment needs ≥ 2 evidence points (one very precise fix right on it suffices),
 *  - gap filling: fast movement between two good fixes is interpolated so short segments
 *    passed by car are not skipped.
 */
class MapMatcher(private val cfg: Config = Config()) {
    data class Config(
        val maxAccuracy: Double = 35.0,
        val maxSpeed: Double = 70.0,              // m/s ≈ 250 km/h
        val ambiguityMargin: Double = 4.0,
        val confirmEvidence: Double = 2.0,
        val headingPenalty: Double = 14.0,        // metres-equivalent for a perpendicular street
        val continuityBonus: Double = 3.0,
        val interpolationStep: Double = 8.0,
        val evidenceTtlMs: Long = 90_000,
    )

    data class Result(
        val accepted: Boolean,
        val reason: String,
        val newlyConfirmed: List<Segment> = emptyList(),
        val current: Segment? = null,
        val countedDistance: Double = 0.0,
        val speed: Double = 0.0,
        val mode: TravelMode = TravelMode.STILL,
        /** Clear movement (jitter averaged out) – not just a noisy position. */
        val moving: Boolean = false,
    )

    private class Evidence(var weight: Double, var lastMs: Long)

    private var last: Fix? = null
    private var lastMatched: Segment? = null
    private var jumpStreak = 0
    private var distanceAnchor: LatLon? = null
    private var awayCount = 0
    private val window = ArrayDeque<Fix>()
    private var movingStreak = 0
    private var smoothedSpeed = 0.0
    private val evidence = HashMap<String, Evidence>()
    private val confirmed = HashSet<String>()
    /** Optional diagnostics hook (tests / debug builds). */
    var debug: ((String) -> Unit)? = null

    /** Segments revealed earlier (from storage); they are not reported again. */
    fun markKnown(ids: Collection<String>) { confirmed.addAll(ids) }

    fun process(fix: Fix, candidates: (LatLon, Double) -> List<Candidate>): Result {
        if (fix.accuracy.isNaN() || fix.accuracy > cfg.maxAccuracy) return Result(false, "accuracy")
        val prev = last
        var dist = 0.0
        var dt = 0.0
        if (prev != null) {
            if (fix.timeMs <= prev.timeMs) return Result(false, "stale")
            dt = (fix.timeMs - prev.timeMs) / 1000.0
            dist = Geo.distance(prev.pos, fix.pos)
            val implied = dist / dt
            if (dist > 50 && implied > cfg.maxSpeed) {
                if (++jumpStreak < 3) return Result(false, "jump")
                // Three consistent far fixes: we really are elsewhere (e.g. after a train ride).
                resetTrack()
                return accept(fix, null, 0.0, 0.0, emptyList(), "relocated")
            }
        }
        jumpStreak = 0

        // Motion from the net displacement over a ~5–20 s window: GPS jitter cancels out, real movement does not.
        window.addLast(fix)
        while (window.size > 2 && fix.timeMs - window.first().timeMs > 20_000) window.removeFirst()
        val first = window.first()
        val span = (fix.timeMs - first.timeMs) / 1000.0
        // Net displacement between the averages of the oldest and newest three fixes (jitter averages out).
        val k = minOf(3, window.size / 2).coerceAtLeast(1)
        val head = window.take(k); val tail = window.takeLast(k)
        val from = LatLon(head.sumOf { it.lat } / k, head.sumOf { it.lon } / k)
        val to = LatLon(tail.sumOf { it.lat } / k, tail.sumOf { it.lon } / k)
        val net = Geo.distance(from, to)
        val netSpeed = if (span >= 8.0) net / span else 0.0
        // Doppler speed from the GPS chip is preferred, but some providers (and emulators) report 0 while
        // moving – so a clear net displacement always wins.
        val doppler = fix.speed ?: 0.0
        val speedNow = max(doppler, netSpeed)
        smoothedSpeed = if (prev == null) speedNow else smoothedSpeed * 0.5 + speedNow * 0.5
        val displaced = net >= max(15.0, fix.accuracy * 1.5)
        val movingNow = smoothedSpeed > 0.8 && (doppler >= 1.0 || displaced)
        movingStreak = if (movingNow) movingStreak + 1 else 0
        // Without Doppler speed, require the displacement on two consecutive fixes (jitter rarely does that).
        val moving = movingNow && (doppler >= 1.0 || movingStreak >= 2)

        val heading: Double? = when {
            fix.bearing != null && doppler >= 1.2 -> fix.bearing
            moving && prev != null && dist >= max(8.0, fix.accuracy * 0.8) -> Geo.bearing(prev.pos, fix.pos)
            moving -> Geo.bearing(from, to)
            else -> null
        }

        // Samples: interpolated points for gaps at speed, then the real fix.
        val samples = ArrayList<Triple<LatLon, Double, Double>>() // pos, weight, accuracy
        // Longer gaps are filled along the street graph instead (GapFill) – a straight line would cut corners.
        if (moving && prev != null && dist > 15 && dist < 70 && dt <= 45) {
            val n = (dist / cfg.interpolationStep).toInt()
            for (i in 1 until n) {
                val t = i.toDouble() / n
                samples += Triple(LatLon(prev.lat + (fix.lat - prev.lat) * t, prev.lon + (fix.lon - prev.lon) * t),
                    0.6, max(prev.accuracy, fix.accuracy))
            }
        }
        if (!moving && window.size < 3) {
            // Not enough history yet to tell standing from moving: wait for more fixes.
        } else if (!moving) {
            // Standing: use the averaged position so jitter does not wander into side streets.
            val lat = window.sumOf { it.lat } / window.size
            val lon = window.sumOf { it.lon } / window.size
            samples += Triple(LatLon(lat, lon), 0.5, fix.accuracy)
        } else samples += Triple(fix.pos, 1.0, fix.accuracy)

        val newly = ArrayList<Segment>()
        for ((pos, weight, acc) in samples) {
            val radius = (acc * 1.3 + 4.0).coerceIn(12.0, 32.0)
            // At car speed footpaths and cycle tracks cannot be what we are on.
            val cands = candidates(pos, radius).let { c -> if (smoothedSpeed > 9.0) c.filter { it.segment.roadClass.vehicular } else c }
            if (cands.isEmpty()) continue
            val scored = cands.map { c -> c to score(c, heading) }.sortedBy { it.second }
            val (best, bestScore) = scored[0]
            // The straight continuation of the same street is no competitor (we are just passing the junction);
            // parallel streets and crossing streets are.
            val rival = scored.drop(1).firstOrNull { (c, _) ->
                !(shareNode(c.segment, best.segment) && Geo.axisDifference(c.axisBearing, best.axisBearing) < 30.0)
            }
            val margin = if (rival != null) rival.second - bestScore else Double.MAX_VALUE
            var w = weight
            if (margin < cfg.ambiguityMargin) {
                if (best.segment.id == lastMatched?.id) w *= 0.5 else continue
            }
            // Near a junction the position says little about *which* street we are on: only count it
            // when we are clearly moving along the street axis.
            val endZone = minOf(maxOf(acc * 0.8, 6.0), best.segment.length * 0.3)
            if (best.fromEnd < endZone) {
                val along = heading != null && Geo.axisDifference(heading, best.axisBearing) < 30.0 && moving
                if (!along) continue
                w *= 0.6
            }
            lastMatched = best.segment
            val id = best.segment.id
            if (id in confirmed) continue
            val ev = evidence.getOrPut(id) { Evidence(0.0, fix.timeMs) }
            ev.weight += w; ev.lastMs = fix.timeMs
            val precise = weight == 1.0 && acc <= 10.0 && best.distance <= 5.0 && margin >= 8.0
            // Short segments (dense junctions, passed by car) need less evidence.
            val needed = if (best.segment.length < 60.0) cfg.confirmEvidence * 0.7 else cfg.confirmEvidence
            if (ev.weight >= needed || precise) {
                debug?.invoke("confirm $id w=${ev.weight} precise=$precise moving=$moving sampleW=$weight fromEnd=${best.fromEnd} d=${best.distance} margin=$margin")
                confirmed += id
                evidence.remove(id)
                newly += best.segment
            }
        }
        evidence.entries.removeAll { fix.timeMs - it.value.lastMs > cfg.evidenceTtlMs }
        return accept(fix, lastMatched, dist, smoothedSpeed, newly, "ok", moving)
    }

    private fun accept(fix: Fix, current: Segment?, dist: Double, speed: Double, newly: List<Segment>, reason: String, moving: Boolean = false): Result {
        last = fix
        // Distance statistics: only count movement that clearly exceeds GPS jitter.
        // A move is only counted once two consecutive fixes are away from the anchor (single outliers are jitter).
        var counted = 0.0
        val anchor = distanceAnchor
        if (anchor == null) distanceAnchor = fix.pos
        else {
            val d = Geo.distance(anchor, fix.pos)
            val threshold = max(15.0, min(fix.accuracy * 1.5, 40.0))
            if (d >= threshold) {
                if (awayCount++ >= 1) { counted = d; distanceAnchor = fix.pos; awayCount = 0 }
            } else awayCount = 0
        }
        return Result(true, reason, newly, current, counted, speed, modeFor(speed), moving)
    }

    private fun score(c: Candidate, heading: Double?): Double {
        var s = c.distance
        // Unmarked sidewalks/paths often run right next to a street: prefer the street.
        if (!c.segment.roadClass.vehicular) s += 2.5
        if (heading != null) s += Geo.axisDifference(heading, c.axisBearing) / 90.0 * cfg.headingPenalty
        val lm = lastMatched
        if (lm != null) {
            if (c.segment.id == lm.id) s -= cfg.continuityBonus
            else if (shareNode(c.segment, lm)) s -= cfg.continuityBonus / 2
        }
        return s
    }

    private fun shareNode(a: Segment, b: Segment): Boolean {
        val an = setOf(a.startNode, a.endNode) - (-1L)
        return b.startNode in an || b.endNode in an
    }

    private fun resetTrack() {
        lastMatched = null; evidence.clear(); jumpStreak = 0; distanceAnchor = null; smoothedSpeed = 0.0; window.clear()
    }

    companion object {
        fun modeFor(speed: Double) = when {
            speed < 0.5 -> TravelMode.STILL
            speed < 2.6 -> TravelMode.WALK
            speed < 8.0 -> TravelMode.BIKE
            else -> TravelMode.CAR
        }
    }
}
