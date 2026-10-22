package de.foggymap.app.core

/** Small synthetic OSM worlds in local metres around a German-latitude origin. */
object TestWorlds {
    val origin = LatLon(51.0, 7.0)
    private val proj = LocalProjection(origin)

    fun ll(x: Double, y: Double): LatLon = proj.toGeo(org.locationtech.jts.geom.Coordinate(x, y))

    class Builder {
        val ways = ArrayList<OsmWay>()
        private val nodeIds = HashMap<Pair<Long, Long>, Long>()
        private var nextNode = 1L
        private var nextWay = 1000L

        /** Same metric position ⇒ same OSM node (that is how OSM models junctions). */
        private fun node(x: Double, y: Double): Long =
            nodeIds.getOrPut(Math.round(x * 10) to Math.round(y * 10)) { nextNode++ }

        fun way(tags: Map<String, String>, vararg xy: Pair<Double, Double>): Long {
            val id = nextWay++
            ways += OsmWay(id, LongArray(xy.size) { node(xy[it].first, xy[it].second) },
                xy.map { ll(it.first, it.second) }, tags)
            return id
        }

        fun street(vararg xy: Pair<Double, Double>, cls: String = "residential", name: String? = null) =
            way(buildMap { put("highway", cls); name?.let { put("name", it) } }, *xy)

        fun building(x: Double, y: Double, w: Double = 12.0, h: Double = 10.0, levels: Int? = null): Long {
            val pts = listOf(x to y, x + w to y, x + w to y + h, x to y + h, x to y)
            // Buildings get their own nodes (not shared with streets).
            val id = nextWay++
            val base = -id * 10
            ways += OsmWay(id, LongArray(pts.size) { if (it == pts.lastIndex) base else base - it },
                pts.map { ll(it.first, it.second) },
                buildMap { put("building", "yes"); levels?.let { put("building:levels", "$it") } })
            return id
        }

        fun build(box: BBox = BBox(origin.lat - 0.02, origin.lon - 0.03, origin.lat + 0.02, origin.lon + 0.03)) =
            WorldModel(OsmData(ways, box))
    }

    /**
     * A 4 × 4 street grid with 100 m spacing (x = 0..300, y = 0..300) → 9 blocks of ~1 ha,
     * one building in the middle of every block.
     */
    fun grid(withBuildings: Boolean = true): WorldModel {
        val b = Builder()
        val s = 100.0
        for (r in 0..3) b.street(*(0..3).map { c -> c * s to r * s }.toTypedArray(), name = "Ost-West $r")
        for (c in 0..3) b.street(*(0..3).map { r -> c * s to r * s }.toTypedArray(), name = "Nord-Süd $c")
        if (withBuildings) for (r in 0..2) for (c in 0..2) b.building(c * s + 40, r * s + 40, 20.0, 20.0, levels = 3)
        return b.build()
    }
}
