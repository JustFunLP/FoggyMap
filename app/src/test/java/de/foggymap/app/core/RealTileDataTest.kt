package de.foggymap.app.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * The street graph from real OpenFreeMap tiles (planet build 2026-10-18) compared with the Overpass
 * data of the same cell, downloaded the same day (© OpenStreetMap contributors, ODbL):
 * Odenthal (village, Bergisches Land) and Berlin-Prenzlauer Berg (dense city, separately mapped sidewalks).
 */
class RealTileDataTest {
    private class Area(val name: String, val cell: CellKey) {
        val tiles by lazy {
            cell.tiles().associateWith { k -> Mvt.decode(res("tiles/$name/14_${k.x}_${k.y}.pbf.gz"), TileNetwork.LAYERS) }
        }
        val output by lazy { TileNetwork.build(tiles, cell.queryBox()) }
        val tileModel by lazy { WorldModel(output.data) }
        val osmModel by lazy { WorldModel(OsmParser.parse(String(res("tiles/$name/overpass_roads.json.gz")), cell.queryBox())) }
        private fun res(path: String): ByteArray =
            GZIPInputStream(javaClass.classLoader!!.getResourceAsStream(path)!!).use { it.readBytes() }
    }

    private val odenthal = Area("odenthal", CellKey(8515, 5482))
    private val berlin = Area("berlin", CellKey(8802, 5371))
    private val areas = listOf(odenthal, berlin)

    private fun inner(a: Area) = a.cell.core().expand(-200.0)

    @Test fun everyRealJunctionIsFound() {
        for (a in areas) {
            val core = inner(a)
            val deg = HashMap<Long, Int>(); val pos = HashMap<Long, LatLon>()
            for (w in a.osmModel.data.ways) {
                val rc = RoadClass.of(w.tags) ?: continue
                if (!rc.vehicular || RoadNetworkBuilder.isElevated(w.tags)) continue
                for ((i, n) in w.nodes.withIndex()) { deg[n] = (deg[n] ?: 0) + if (i == 0 || i == w.nodes.lastIndex) 1 else 2; pos[n] = w.points[i] }
            }
            val junctions = deg.filter { it.value >= 3 && core.contains(pos[it.key]!!) }.keys.map { pos[it]!! }
            val ends = a.tileModel.segments.filter { it.roadClass.vehicular && !it.elevated }.flatMap { listOf(it.points.first(), it.points.last()) }
            val found = junctions.count { j -> ends.any { Geo.distance(it, j) < 4.0 } }
            println("${a.name}: $found of ${junctions.size} junctions found")
            assertTrue("${a.name}: only $found of ${junctions.size} junctions", found >= 0.98 * junctions.size)
            assertTrue(junctions.size > 40)
        }
    }

    @Test fun noStreetIsMissing() {
        for (a in areas) {
            val core = inner(a)
            val missing = a.osmModel.segments.filter { s -> s.matchable && s.roadClass.vehicular && core.contains(s.points[s.points.size / 2]) }
                .filter { s -> a.tileModel.candidates(s.points[s.points.size / 2], 6.0).none { it.segment.roadClass.vehicular } }
            println("${a.name}: ${missing.size} OSM street segments without counterpart")
            assertTrue("${a.name}: missing ${missing.map { it.name }}", missing.sumOf { it.length } < 100.0)
        }
    }

    @Test fun separatelyMappedSidewalksAreRemoved() {
        val a = berlin
        val core = inner(a)
        val proj = a.tileModel.proj
        val gf = org.locationtech.jts.geom.GeometryFactory()
        val sidewalks = a.osmModel.data.ways.filter { it.tags["footway"] == "sidewalk" }
            .map { w -> gf.createLineString(w.points.map(proj::toLocal).toTypedArray()) }
        val sidewalkLen = sidewalks.sumOf { l -> l.intersection(gf.toGeometry(org.locationtech.jts.geom.Envelope(
            proj.toLocal(LatLon(core.south, core.west)), proj.toLocal(LatLon(core.north, core.east))))).length }
        var leaked = 0.0
        for (s in a.tileModel.segments.filter { it.roadClass == RoadClass.PATH && core.contains(it.points[it.points.size / 2]) }) {
            val mid = gf.createPoint(proj.toLocal(s.points[s.points.size / 2]))
            if (sidewalks.any { it.distance(mid) < 3.0 }) leaked += s.length
        }
        println("Berlin: ${leaked.toInt()} m of path on ${sidewalkLen.toInt()} m of OSM sidewalks remain")
        assertTrue(sidewalkLen > 5000)
        assertTrue("too many sidewalks left: $leaked of $sidewalkLen m", leaked < 0.1 * sidewalkLen)
    }

    @Test fun cityBlocksMatchOverpass() {
        for (a in areas) {
            val core = a.cell.core()
            fun blocks(m: WorldModel) = m.faces.count { it.reliable && it.area in WorldModel.MIN_BLOCK_AREA..WorldModel.MAX_BLOCK_AREA &&
                core.contains(m.proj.toGeo(it.polygon.interiorPoint.coordinate)) }
            val t = blocks(a.tileModel); val o = blocks(a.osmModel)
            println("${a.name}: $t blocks from tiles, $o from Overpass")
            assertTrue("${a.name}: $t vs $o blocks", kotlin.math.abs(t - o) <= maxOf(3, o / 10))
        }
    }

    @Test fun streetsBetweenJunctionsAreNotCutInPieces() {
        // OSM splits ways where only an attribute changes; segments must still run junction to junction.
        val a = berlin
        val core = inner(a)
        val osmEnds = HashMap<Long, Int>(); val pos = HashMap<Long, LatLon>()
        for (w in a.osmModel.data.ways) {
            val rc = RoadClass.of(w.tags) ?: continue
            if (!rc.vehicular) continue
            for ((i, n) in w.nodes.withIndex()) { osmEnds[n] = (osmEnds[n] ?: 0) + if (i == 0 || i == w.nodes.lastIndex) 1 else 2; pos[n] = w.points[i] }
        }
        val real = osmEnds.filter { it.value != 2 }.keys.map { pos[it]!! }
        val ends = a.tileModel.segments.filter { it.roadClass.vehicular && it.matchable }
            .flatMap { listOf(it.points.first(), it.points.last()) }.filter { core.contains(it) }.distinct()
        val spurious = ends.count { e -> real.none { Geo.distance(it, e) < 4.0 } }
        println("Berlin: $spurious of ${ends.size} segment ends are not at a junction or dead end")
        assertTrue("$spurious of ${ends.size}", spurious <= 0.25 * ends.size)
    }

    @Test fun walkingARealStreetRevealsJustThatStreet() {
        val a = berlin
        val m = a.tileModel
        val core = inner(a)
        val seg = m.segments.filter { it.matchable && it.roadClass == RoadClass.RESIDENTIAL && it.length in 90.0..250.0 && core.contains(it.points[0]) }
            .maxBy { it.length }
        val e = Explorer(emptyList()).apply { addCell(a.cell, m) }
        val rnd = java.util.Random(3)
        var t = 0L
        val revealed = HashSet<String>()
        val pts = seg.points
        for (i in 0 until pts.size - 1) {
            val p = pts[i]; val q = pts[i + 1]
            val n = maxOf(1, (Geo.distance(p, q) / 3).toInt())
            for (k in 0 until n) {
                val f = k.toDouble() / n
                t += 2200
                // Walking on the pavement, ~6 m beside the centre line, with GPS noise.
                val lat = p.lat + (q.lat - p.lat) * f + rnd.nextGaussian() * 0.00002 + 0.00004
                val lon = p.lon + (q.lon - p.lon) * f + rnd.nextGaussian() * 0.00003
                e.onFix(Fix(lat, lon, 7.0, t)).segments.forEach { revealed += it.id }
            }
        }
        println("Berlin walk on ${seg.name} (${seg.length.toInt()} m): revealed ${revealed.size} segment(s)")
        assertTrue("${seg.name} not revealed", seg.id in revealed)
        assertTrue("revealed too much: $revealed", revealed.size <= 3)
        val items = m.reveal(seg)
        assertTrue("street reveals its blocks", items.count { it.kind == RevealKind.BLOCK } in 1..4)
        assertTrue(items.all { RevealItem.ID_PATTERN.matches(it.id) })
    }

    @Test fun buildingsComeFromTheSameTiles() {
        for (a in areas) {
            val b = a.output.buildings
            println("${a.name}: ${b.size} buildings")
            assertTrue(b.size > 1000)
            assertTrue(b.map { it.id }.toSet().size == b.size)
            // Streets in built-up blocks show their buildings – and never a big part of the town at once.
            val m = a.tileModel
            val core = inner(a)
            val segs = m.segments.filter { s -> s.matchable && s.roadClass.vehicular && core.contains(s.points[s.points.size / 2]) &&
                m.adjacentFaces(s).any { it.reliable && it.area in 2_000.0..WorldModel.MAX_BLOCK_AREA } }.take(120)
            var withBuildings = 0; var most = 0
            for (s in segs) { val n = BuildingRevealer.reveal(b, m.reveal(s)).size; if (n > 0) withBuildings++; most = maxOf(most, n) }
            println("${a.name}: ${withBuildings}/${segs.size} block streets show buildings, at most $most")
            assertTrue(segs.size >= 10)
            assertTrue("${a.name}: $withBuildings of ${segs.size}", withBuildings >= segs.size / 2)
            assertTrue(most < b.size / 10)
        }
    }

    @Test fun streetsRevealedWithTheOldDataSourceCountAsKnown() {
        // Version ≤ 1.0.21 revealed streets with Overpass ids. They must not be revealed (and counted) again.
        val a = berlin
        val core = inner(a)
        val oldSegs = a.osmModel.segments.filter { it.matchable && it.roadClass.vehicular && core.contains(it.points[it.points.size / 2]) }
        val oldRoads = oldSegs.flatMap { a.osmModel.reveal(it) }.filter { it.kind == RevealKind.ROAD }
        val e = Explorer(oldRoads.map { it.id }).apply { addCell(a.cell, a.tileModel) }
        val known = e.markCovered(a.tileModel, oldRoads)
        val walked = a.tileModel.segments.filter { it.matchable && it.roadClass == RoadClass.RESIDENTIAL && it.length in 60.0..300.0 &&
            it.points.all { p -> core.contains(p) } }.take(8)
        var t = 0L
        val again = HashSet<String>()
        for (s in walked) for (i in 0 until s.points.size - 1) {
            val p = s.points[i]; val q = s.points[i + 1]
            val n = maxOf(1, (Geo.distance(p, q) / 3).toInt())
            for (k in 0 until n) { val f = k.toDouble() / n; t += 2200
                e.onFix(Fix(p.lat + (q.lat - p.lat) * f, p.lon + (q.lon - p.lon) * f, 6.0, t)).segments.forEach { again += it.id } }
            t += 120_000   // jump to the next street without walking in between
        }
        println("Berlin: $known tile segments recognised as already revealed; walking ${walked.size} streets revealed ${again.size} again")
        assertTrue(known > 50)
        assertTrue("revealed again: $again", again.isEmpty())
    }

    @Test fun sparseFixesByCarRevealTheWholeRoute() {
        // Battery saver at 50 km/h: one fix every ~60 m along a real Berlin route of several streets.
        val a = berlin
        val m = a.tileModel
        val core = inner(a)
        val byNode = HashMap<Long, MutableList<Segment>>()
        val cars = m.segments.filter { it.matchable && it.roadClass.vehicular && it.roadClass != RoadClass.PEDESTRIAN }
        for (s in cars) { byNode.getOrPut(s.startNode) { ArrayList() }.add(s); byNode.getOrPut(s.endNode) { ArrayList() }.add(s) }
        val start = cars.filter { it.roadClass == RoadClass.RESIDENTIAL && it.points.all { p -> core.contains(p) } }.maxBy { it.length }
        val route = arrayListOf(start)
        var node = start.endNode
        var pts = ArrayList(start.points)
        while (route.sumOf { it.length } < 1400 && route.size < 12) {
            val nextOpts = (byNode[node] ?: break).filter { s -> s.id !in route.map { it.id } && s.points.all { core.contains(it) } }
            val next = nextOpts.minByOrNull { it.length } ?: break
            val fwd = next.startNode == node
            pts.addAll((if (fwd) next.points else next.points.asReversed()).drop(1))
            node = if (fwd) next.endNode else next.startNode
            route += next
        }
        fun drive(spacing: Double, fill: Boolean): Set<String> {
            val e = Explorer(emptyList()).apply { addCell(a.cell, m); gapFillEnabled = fill }
            var t = 0L; var since = spacing
            val got = HashSet<String>()
            val rnd = java.util.Random(7)
            for (i in 0 until pts.size - 1) {
                val p = pts[i]; val q = pts[i + 1]
                val len = Geo.distance(p, q)
                var d = 0.0
                while (d < len) {
                    if (since >= spacing) {
                        since = 0.0; t += (spacing / 14.0 * 1000).toLong()
                        val f = d / len
                        got += e.onFix(Fix(p.lat + (q.lat - p.lat) * f + rnd.nextGaussian() * 0.00002, p.lon + (q.lon - p.lon) * f + rnd.nextGaussian() * 0.00003,
                            6.0, t, 14.0, Geo.bearing(p, q))).segments.map { it.id }
                    }
                    d += 1.0; since += 1.0
                }
            }
            return got
        }
        val want = route.map { it.id }.toSet()
        for (spacing in listOf(60.0, 120.0, 180.0)) {
            val with = drive(spacing, true); val without = drive(spacing, false)
            println("Berlin drive, fix every ${spacing.toInt()} m over ${route.size} streets (${route.sumOf { it.length }.toInt()} m): " +
                "with gap filling ${with.intersect(want).size} (+${(with - want).size} extra), matcher alone ${without.intersect(want).size} (+${(without - want).size} extra)")
            // 180 m (a fix every 13 s at 50 km/h) only happens when GPS drops out; then most of the way is still found.
            val need = if (spacing <= 120.0) want.size else want.size * 2 / 3
            assertTrue("spacing $spacing: revealed ${with.intersect(want).size} of ${want.size}", with.intersect(want).size >= need)
            assertTrue(with.intersect(want).size >= without.intersect(want).size)
            assertTrue("spacing $spacing: extra ${(with - want).size}", (with - want).size <= 2)
        }
    }

    @Test fun buildingTheCellIsFastEnough() {
        val times = HashMap<String, Long>()
        TileNetwork.timing = { stage, ms -> times[stage] = (times[stage] ?: 0) + ms }
        try {
            val a = Area("berlin", berlin.cell)
            val t0 = System.nanoTime()
            val out = TileNetwork.build(a.tiles, a.cell.queryBox())
            WorldModel(out.data)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("Berlin cell: $ms ms (${times})")
            assertTrue("street graph took $ms ms", ms < 5000)
        } finally { TileNetwork.timing = null }
    }
}
