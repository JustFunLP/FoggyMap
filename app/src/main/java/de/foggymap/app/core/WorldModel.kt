package de.foggymap.app.core

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.index.strtree.STRtree
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.distance.DistanceOp
import org.locationtech.jts.operation.polygonize.Polygonizer
import org.locationtech.jts.operation.union.UnaryUnionOp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class BuildingShape(val id: Long, val polygon: Polygon, val height: Double, val minHeight: Double)

/** A city block: a face of the planar street graph (streets, railways and rivers as boundaries). */
class Face(val id: String, val polygon: Polygon, val reliable: Boolean) {
    val area: Double = polygon.area
}

/** A street segment near a GPS position. */
data class Candidate(val segment: Segment, val distance: Double, val axisBearing: Double, val along: Double = segment.length / 2) {
    /** Distance of the projected position to the nearer end (junction) of the segment. */
    val fromEnd get() = minOf(along, segment.length - along)
}

/**
 * Everything the reveal logic needs for one Overpass cell, in local metric coordinates.
 * Construction costs a few hundred ms for a dense city cell; do it off the main thread.
 */
class WorldModel(val data: OsmData) {
    val proj = LocalProjection(data.bbox.center)
    val gf = GeometryFactory(PrecisionModel(), 0)
    val segments: List<Segment> = RoadNetworkBuilder.build(data, proj, gf)
    val segmentById: Map<String, Segment> = segments.associateBy { it.id }
    val buildings: List<BuildingShape> = parseBuildings()
    val faces: List<Face>

    private val segIndex = STRtree()
    private val buildingIndex = STRtree()
    private val faceIndex = STRtree()
    private val reliableEnvelope: Envelope

    init {
        val sw = proj.toLocal(LatLon(data.bbox.south, data.bbox.west))
        val ne = proj.toLocal(LatLon(data.bbox.north, data.bbox.east))
        reliableEnvelope = Envelope(sw.x + EDGE_MARGIN, ne.x - EDGE_MARGIN, sw.y + EDGE_MARGIN, ne.y - EDGE_MARGIN)
        segments.forEach { segIndex.insert(it.line.envelopeInternal, it) }
        buildings.forEach { buildingIndex.insert(it.polygon.envelopeInternal, it) }
        faces = buildFaces()
        faces.forEach { faceIndex.insert(it.polygon.envelopeInternal, it) }
    }

    /** Total length of streets whose ids are stable in this cell (used for the exploration percentage). */
    val completeSegments get() = segments.filter { it.matchable }

    fun candidates(p: LatLon, radius: Double): List<Candidate> {
        val c = proj.toLocal(p)
        val env = Envelope(c).apply { expandBy(radius) }
        val pt = gf.createPoint(c)
        @Suppress("UNCHECKED_CAST")
        val hits = segIndex.query(env) as List<Segment>
        return hits.asSequence().filter { it.matchable }.mapNotNull { s ->
            val nearest = DistanceOp.nearestPoints(s.line, pt)[0]
            val d = nearest.distance(c)
            if (d > radius) null else Candidate(s, d, localAxis(s.line, nearest),
                org.locationtech.jts.linearref.LengthIndexedLine(s.line).project(nearest))
        }.toList()
    }

    /** Bearing of the street at the point closest to the user. */
    private fun localAxis(line: LineString, at: Coordinate): Double {
        val cs = line.coordinates
        var best = 0; var bestD = Double.MAX_VALUE
        for (i in 0 until cs.size - 1) {
            val d = org.locationtech.jts.algorithm.Distance.pointToSegment(at, cs[i], cs[i + 1])
            if (d < bestD) { bestD = d; best = i }
        }
        return proj.bearingOf(cs[best + 1].x - cs[best].x, cs[best + 1].y - cs[best].y)
    }

    fun inReliableArea(p: LatLon) = reliableEnvelope.contains(proj.toLocal(p))

    // ---------------------------------------------------------------- reveal

    /**
     * What becomes visible when [segment] is driven/walked:
     *  1. the street corridor between its two junctions,
     *  2. the city blocks whose boundary runs along this segment (not blocks that merely touch the
     *     junction, so cross streets and parallel streets stay hidden),
     *  3. for huge/open blocks only a 40 m deep strip, and in areas without closed blocks the
     *     directly adjacent buildings that are not separated by another street,
     *  4. every building footprint inside the uncovered area (for 3D rendering and statistics).
     */
    fun reveal(segment: Segment): List<RevealItem> {
        val items = ArrayList<RevealItem>()
        val corridor = buffer(segment.line, segment.roadClass.corridorHalfWidth, BufferParameters.CAP_FLAT)
        items += RevealItem("r:${segment.id}", RevealKind.ROAD, RevealItem.polygonsOf(corridor, proj),
            line = segment.points, length = segment.length, name = segment.name)
        val uncovered = ArrayList<Geometry>()
        uncovered.add(corridor)

        val adjacent = adjacentFaces(segment)
        for (f in adjacent) {
            if (f.reliable && f.area in MIN_BLOCK_AREA..MAX_BLOCK_AREA) {
                val inset = f.polygon.buffer(-BLOCK_INSET)
                if (inset.isEmpty) continue
                items += RevealItem("b:${f.id}", RevealKind.BLOCK, RevealItem.polygonsOf(inset, proj))
                uncovered += inset
            } else {
                strip(segment, f, STRIP_DEPTH)?.let { (g, item) -> items += item; uncovered += g }
            }
        }
        if (adjacent.isEmpty() && segment.blockForming.not()) {
            // Footpath inside a park or courtyard: reveal a strip of the surrounding face.
            val mid = segment.line.interiorPoint
            @Suppress("UNCHECKED_CAST")
            (faceIndex.query(mid.envelopeInternal) as List<Face>).filter { it.polygon.contains(mid) }.forEach { f ->
                strip(segment, f, PATH_STRIP_DEPTH)?.let { (g, item) -> items += item; uncovered += g }
            }
        }
        val area = if (uncovered.size == 1) corridor else UnaryUnionOp.union(uncovered)

        val revealedBuildings = HashSet<Long>()
        @Suppress("UNCHECKED_CAST")
        for (b in buildingIndex.query(area.envelopeInternal) as List<BuildingShape>) {
            if (!b.polygon.intersects(area)) continue
            val inside = area.contains(b.polygon.interiorPoint) ||
                safeArea { b.polygon.intersection(area).area } >= 0.4 * b.polygon.area
            if (inside && revealedBuildings.add(b.id)) items += buildingItem(b)
        }
        if (adjacent.isEmpty() && segment.blockForming) {
            // Open settlement without closed blocks: houses along the street, never across another street.
            val env = segment.line.envelopeInternal.also { it.expandBy(OPEN_BUILDING_DISTANCE) }
            @Suppress("UNCHECKED_CAST")
            for (b in buildingIndex.query(env) as List<BuildingShape>) {
                if (b.id in revealedBuildings) continue
                if (b.polygon.distance(segment.line) > OPEN_BUILDING_DISTANCE) continue
                if (separatedByOtherStreet(segment, b.polygon)) continue
                revealedBuildings += b.id
                items += buildingItem(b)
            }
        }
        return items
    }

    private fun buildingItem(b: BuildingShape) = RevealItem(
        "g:${b.id}", RevealKind.BUILDING, RevealItem.polygonsOf(b.polygon.buffer(0.8), proj, 0.3),
        height = b.height, minHeight = b.minHeight,
    )

    private fun strip(segment: Segment, f: Face, depth: Double): Pair<Geometry, RevealItem>? {
        val g = safe { f.polygon.intersection(buffer(segment.line, depth, BufferParameters.CAP_FLAT)).buffer(-BLOCK_INSET / 2) } ?: return null
        if (g.isEmpty || g.area < MIN_BLOCK_AREA) return null
        return g to RevealItem("s:${segment.id}~${f.id}", RevealKind.AREA, RevealItem.polygonsOf(g, proj))
    }

    /** Faces that share a substantial part of their boundary with the segment. */
    fun adjacentFaces(segment: Segment): List<Face> {
        if (!segment.blockForming) return emptyList()
        val probe = buffer(segment.line, ADJ_TOLERANCE, BufferParameters.CAP_FLAT)
        val need = max(3.0, min(0.5 * segment.length, 25.0))
        @Suppress("UNCHECKED_CAST")
        return (faceIndex.query(probe.envelopeInternal) as List<Face>).filter { f ->
            safeArea { f.polygon.exteriorRing.intersection(probe).length } >= need ||
                (0 until f.polygon.numInteriorRing).any { h ->
                    safeArea { f.polygon.getInteriorRingN(h).intersection(probe).length } >= need
                }
        }
    }

    private fun separatedByOtherStreet(segment: Segment, building: Polygon): Boolean {
        val pts = DistanceOp.nearestPoints(segment.line, building)
        val link = gf.createLineString(arrayOf(pts[0], building.interiorPoint.coordinate))
        @Suppress("UNCHECKED_CAST")
        return (segIndex.query(link.envelopeInternal) as List<Segment>).any { other ->
            other !== segment && other.blockForming && other.line.intersects(link) &&
                other.line.distance(gf.createPoint(pts[0])) > 1.0
        }
    }

    // ---------------------------------------------------------------- construction helpers

    private fun buildFaces(): List<Face> {
        val lines = ArrayList<Geometry>()
        segments.filter { it.blockForming }.forEach { lines.add(it.line) }
        // Railways and rivers bound blocks too (otherwise a block would extend across the tracks).
        for (w in data.ways) {
            val isBarrier = w.tags["railway"] != null || w.tags["waterway"] != null
            if (!isBarrier || RoadNetworkBuilder.isElevated(w.tags) || w.points.size < 2) continue
            lines.add(gf.createLineString(w.points.map(proj::toLocal).toTypedArray()))
        }
        if (lines.isEmpty()) return emptyList()
        return try {
            val noded = UnaryUnionOp.union(lines)          // nodes crossings that share no OSM node
            val pz = Polygonizer()
            pz.add(noded)
            @Suppress("UNCHECKED_CAST")
            (pz.polygons as Collection<Polygon>).mapNotNull { p ->
                if (p.area < MIN_BLOCK_AREA || !p.isValid) null
                else Face(faceId(p), p, reliableEnvelope.contains(p.envelopeInternal))
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Stable id from the rounded geographic outline, identical when another cell computes the same block. */
    private fun faceId(p: Polygon): String {
        val keys = p.exteriorRing.coordinates.map { c ->
            val g = proj.toGeo(c)
            (Math.round(g.lat * 20_000) * 31 + Math.round(g.lon * 20_000))
        }.toSortedSet()
        var h = 1125899906842597L
        keys.forEach { h = 31 * h + it }
        return java.lang.Long.toHexString(h)
    }

    private fun parseBuildings(): List<BuildingShape> = data.ways.mapNotNull { w ->
        val b = w.tags["building"] ?: return@mapNotNull null
        val closed = if (w.nodes.isNotEmpty()) w.nodes.first() == w.nodes.last()
            else w.points.first().let { f -> val l = w.points.last(); Math.abs(f.lat - l.lat) < 1e-9 && Math.abs(f.lon - l.lon) < 1e-9 }
        if (b == "no" || w.points.size < 4 || !closed) return@mapNotNull null
        val poly: Polygon = try { gf.createPolygon(w.points.map(proj::toLocal).toTypedArray()) } catch (_: Exception) { null } ?: return@mapNotNull null
        val valid: Polygon = if (poly.isValid) poly else (safe { poly.buffer(0.0) } as? Polygon ?: return@mapNotNull null)
        if (valid.area < 6.0) return@mapNotNull null
        val minH = parseMeters(w.tags["min_height"]) ?: w.tags["building:min_level"]?.toDoubleOrNull()?.times(3.1) ?: 0.0
        val h = parseMeters(w.tags["height"])
            ?: w.tags["building:levels"]?.toDoubleOrNull()?.let { it * 3.1 + if (w.tags["roof:shape"].let { r -> r != null && r != "flat" }) 2.5 else 0.6 }
            ?: defaultHeight(b)
        val hh = h.coerceIn(2.5, 400.0)
        BuildingShape(w.id, valid, hh, minH.coerceIn(0.0, hh - 1))
    }

    private fun safe(block: () -> Geometry): Geometry? = try { block() } catch (_: Exception) { null }
    private fun safeArea(block: () -> Double): Double = try { block() } catch (_: Exception) { 0.0 }

    private fun buffer(g: Geometry, d: Double, cap: Int): Geometry {
        val params = BufferParameters(6, cap, BufferParameters.JOIN_ROUND, 5.0)
        return BufferOp.bufferOp(g, d, params)
    }

    companion object {
        const val EDGE_MARGIN = 60.0
        const val MIN_BLOCK_AREA = 40.0
        /** 5 ha. Bigger faces (industrial estates, parks, fields) only get a strip revealed. */
        const val MAX_BLOCK_AREA = 50_000.0
        const val BLOCK_INSET = 3.0
        const val STRIP_DEPTH = 40.0
        const val PATH_STRIP_DEPTH = 25.0
        const val ADJ_TOLERANCE = 2.0
        const val OPEN_BUILDING_DISTANCE = 30.0

        fun parseMeters(v: String?): Double? {
            if (v == null) return null
            val s = v.trim().lowercase().replace(",", ".")
            val num = Regex("^([0-9]+(?:\\.[0-9]+)?)").find(s)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
            return when {
                s.contains("ft") || s.contains("'") -> num * 0.3048
                else -> num
            }
        }

        fun defaultHeight(type: String) = when (type) {
            "house", "detached", "semidetached_house", "bungalow", "farm", "residential", "terrace" -> 7.5
            "apartments", "dormitory", "hotel" -> 15.0
            "garage", "garages", "shed", "carport", "hut", "roof", "kiosk", "toilets", "service" -> 3.0
            "church", "cathedral", "chapel" -> 18.0
            "industrial", "warehouse", "retail", "supermarket", "barn", "farm_auxiliary" -> 8.0
            "commercial", "office", "school", "university", "hospital", "public", "civic", "government" -> 13.0
            else -> 9.0
        }
    }
}

internal fun Double.near(o: Double, eps: Double = 1e-9) = abs(this - o) < eps
