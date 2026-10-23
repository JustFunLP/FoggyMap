package de.foggymap.app.core

import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString

/** Street categories with the half-width of the revealed corridor. */
enum class RoadClass(val corridorHalfWidth: Double, val vehicular: Boolean) {
    MOTORWAY(9.0, true), PRIMARY(8.0, true), SECONDARY(7.5, true), TERTIARY(7.0, true),
    RESIDENTIAL(6.5, true), SERVICE(5.0, true), PEDESTRIAN(6.5, true),
    PATH(4.5, false);

    companion object {
        /** null = not a street we explore (sidewalks, crossings, driveways, areas, …). */
        fun of(tags: Map<String, String>): RoadClass? {
            val hw = tags["highway"] ?: return null
            if (tags["area"] == "yes") return null
            val footway = tags["footway"]
            if (footway == "sidewalk" || footway == "crossing" || footway == "traffic_island" || footway == "link") return null
            if (tags["cycleway"] == "crossing" || tags["path"] == "crossing") return null
            return when (hw) {
                "motorway", "trunk", "motorway_link", "trunk_link" -> MOTORWAY
                "primary", "primary_link" -> PRIMARY
                "secondary", "secondary_link" -> SECONDARY
                "tertiary", "tertiary_link" -> TERTIARY
                "residential", "unclassified", "living_street", "road", "busway" -> RESIDENTIAL
                "pedestrian" -> PEDESTRIAN
                "service" -> when (tags["service"]) {
                    "driveway", "parking_aisle", "drive-through", "emergency_access", "slipway" -> null
                    else -> if (tags["access"] == "private" || tags["access"] == "no") null else SERVICE
                }
                "footway", "path", "cycleway", "steps", "bridleway", "track" -> PATH
                else -> null
            }
        }
    }
}

/** A street piece between two real junctions (or a junction and a dead end). */
class Segment(
    val id: String,
    val wayId: Long,
    val roadClass: RoadClass,
    val name: String?,
    val points: List<LatLon>,
    val line: LineString,           // local metres
    val startNode: Long,
    val endNode: Long,
    /** All nodes inside the query bbox ⇒ every junction on it is known ⇒ id is stable across map cells. */
    val complete: Boolean,
    val elevated: Boolean,          // bridge / tunnel / layer ≠ 0
    val tunnel: Boolean,
) {
    val length: Double get() = line.length
    val matchable get() = complete && !tunnel
    val blockForming get() = roadClass.vehicular && !elevated
}

object RoadNetworkBuilder {
    /** Very long rural roads are cut into chunks so that one step does not reveal kilometres. */
    const val MAX_SEGMENT_LENGTH = 900.0

    fun build(data: OsmData, proj: LocalProjection, gf: GeometryFactory): List<Segment> {
        data class W(val way: OsmWay, val cls: RoadClass)
        val roads = data.ways.mapNotNull { w -> RoadClass.of(w.tags)?.let { W(w, it) } }

        // node -> list of (road index) using it; self-intersections count twice.
        val usage = HashMap<Long, MutableList<Int>>()
        roads.forEachIndexed { idx, r -> r.way.nodes.forEach { n -> usage.getOrPut(n) { ArrayList(2) }.add(idx) } }

        val out = ArrayList<Segment>()
        for ((idx, r) in roads.withIndex()) {
            val w = r.way
            val elevated = isElevated(w.tags)
            val tunnel = w.tags["tunnel"].let { it != null && it != "no" } || w.tags["covered"] == "yes"
            val cuts = ArrayList<Int>()
            cuts.add(0)
            for (i in 1 until w.nodes.size - 1) {
                val users = usage[w.nodes[i]] ?: continue
                if (users.size < 2) continue
                // A street is only split by other streets; footpaths split at every junction.
                val splits = users.any { other ->
                    if (other == idx) users.count { it == idx } > 1
                    else !r.cls.vehicular || roads[other].cls.vehicular
                }
                if (splits) cuts.add(i)
            }
            cuts.add(w.nodes.size - 1)
            for (c in 0 until cuts.size - 1) {
                val from = cuts[c]; val to = cuts[c + 1]
                if (to <= from) continue
                var pts = w.points.subList(from, to + 1)
                var a = w.nodes[from]; var b = w.nodes[to]
                if (data.syntheticIds && canonicalReversed(a, b, w.nodes, from, to)) {
                    // Same orientation in every cell, so chunk numbers are stable too.
                    pts = pts.asReversed(); val t = a; a = b; b = t
                }
                val complete = pts.all { data.bbox.contains(it) }
                val baseId = if (data.syntheticIds) syntheticId(r.cls, a, b, pts)
                    else "${w.id}:${minOf(a, b)}-${maxOf(a, b)}" + if (a == b) ":$from" else ""
                val coords = pts.map(proj::toLocal).toTypedArray()
                if (coords.size < 2) continue
                val line = gf.createLineString(coords)
                if (line.length < 0.5) continue
                val pieces = if (line.length <= MAX_SEGMENT_LENGTH) listOf(pts to line)
                    else chunk(pts, line, proj, gf)
                pieces.forEachIndexed { k, (p, l) ->
                    out.add(Segment(
                        id = if (pieces.size == 1) baseId else "$baseId:$k",
                        wayId = w.id, roadClass = r.cls, name = w.tags["name"], points = p, line = l,
                        startNode = if (k == 0) a else -1, endNode = if (k == pieces.lastIndex) b else -1,
                        complete = complete, elevated = elevated, tunnel = tunnel,
                    ))
                }
            }
        }
        return out
    }

    private fun canonicalReversed(a: Long, b: Long, nodes: LongArray, from: Int, to: Int): Boolean {
        if (a != b) return a > b
        if (to - from < 2) return false
        return nodes[from + 1] > nodes[to - 1]
    }

    /**
     * Id from the end junctions plus an order-independent fingerprint of the shape (two different
     * streets between the same two junctions get different ids). Only characters allowed in
     * [RevealItem.ID_PATTERN].
     */
    fun syntheticId(cls: RoadClass, a: Long, b: Long, pts: List<LatLon>): String {
        var sLat = 0L; var sLon = 0L
        for (p in pts) { sLat += Math.round(p.lat * 1e6); sLon += Math.round(p.lon * 1e6) }
        val shape = ((sLat * 31 + sLon) and 0xFFFFFFFFFFL).toString(36)
        return "v${cls.ordinal}.${minOf(a, b).toString(36)}-${maxOf(a, b).toString(36)}~$shape"
    }

    fun isElevated(tags: Map<String, String>): Boolean {
        val bridge = tags["bridge"].let { it != null && it != "no" }
        val tunnel = tags["tunnel"].let { it != null && it != "no" }
        val layer = tags["layer"]?.toIntOrNull() ?: 0
        return bridge || tunnel || layer != 0
    }

    private fun chunk(pts: List<LatLon>, line: LineString, proj: LocalProjection, gf: GeometryFactory): List<Pair<List<LatLon>, LineString>> {
        val n = kotlin.math.ceil(line.length / MAX_SEGMENT_LENGTH).toInt()
        val step = line.length / n
        val indexed = org.locationtech.jts.linearref.LengthIndexedLine(line)
        return (0 until n).map { k ->
            val sub = indexed.extractLine(k * step, (k + 1) * step) as LineString
            sub.coordinates.map(proj::toGeo) to sub
        }
    }
}
