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

enum class RevealMode { BLOCKS, STREET, RADIUS }

/** How much a walked street uncovers. [depth]: strip width per side (STREET) or radius (RADIUS) in metres. */
data class RevealSettings(val mode: RevealMode = RevealMode.BLOCKS, val depth: Double = 40.0)

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
    val faces: List<Face>

    private val segIndex = STRtree()
    private val faceIndex = STRtree()
    private val reliableEnvelope: Envelope

    init {
        val sw = proj.toLocal(LatLon(data.bbox.south, data.bbox.west))
        val ne = proj.toLocal(LatLon(data.bbox.north, data.bbox.east))
        reliableEnvelope = Envelope(sw.x + EDGE_MARGIN, ne.x - EDGE_MARGIN, sw.y + EDGE_MARGIN, ne.y - EDGE_MARGIN)
        segments.forEach { segIndex.insert(it.line.envelopeInternal, it) }
        faces = buildFaces()
        faces.forEach { faceIndex.insert(it.polygon.envelopeInternal, it) }
    }

    /** Railway lines of this cell (for "by train"): built on first use. */
    private val railIndex: STRtree by lazy {
        STRtree().apply {
            for (w in data.ways) if (w.tags["railway"] != null && w.points.size >= 2) try {
                val l = gf.createLineString(w.points.map { proj.toLocal(it) }.toTypedArray())
                insert(l.envelopeInternal, l)
            } catch (_: Exception) { }
        }
    }

    /** Distance to the nearest railway line, if one is within [max] metres. */
    fun railDistance(p: LatLon, max: Double): Double? {
        val c = proj.toLocal(p)
        val pt = gf.createPoint(c)
        @Suppress("UNCHECKED_CAST")
        val hits = railIndex.query(Envelope(c).apply { expandBy(max) }) as List<LineString>
        return hits.minOfOrNull { it.distance(pt) }?.takeIf { it <= max }
    }

    /** Distance to the nearest street a car can use, if one is within [max] metres. */
    fun roadDistance(p: LatLon, max: Double): Double? =
        candidates(p, max).filter { it.segment.roadClass.vehicular }.minOfOrNull { it.distance }

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
     * What becomes visible when [segment] is driven/walked, depending on the chosen [RevealMode]:
     *
     * BLOCKS (default): the street corridor between its two junctions, plus
     *  - every city block whose boundary runs along this segment (not blocks that merely touch the
     *    junction, so cross streets and parallel streets stay hidden) – up to [MAX_BLOCK_AREA];
     *    bigger blocks (industrial estates, parks, fields) only as a [STRIP_DEPTH] strip,
     *  - on a side without any block (lake shore, fields, edge of town) a [OPEN_DISTANCE] strip.
     * STREET: corridor plus a strip of [RevealSettings.depth] on both sides.
     * RADIUS: only the corridor here; the circle around your path comes from [RadiusRevealer].
     * Strips never reach across another street. Buildings are not part of the street data:
     * [BuildingRevealer] adds every building inside these areas.
     */
    fun reveal(segment: Segment, settings: RevealSettings = RevealSettings()): List<RevealItem> {
        val items = ArrayList<RevealItem>()
        val corridor = buffer(segment.line, segment.roadClass.corridorHalfWidth, BufferParameters.CAP_FLAT)
        items += RevealItem("r:${segment.id}", RevealKind.ROAD, RevealItem.polygonsOf(corridor, proj),
            line = segment.points, length = segment.length, name = segment.name)
        when (settings.mode) {
            RevealMode.RADIUS -> {}
            RevealMode.STREET -> {
                val d = settings.depth.coerceIn(10.0, 150.0)
                for (left in listOf(true, false)) sideStrip(segment, left, d, "w${d.toInt()}")?.let { items += it }
            }
            RevealMode.BLOCKS -> revealBlocks(segment, items)
        }
        // Tiny slivers can vanish completely when simplified – never emit an item without geometry.
        return items.filter { it.polygons.isNotEmpty() }
    }

    private fun revealBlocks(segment: Segment, items: MutableList<RevealItem>) {
        val adjacent = adjacentFaces(segment)
        for (f in adjacent) {
            if (f.reliable && f.area in MIN_BLOCK_AREA..MAX_BLOCK_AREA) {
                val inset = f.polygon.buffer(-BLOCK_INSET)
                if (inset.isEmpty) continue
                items += RevealItem("b:${f.id}", RevealKind.BLOCK, RevealItem.polygonsOf(inset, proj))
            } else {
                strip(segment, f, STRIP_DEPTH)?.let { (_, item) -> items += item }
            }
        }
        if (!segment.blockForming) {
            if (adjacent.isEmpty()) {
                // Footpath inside a park or courtyard: reveal a strip of the surrounding face.
                val mid = segment.line.interiorPoint
                @Suppress("UNCHECKED_CAST")
                (faceIndex.query(mid.envelopeInternal) as List<Face>).filter { it.polygon.contains(mid) }.forEach { f ->
                    strip(segment, f, PATH_STRIP_DEPTH)?.let { (_, item) -> items += item }
                }
            }
            return
        }
        if (adjacent.isEmpty()) { openStrip(segment)?.let { items += it }; return }
        // A side of the street without any block (lake shore, fields, edge of town) still shows what lies next to it.
        for (left in listOf(true, false)) {
            val probe = singleSided(segment.line, if (left) SIDE_PROBE else -SIDE_PROBE)
            if (probe.isEmpty) continue
            val covered = adjacent.sumOf { f -> safeArea { f.polygon.intersection(probe).area } }
            if (covered < 0.3 * probe.area) sideStrip(segment, left, OPEN_DISTANCE, "o")?.let { items += it }
        }
    }

    /**
     * Open settlement / country road: both sides up to [OPEN_DISTANCE], cut at every other street so
     * nothing across a neighbouring street becomes visible.
     */
    private fun openStrip(segment: Segment): RevealItem? {
        val strip = buffer(segment.line, OPEN_DISTANCE, BufferParameters.CAP_FLAT)
        val g = cutAtOtherStreets(segment, strip) ?: return null
        return RevealItem("s:${segment.id}~open", RevealKind.AREA, RevealItem.polygonsOf(g, proj))
    }

    /** Strip of [depth] on one side of the segment (left = left of its drawing direction), cut at other streets. */
    private fun sideStrip(segment: Segment, left: Boolean, depth: Double, tag: String): RevealItem? {
        val strip = singleSided(segment.line, if (left) depth else -depth)
        if (strip.isEmpty) return null
        val g = cutAtOtherStreets(segment, strip) ?: return null
        return RevealItem("s:${segment.id}~${if (left) "l" else "r"}$tag", RevealKind.AREA, RevealItem.polygonsOf(g, proj))
    }

    private fun cutAtOtherStreets(segment: Segment, strip: Geometry): Geometry? {
        @Suppress("UNCHECKED_CAST")
        val others = (segIndex.query(strip.envelopeInternal) as List<Segment>)
            .filter { it !== segment && it.id != segment.id && it.blockForming && it.line.intersects(strip) }
            .map { buffer(it.line, 2.5, BufferParameters.CAP_ROUND) }
        val cut = if (others.isEmpty()) strip else safe { strip.difference(UnaryUnionOp.union(others)) } ?: return null
        val core = buffer(segment.line, segment.roadClass.corridorHalfWidth + 1.0, BufferParameters.CAP_FLAT)
        val parts = (0 until cut.numGeometries).map { cut.getGeometryN(it) }.filter { it.intersects(core) && it.area > MIN_BLOCK_AREA }
        if (parts.isEmpty()) return null
        return UnaryUnionOp.union(parts)
    }

    private fun singleSided(line: LineString, distance: Double): Geometry = try {
        val params = BufferParameters(6, BufferParameters.CAP_FLAT, BufferParameters.JOIN_ROUND, 5.0).apply { isSingleSided = true }
        BufferOp.bufferOp(line, distance, params)
    } catch (_: Exception) { gf.createPolygon() }

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

    private fun safe(block: () -> Geometry): Geometry? = try { block() } catch (_: Exception) { null }
    private fun safeArea(block: () -> Double): Double = try { block() } catch (_: Exception) { 0.0 }

    private fun buffer(g: Geometry, d: Double, cap: Int): Geometry {
        val params = BufferParameters(6, cap, BufferParameters.JOIN_ROUND, 5.0)
        return BufferOp.bufferOp(g, d, params)
    }

    companion object {
        const val EDGE_MARGIN = 60.0
        const val MIN_BLOCK_AREA = 40.0
        /** 5 ha (Berlin perimeter blocks are 2–5 ha). Bigger faces (industrial estates, parks, fields) only get a strip. */
        const val MAX_BLOCK_AREA = 50_000.0
        const val BLOCK_INSET = 3.0
        const val STRIP_DEPTH = 50.0
        const val SIDE_PROBE = 4.0
        const val PATH_STRIP_DEPTH = 25.0
        const val ADJ_TOLERANCE = 2.0
        const val OPEN_DISTANCE = 30.0

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
