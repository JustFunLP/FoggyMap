package de.foggymap.app.core

import java.util.PriorityQueue

/**
 * Closes gaps between two GPS fixes that lie far apart (battery saver, screen-off batching, fast
 * driving, a few lost fixes): the way between them is searched on the street graph and every
 * segment on it counts as travelled.
 *
 * Only when it is unambiguous: both fixes lie on a street, the way along the streets is hardly
 * longer than the straight line, and there is no second, almost equally short way (e.g. left or
 * right around a block) – then nothing is filled in.
 */
class GapFill(private val cfg: Config = Config()) {
    data class Config(
        val minGap: Double = 25.0,         // shorter gaps are handled by the matcher itself
        val maxGap: Double = 900.0,
        val maxSeconds: Double = 600.0,
        val snapRadius: Double = 20.0,
        val detourFactor: Double = 1.3,
        val detourExtra: Double = 30.0,
        val ambiguity: Double = 1.1,        // alternative within +10 % ⇒ ambiguous
    )

    /** Street graph around the loaded cells: node → segments touching it. */
    class Graph(segments: Collection<Segment>) {
        val byNode = HashMap<Long, MutableList<Segment>>()
        init {
            for (s in segments) {
                if (s.startNode == -1L || s.endNode == -1L) continue
                byNode.getOrPut(s.startNode) { ArrayList(4) }.add(s)
                byNode.getOrPut(s.endNode) { ArrayList(4) }.add(s)
            }
        }
    }

    /**
     * Segments travelled between [a] and [b] (including the ones the fixes lie on), or empty when
     * the gap is small, implausible or ambiguous. [snap] finds street candidates near a point.
     */
    fun fill(a: Fix, b: Fix, carOnly: Boolean, graph: Graph, snap: (LatLon, Double) -> List<Candidate>): List<Segment> {
        val d = Geo.distance(a.pos, b.pos)
        val dt = (b.timeMs - a.timeMs) / 1000.0
        if (d < cfg.minGap || d > cfg.maxGap || dt <= 0 || dt > cfg.maxSeconds || d / dt > 70.0) return emptyList()
        if (a.accuracy > 25 || b.accuracy > 25) return emptyList()
        val dir = Geo.bearing(a.pos, b.pos)
        val sa = best(snap(a.pos, cfg.snapRadius), carOnly, a.bearing ?: dir) ?: return emptyList()
        val sb = best(snap(b.pos, cfg.snapRadius), carOnly, b.bearing ?: dir) ?: return emptyList()
        val maxCost = cfg.detourFactor * d + cfg.detourExtra
        if (sa.segment.id == sb.segment.id) {
            return if (kotlin.math.abs(sa.along - sb.along) <= maxCost) listOf(sa.segment) else emptyList()
        }
        val first = route(sa, sb, carOnly, graph, maxCost, emptySet()) ?: return emptyList()
        // Is there a second way of almost the same length through other junctions (around the block)?
        if (route(sa, sb, carOnly, graph, first.cost * cfg.ambiguity, first.nodes) != null) return emptyList()
        return first.segments
    }

    /** Nearest street, preferring one along the direction of travel (at a junction the cross street is just as near). */
    private fun best(c: List<Candidate>, carOnly: Boolean, heading: Double): Candidate? =
        c.filter { it.segment.matchable && (!carOnly || it.segment.roadClass.vehicular) }
            .minByOrNull { it.distance + Geo.axisDifference(heading, it.axisBearing) / 90.0 * 14.0 }

    private class Route(val cost: Double, val segments: List<Segment>, val nodes: Set<Long>)

    /** Dijkstra from the position on [sa] to the position on [sb], never passing [avoid]. */
    private fun route(sa: Candidate, sb: Candidate, carOnly: Boolean, g: Graph, maxCost: Double, avoid: Set<Long>): Route? {
        val dist = HashMap<Long, Double>()
        val prev = HashMap<Long, Pair<Long, Segment>>()
        val pq = PriorityQueue<Pair<Double, Long>>(compareBy { it.first })
        fun push(n: Long, c: Double, from: Long?, via: Segment?) {
            if (c > maxCost || n in avoid) return
            if (c < (dist[n] ?: Double.MAX_VALUE)) {
                dist[n] = c
                if (from != null && via != null) prev[n] = from to via else prev.remove(n)
                pq.add(c to n)
            }
        }
        val a = sa.segment
        val b = sb.segment
        push(a.startNode, sa.along, null, null)
        push(a.endNode, a.length - sa.along, null, null)
        var bestCost = Double.MAX_VALUE; var bestNode = 0L
        while (pq.isNotEmpty()) {
            val (c, n) = pq.poll()
            if (c > (dist[n] ?: Double.MAX_VALUE)) continue
            if (c >= bestCost) break
            if (n == b.startNode && c + sb.along < bestCost) { bestCost = c + sb.along; bestNode = n }
            if (n == b.endNode && c + b.length - sb.along < bestCost) { bestCost = c + b.length - sb.along; bestNode = n }
            for (s in g.byNode[n] ?: continue) {
                if (s.id == a.id || s.id == b.id) continue
                if (carOnly && !s.roadClass.vehicular) continue
                val other = if (s.startNode == n) s.endNode else s.startNode
                push(other, c + s.length, n, s)
            }
        }
        if (bestCost > maxCost) return null
        val path = ArrayList<Segment>()
        val nodes = HashSet<Long>()
        var n = bestNode
        nodes += n
        while (true) { val (p, s) = prev[n] ?: break; path += s; n = p; nodes += n }
        path.reverse()
        return Route(bestCost, listOf(a) + path + listOf(b), nodes)
    }
}
