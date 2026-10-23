package de.foggymap.app.core

import org.locationtech.jts.geom.GeometryFactory

/**
 * "Radius" mode: everything within a radius around your path becomes visible – straight from GPS,
 * so it appears at once, also before (or without) map data. Consecutive positions are joined to a
 * band, so moving fast leaves no holes. Standing still adds nothing new.
 */
class RadiusRevealer {
    private var last: Fix? = null
    private val gf = GeometryFactory()

    fun onFix(fix: Fix, radius: Double): List<RevealItem> {
        if (fix.accuracy > 30.0 || radius < 10.0) return emptyList()
        val prev = last
        val step = maxOf(radius / 3, 10.0)
        if (prev != null && fix.timeMs > prev.timeMs && Geo.distance(prev.pos, fix.pos) < step) return emptyList()
        last = fix
        val proj = LocalProjection(fix.pos)
        val join = prev != null && fix.timeMs - prev.timeMs in 1..90_000 &&
            Geo.distance(prev.pos, fix.pos) < 4 * radius + 120
        val geom = if (join) gf.createLineString(arrayOf(proj.toLocal(prev!!.pos), proj.toLocal(fix.pos)))
            else gf.createPoint(proj.toLocal(fix.pos))
        val area = geom.buffer(radius, 10)
        val key = ((Math.round(fix.lat * 1e5) * 31 + Math.round(fix.lon * 1e5)) and 0xffffffffL).toString(36)
        val item = RevealItem("s:c${fix.timeMs.toString(36)}.$key", RevealKind.AREA, RevealItem.polygonsOf(area, proj, 0.5))
        return if (item.polygons.isEmpty()) emptyList() else listOf(item)
    }

    fun reset() { last = null }
}
