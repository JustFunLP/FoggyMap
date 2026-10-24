package de.foggymap.app.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Puts your figure on the street you are on – like the arrow in Google Maps – instead of where the raw
 * GPS position happens to wobble (next to the street, inside a house, jumping between both sides).
 *
 *  - Only for the display: what gets revealed is decided by [MapMatcher] as before.
 *  - Snaps only when a street is close enough for the GPS accuracy; on a square or in a park the figure
 *    stays where GPS says. Entering needs to be closer than leaving (no flip-flopping at the edge).
 *  - Sticks to the street it is on: another street must fit clearly better to take over (parallel
 *    streets, junctions). The walking/driving direction must fit the street axis.
 *  - Returns the next stretch of the street ahead ([Snap.ahead]), so the map can let the figure glide
 *    along the street between two GPS fixes instead of jumping from fix to fix.
 */
class RoadSnapper {
    data class Snap(
        val pos: LatLon,
        /** Direction of the street in your travel direction (null when not on a street or unknown). */
        val bearing: Double?,
        /** The street ahead in travel direction, starting at [pos] (up to [AHEAD_M]); empty when not snapped. */
        val ahead: List<LatLon>,
        val snapped: Boolean,
        val timeMs: Long,
        val moving: Boolean,
    )

    private var lastSegment: Segment? = null
    private var lastDir = 1                 // +1 = along the segment's point order, -1 = against it
    private var wasSnapped = false

    fun reset() { lastSegment = null; wasSnapped = false }

    /**
     * [pos] position to show (already steadied while standing), [accuracy] metres, [heading] travel direction
     * (only while moving), [candidates] streets around a point within a radius.
     */
    fun snap(pos: LatLon, accuracy: Double, heading: Double?, moving: Boolean, timeMs: Long,
             candidates: (LatLon, Double) -> List<Candidate>): Snap {
        val limit = max(ENTER_M, min(accuracy, MAX_M)) + if (wasSnapped) STAY_EXTRA_M else 0.0
        val cands = candidates(pos, limit).filter { it.distance <= limit }
        if (cands.isEmpty()) { wasSnapped = false; lastSegment = null; return Snap(pos, null, emptyList(), false, timeMs, moving) }
        val last = lastSegment
        fun score(c: Candidate): Double {
            var s = c.distance
            if (heading != null && moving) s += Geo.axisDifference(heading, c.axisBearing) / 90.0 * HEADING_PENALTY
            if (last != null) {
                if (c.segment.id == last.id) s -= STICKY_M
                else if (sharesNode(c.segment, last)) s -= STICKY_M / 2
            }
            return s
        }
        val best = cands.minByOrNull { score(it) }!!
        val seg = best.segment
        val proj = project(seg.points, pos) ?: run { wasSnapped = false; return Snap(pos, null, emptyList(), false, timeMs, moving) }
        // Travel direction along the street: from the heading while moving, otherwise as before on the same street.
        val forwardBearing = proj.legBearing
        val dir = when {
            heading != null && moving -> if (Geo.angleDifference(heading, forwardBearing) <= 90.0) 1 else -1
            last?.id == seg.id -> lastDir
            else -> 1
        }
        if (last?.id != seg.id || heading != null) lastDir = dir
        lastSegment = seg
        wasSnapped = true
        val ahead = aheadOf(seg.points, proj, dir, AHEAD_M).toMutableList()
        // Short of the junction: continue on the street that goes on (most) straight ahead.
        val left = AHEAD_M - length(ahead)
        if (left > 1.0) continuation(seg, dir, candidates)?.let { (next, nextDir) ->
            val start = if (nextDir == 1) Projection(0, 0.0, next.points.first(), 0.0) else Projection(next.points.size - 2, 1.0, next.points.last(), 0.0)
            ahead += aheadOf(next.points, start, nextDir, left).drop(1)
        }
        val bearing = if (dir == 1) forwardBearing else (forwardBearing + 180.0) % 360.0
        return Snap(proj.point, bearing, ahead, true, timeMs, moving)
    }

    /** The segment that continues [seg] at its far end (in direction [dir]) with the smallest turn, and its direction. */
    private fun continuation(seg: Segment, dir: Int, candidates: (LatLon, Double) -> List<Candidate>): Pair<Segment, Int>? {
        val endNode = if (dir == 1) seg.endNode else seg.startNode
        if (endNode == -1L || seg.points.size < 2) return null
        val end = if (dir == 1) seg.points.last() else seg.points.first()
        val before = if (dir == 1) seg.points[seg.points.size - 2] else seg.points[1]
        val inBearing = Geo.bearing(before, end)
        var best: Pair<Segment, Int>? = null; var bestTurn = MAX_TURN
        for (c in candidates(end, 3.0)) {
            val n = c.segment
            if (n.id == seg.id || n.points.size < 2) continue
            val nd = when (endNode) { n.startNode -> 1; n.endNode -> -1; else -> continue }
            val outBearing = if (nd == 1) Geo.bearing(n.points[0], n.points[1]) else Geo.bearing(n.points.last(), n.points[n.points.size - 2])
            val turn = Geo.angleDifference(inBearing, outBearing)
            if (turn < bestTurn) { bestTurn = turn; best = n to nd }
        }
        return best
    }

    private fun sharesNode(a: Segment, b: Segment): Boolean {
        val an = setOf(a.startNode, a.endNode) - (-1L)
        return b.startNode in an || b.endNode in an
    }

    /** Closest point on a polyline: leg index, fraction along that leg, the point itself and the leg's bearing. */
    internal class Projection(val leg: Int, val t: Double, val point: LatLon, val legBearing: Double)

    internal fun project(points: List<LatLon>, p: LatLon): Projection? {
        if (points.size < 2) return null
        val kx = Geo.M_PER_DEG_LAT * cos(Math.toRadians(p.lat)); val ky = Geo.M_PER_DEG_LAT
        var best: Projection? = null; var bestD = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
            val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 1e-9) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + dx * t; val cy = ay + dy * t
            val d = hypot(cx, cy)
            if (d < bestD) {
                bestD = d
                best = Projection(i, t, LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t),
                    if (len2 <= 1e-9) 0.0 else Geo.bearing(a, b))
            }
        }
        return best
    }

    /** The polyline from the projected point onwards in direction [dir], cut after [maxM] metres. */
    internal fun aheadOf(points: List<LatLon>, proj: Projection, dir: Int, maxM: Double): List<LatLon> {
        val out = ArrayList<LatLon>()
        out += proj.point
        var left = maxM
        var prev = proj.point
        val idx = if (dir == 1) (proj.leg + 1..points.lastIndex) else (proj.leg downTo 0)
        for (i in idx) {
            val q = points[i]
            val d = Geo.distance(prev, q)
            if (d < 0.05) continue
            if (d >= left) {
                val f = left / d
                out += LatLon(prev.lat + (q.lat - prev.lat) * f, prev.lon + (q.lon - prev.lon) * f)
                return out
            }
            out += q; left -= d; prev = q
        }
        return out
    }

    companion object {
        /** Snap when a street is at most this far (or within the GPS accuracy, up to [MAX_M]). */
        const val ENTER_M = 15.0
        const val MAX_M = 25.0
        /** Once on a street, it may drift this much further before the figure leaves it. */
        const val STAY_EXTRA_M = 8.0
        /** Bonus for staying on the same street (metres-equivalent). */
        const val STICKY_M = 6.0
        const val HEADING_PENALTY = 14.0
        const val AHEAD_M = 60.0
        /** Gliding on across a junction only when the street goes on (nearly) straight. */
        const val MAX_TURN = 35.0

        /** Signed distance of [p] along the direction of [path]'s first leg, measured from its start (+ = ahead). */
        fun alongOffset(path: List<LatLon>, p: LatLon): Double? {
            if (path.size < 2) return null
            val o = path[0]; val n = path[1]
            val kx = Geo.M_PER_DEG_LAT * cos(Math.toRadians(o.lat)); val ky = Geo.M_PER_DEG_LAT
            val dx = (n.lon - o.lon) * kx; val dy = (n.lat - o.lat) * ky
            val len = hypot(dx, dy); if (len < 1e-6) return null
            val px = (p.lon - o.lon) * kx; val py = (p.lat - o.lat) * ky
            val along = (px * dx + py * dy) / len
            val side = abs(px * dy - py * dx) / len
            return if (side > 12.0) null else along
        }

        /** The point [m] metres along [path] (clamped to its end). */
        fun pointAlong(path: List<LatLon>, m: Double): LatLon {
            if (path.isEmpty()) throw IllegalArgumentException("empty path")
            var left = max(0.0, m)
            for (i in 0 until path.size - 1) {
                val d = Geo.distance(path[i], path[i + 1])
                if (d >= left && d > 0) {
                    val f = left / d
                    return LatLon(path[i].lat + (path[i + 1].lat - path[i].lat) * f, path[i].lon + (path[i + 1].lon - path[i].lon) * f)
                }
                left -= d
            }
            return path.last()
        }

        fun length(path: List<LatLon>): Double = (0 until path.size - 1).sumOf { Geo.distance(path[it], path[it + 1]) }
    }
}
