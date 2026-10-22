package de.foggymap.app.core

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.MultiLineString
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier
import org.locationtech.jts.simplify.TopologyPreservingSimplifier

/**
 * Keeps the union of everything revealed and turns it into GeoJSON for the map:
 * fog layers (world minus the revealed area, feathered by growing buffers), the glowing fog edge,
 * explored street lines and revealed 3D buildings.
 *
 * Geometry is handled in a linear lon/lat-scaled metric frame around a fixed anchor latitude, so
 * the world rectangle maps exactly and buffer distances are metres around that latitude.
 */
class FogBuilder(anchorLat: Double, private val levels: DoubleArray = doubleArrayOf(0.0, 9.0, 24.0)) {
    private val proj = LocalProjection(LatLon(anchorLat, 0.0))
    private val gf = GeometryFactory()
    /**
     * One cumulative union per feather level. buffer(A ∪ B) = buffer(A) ∪ buffer(B), so each level is
     * maintained incrementally by buffering only the *new* items – adding a reveal stays cheap even
     * after thousands of explored streets.
     */
    private val unions = Array<Geometry>(levels.size) { gf.createPolygon() }
    private val revealed: Geometry get() = unions[0]
    private val roads = LinkedHashMap<String, RevealItem>()
    private val buildings = LinkedHashMap<String, RevealItem>()
    private val known = HashSet<String>()

    val itemCount get() = known.size

    /** Adds items; returns true when anything new was included. */
    fun add(items: Collection<RevealItem>): Boolean {
        val fresh = items.filter { known.add(it.id) }
        if (fresh.isEmpty()) return false
        val geoms = ArrayList<Geometry>(fresh.size + 1)
        for (it in fresh) {
            toGeometry(it)?.let(geoms::add)
            when (it.kind) {
                RevealKind.ROAD -> roads[it.id] = it
                RevealKind.BUILDING -> buildings[it.id] = it
                else -> {}
            }
        }
        if (geoms.isEmpty()) return true
        val batch = safeUnion(geoms)
        for ((i, d) in levels.withIndex()) {
            val grown = if (d == 0.0) batch else batch.buffer(d, 4)
            val merged = try {
                if (unions[i].isEmpty) grown else unions[i].union(grown)
            } catch (_: Exception) {
                unions[i].buffer(0.0).union(grown.buffer(0.0))
            }
            unions[i] = TopologyPreservingSimplifier.simplify(merged, if (d == 0.0) 0.3 else 0.8)
        }
        return true
    }

    private fun safeUnion(geoms: List<Geometry>): Geometry =
        try { UnaryUnionOp.union(geoms) } catch (_: Exception) { UnaryUnionOp.union(geoms.map { g -> g.buffer(0.0) }) }

    fun clear() {
        for (i in unions.indices) unions[i] = gf.createPolygon()
        roads.clear(); buildings.clear(); known.clear()
    }

    /** Fog = world minus revealed area grown by [growMeters] (must be one of the configured levels). */
    fun fogGeoJson(growMeters: Double): String {
        val world = worldRect()
        val idx = levels.indexOfFirst { it == growMeters }
        val hole = if (idx >= 0) unions[idx] else if (revealed.isEmpty) revealed else revealed.buffer(growMeters, 4)
        if (hole.isEmpty) return featureCollection(listOf(feature(polygonJson(world))))
        val fog = try { world.difference(hole) } catch (_: Exception) { world.difference(hole.buffer(0.0)) }
        return featureCollection(listOf(feature(geometryJson(fog))))
    }

    /** Soft outline of the revealed area, drawn as a violet glow. */
    fun edgeGeoJson(): String {
        if (revealed.isEmpty) return EMPTY
        val boundary = DouglasPeuckerSimplifier.simplify(revealed.boundary, 0.8)
        return featureCollection(listOf(feature(geometryJson(boundary))))
    }

    fun roadsGeoJson(): String = featureCollection(roads.values.mapNotNull { r ->
        val l = r.line ?: return@mapNotNull null
        feature(lineJson(l), "\"id\":\"${r.id}\"")
    })

    fun buildingsGeoJson(only: Collection<RevealItem>? = null): String =
        featureCollection((only ?: buildings.values).filter { it.kind == RevealKind.BUILDING }.map { b ->
            feature(multiPolygonJson(b.polygons), "\"h\":${b.height},\"mh\":${b.minHeight}")
        })

    fun itemsGeoJson(items: Collection<RevealItem>): String =
        featureCollection(items.filter { it.kind != RevealKind.BUILDING }.map { feature(multiPolygonJson(it.polygons)) })

    /** Revealed area in square metres (approximate, for statistics). */
    fun revealedArea(): Double = revealed.area

    fun containsPoint(p: LatLon): Boolean = !revealed.isEmpty && revealed.contains(gf.createPoint(proj.toLocal(p)))

    // ----------------------------------------------------------------- geometry/json helpers

    private fun toGeometry(item: RevealItem): Geometry? = try {
        val polys = item.polygons.mapNotNull { rings ->
            val shell = gf.createLinearRing(closed(rings[0].map(proj::toLocal)))
            val holes = rings.drop(1).map { gf.createLinearRing(closed(it.map(proj::toLocal))) }.toTypedArray()
            gf.createPolygon(shell, holes).let { if (it.isValid) it else it.buffer(0.0) }
        }
        if (polys.isEmpty()) null else UnaryUnionOp.union(polys)
    } catch (_: Exception) { null }

    private fun closed(c: List<Coordinate>): Array<Coordinate> =
        if (c.first().equals2D(c.last())) c.toTypedArray() else (c + c.first()).toTypedArray()

    private fun worldRect(): Polygon {
        val sw = proj.toLocal(LatLon(-85.0, -180.0)); val ne = proj.toLocal(LatLon(85.0, 180.0))
        return gf.createPolygon(arrayOf(Coordinate(sw.x, sw.y), Coordinate(ne.x, sw.y), Coordinate(ne.x, ne.y),
            Coordinate(sw.x, ne.y), Coordinate(sw.x, sw.y)))
    }

    private fun geometryJson(g: Geometry): String = when (g) {
        is Polygon -> polygonJson(g)
        is MultiPolygon -> "{\"type\":\"MultiPolygon\",\"coordinates\":[" +
            (0 until g.numGeometries).joinToString(",") { ringsOf(g.getGeometryN(it) as Polygon) } + "]}"
        is LineString -> "{\"type\":\"LineString\",\"coordinates\":${coords(g.coordinates.toList())}}"
        is MultiLineString -> "{\"type\":\"MultiLineString\",\"coordinates\":[" +
            (0 until g.numGeometries).joinToString(",") { coords(g.getGeometryN(it).coordinates.toList()) } + "]}"
        else -> {
            val parts = (0 until g.numGeometries).map { g.getGeometryN(it) }
            val polys = parts.filterIsInstance<Polygon>()
            if (polys.isNotEmpty()) "{\"type\":\"MultiPolygon\",\"coordinates\":[" + polys.joinToString(",") { ringsOf(it) } + "]}"
            else "{\"type\":\"MultiLineString\",\"coordinates\":[" +
                parts.filterIsInstance<LineString>().joinToString(",") { coords(it.coordinates.toList()) } + "]}"
        }
    }

    private fun polygonJson(p: Polygon) = "{\"type\":\"Polygon\",\"coordinates\":${ringsOf(p)}}"
    private fun ringsOf(p: Polygon): String {
        val rings = ArrayList<String>()
        rings += coords(p.exteriorRing.coordinates.toList())
        for (i in 0 until p.numInteriorRing) rings += coords(p.getInteriorRingN(i).coordinates.toList())
        return "[" + rings.joinToString(",") + "]"
    }

    private fun coords(cs: List<Coordinate>): String {
        val sb = StringBuilder(cs.size * 24).append('[')
        cs.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            val g = proj.toGeo(c)
            sb.append('[').append(Geo.round6(g.lon)).append(',').append(Geo.round6(g.lat)).append(']')
        }
        return sb.append(']').toString()
    }

    companion object {
        const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"

        fun featureCollection(features: List<String>) =
            "{\"type\":\"FeatureCollection\",\"features\":[" + features.joinToString(",") + "]}"

        fun feature(geometry: String, props: String = "") =
            "{\"type\":\"Feature\",\"properties\":{$props},\"geometry\":$geometry}"

        fun lineJson(pts: List<LatLon>) = "{\"type\":\"LineString\",\"coordinates\":[" +
            pts.joinToString(",") { "[${Geo.round6(it.lon)},${Geo.round6(it.lat)}]" } + "]}"

        fun multiPolygonJson(polys: List<List<Ring>>) = "{\"type\":\"MultiPolygon\",\"coordinates\":[" +
            polys.joinToString(",") { rings ->
                "[" + rings.joinToString(",") { r ->
                    "[" + r.joinToString(",") { "[${Geo.round6(it.lon)},${Geo.round6(it.lat)}]" } + "]"
                } + "]"
            } + "]}"

        fun pointJson(p: LatLon) = "{\"type\":\"Point\",\"coordinates\":[${Geo.round6(p.lon)},${Geo.round6(p.lat)}]}"

    }
}
