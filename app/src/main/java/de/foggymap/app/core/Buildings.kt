package de.foggymap.app.core

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.index.strtree.STRtree
import org.locationtech.jts.operation.union.UnaryUnionOp

/** A building footprint in geographic coordinates. */
class BuildingFootprint(val id: Long, val ring: List<LatLon>, val height: Double, val minHeight: Double)

object BuildingParser {
    fun parse(data: OsmData): List<BuildingFootprint> = data.ways.mapNotNull { w ->
        val b = w.tags["building"] ?: return@mapNotNull null
        if (b == "no" || w.points.size < 4) return@mapNotNull null
        val f = w.points.first(); val l = w.points.last()
        val closed = if (w.nodes.isNotEmpty()) w.nodes.first() == w.nodes.last()
            else Math.abs(f.lat - l.lat) < 1e-9 && Math.abs(f.lon - l.lon) < 1e-9
        if (!closed) return@mapNotNull null
        val minH = WorldModel.parseMeters(w.tags["min_height"]) ?: w.tags["building:min_level"]?.toDoubleOrNull()?.times(3.1) ?: 0.0
        val h = WorldModel.parseMeters(w.tags["height"])
            ?: w.tags["building:levels"]?.toDoubleOrNull()?.let { it * 3.1 + if (w.tags["roof:shape"].let { r -> r != null && r != "flat" }) 2.5 else 0.6 }
            ?: WorldModel.defaultHeight(b)
        val hh = h.coerceIn(2.5, 400.0)
        BuildingFootprint(w.id, w.points, hh, minH.coerceIn(0.0, hh - 1))
    }
}

/**
 * A building becomes visible when it lies inside revealed ground: its interior point is inside, or
 * at least 40 % of its footprint is covered. Works for own reveals and for areas synced from friends.
 */
object BuildingRevealer {
    /**
     * [existing]: buildings already shown (also from an older data source with other ids); a building
     * whose centre lies inside one of them is not added a second time.
     */
    fun reveal(buildings: Collection<BuildingFootprint>, revealed: Collection<RevealItem>,
               existing: Collection<RevealItem> = emptyList()): List<RevealItem> {
        val areas = revealed.filter { it.kind != RevealKind.BUILDING }
        if (buildings.isEmpty() || areas.isEmpty()) return emptyList()
        val all = areas.flatMap { it.polygons.flatMap { p -> p.first() } }
        val origin = LatLon(all.sumOf { it.lat } / all.size, all.sumOf { it.lon } / all.size)
        val proj = LocalProjection(origin)
        val gf = GeometryFactory()
        val shown = STRtree()
        val existingIds = HashSet<String>()
        for (it in existing) {
            if (it.kind != RevealKind.BUILDING) continue
            existingIds += it.id
            for (poly in it.polygons) try {
                val g = gf.createPolygon(closed(poly[0]).map(proj::toLocal).toTypedArray())
                shown.insert(g.envelopeInternal, g)
            } catch (_: Exception) { }
        }
        val geoms = ArrayList<Geometry>()
        for (it in areas) for (poly in it.polygons) try {
            val shell = gf.createLinearRing(closed(poly[0]).map(proj::toLocal).toTypedArray())
            val holes = poly.drop(1).map { h -> gf.createLinearRing(closed(h).map(proj::toLocal).toTypedArray()) }.toTypedArray()
            val g = gf.createPolygon(shell, holes)
            geoms += if (g.isValid) g else g.buffer(0.0)
        } catch (_: Exception) { }
        if (geoms.isEmpty()) return emptyList()
        val area = try { UnaryUnionOp.union(geoms) } catch (_: Exception) { UnaryUnionOp.union(geoms.map { it.buffer(0.0) }) }
        val env = area.envelopeInternal

        val index = STRtree()
        val shapes = HashMap<Long, Pair<BuildingFootprint, Polygon>>()
        for (b in buildings) {
            if (b.id in shapes) continue
            val poly = try { gf.createPolygon(closed(b.ring).map(proj::toLocal).toTypedArray()) } catch (_: Exception) { null } ?: continue
            val valid = if (poly.isValid) poly else (try { poly.buffer(0.0) as? Polygon } catch (_: Exception) { null } ?: continue)
            if (valid.area < 6.0 || !valid.envelopeInternal.intersects(env)) continue
            shapes[b.id] = b to valid
            index.insert(valid.envelopeInternal, b.id)
        }
        val out = ArrayList<RevealItem>()
        @Suppress("UNCHECKED_CAST")
        for (id in index.query(env) as List<Long>) {
            val (b, poly) = shapes[id] ?: continue
            if (!poly.intersects(area)) continue
            val inside = try {
                area.contains(poly.interiorPoint) || poly.intersection(area).area >= 0.4 * poly.area
            } catch (_: Exception) { false }
            if (!inside) continue
            if ("g:${b.id}" in existingIds) continue
            val centre = poly.interiorPoint
            @Suppress("UNCHECKED_CAST")
            if ((shown.query(centre.envelopeInternal) as List<Geometry>).any { g -> try { g.contains(centre) } catch (_: Exception) { false } }) continue
            val item = RevealItem("g:${b.id}", RevealKind.BUILDING, RevealItem.polygonsOf(poly.buffer(0.8), proj, 0.3),
                height = b.height, minHeight = b.minHeight)
            if (item.polygons.isNotEmpty()) out += item
        }
        return out
    }

    private fun closed(r: List<LatLon>) = if (r.first() == r.last()) r else r + r.first()

    /** Bounding box of reveal items (to know which building tiles are needed). */
    fun bboxOf(items: Collection<RevealItem>): BBox? {
        var s = 90.0; var w = 180.0; var n = -90.0; var e = -180.0
        var any = false
        for (it in items) for (p in it.polygons) for (pt in p.first()) {
            any = true
            if (pt.lat < s) s = pt.lat; if (pt.lat > n) n = pt.lat
            if (pt.lon < w) w = pt.lon; if (pt.lon > e) e = pt.lon
        }
        return if (any) BBox(s, w, n, e) else null
    }
}
