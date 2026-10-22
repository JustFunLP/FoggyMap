package de.foggymap.app.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the pipeline on a real OpenStreetMap extract (Helsinki city centre, © OpenStreetMap
 * contributors, ODbL; converted from the pyrosm test file to Overpass JSON).
 */
class RealOsmDataTest {
    private val box = BBox(60.1641, 24.9351, 60.1792, 24.9535)
    private val model by lazy {
        val json = javaClass.classLoader!!.getResource("helsinki_overpass.json")!!.readText()
        WorldModel(OsmParser.parse(json, box))
    }

    @Test fun buildsNetworkBlocksAndBuildings() {
        val t0 = System.nanoTime()
        val m = model
        val ms = (System.nanoTime() - t0) / 1e6
        println("Helsinki: ${m.segments.size} segments, ${m.faces.size} faces, ${m.buildings.size} buildings in ${ms.toInt()} ms")
        assertTrue(m.segments.size > 500)
        assertTrue(m.faces.size > 30)
        assertTrue(m.buildings.size > 100)
    }

    @Test fun everyStreetSegmentRevealsAPlausibleAmount() {
        val m = model
        var withBlocks = 0; var total = 0; var maxArea = 0.0
        val t0 = System.nanoTime()
        for (s in m.segments.filter { it.matchable && it.roadClass.vehicular }.take(400)) {
            val items = m.reveal(s)
            total++
            if (items.any { it.kind == RevealKind.BLOCK }) withBlocks++
            val fog = FogBuilder(60.17).apply { add(items) }
            maxArea = maxOf(maxArea, fog.revealedArea())
            assertTrue("segment ${s.id} revealed ${items.count { it.kind == RevealKind.BLOCK }} blocks",
                items.count { it.kind == RevealKind.BLOCK } <= 4)
        }
        val ms = (System.nanoTime() - t0) / 1e6 / total
        println("Helsinki: $withBlocks/$total vehicular segments reveal blocks; max revealed area ${maxArea.toInt()} m²; ${"%.1f".format(ms)} ms/segment")
        assertTrue(withBlocks > total / 3)
        // A single segment should never uncover more than ~2 big blocks worth of area.
        assertTrue(maxArea < 2 * WorldModel.MAX_BLOCK_AREA + 60_000)
    }

    @Test fun simulatedWalkOnRealStreets() {
        val m = model
        val e = Explorer(emptyList()).apply { addCell(CellKey(0, 0), m) }
        // Walk along the longest residential segment chain: take a random long segment and follow its points.
        val seg = m.segments.filter { it.matchable && it.roadClass.vehicular && it.length in 80.0..300.0 }.maxBy { it.length }
        var t = 0L
        val rnd = java.util.Random(1)
        val revealed = HashSet<String>()
        val pts = seg.points
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val n = maxOf(1, (Geo.distance(a, b) / 3).toInt())
            for (k in 0 until n) {
                val f = k.toDouble() / n
                val lat = a.lat + (b.lat - a.lat) * f + rnd.nextGaussian() * 0.00003
                val lon = a.lon + (b.lon - a.lon) * f + rnd.nextGaussian() * 0.00005
                t += 2000
                e.onFix(Fix(lat, lon, 8.0, t)).segments.forEach { revealed += it.id }
            }
        }
        println("Helsinki walk on ${seg.name} (${seg.length.toInt()} m): revealed $revealed")
        assertTrue(seg.id in revealed)
        assertTrue("walk revealed too many segments: $revealed", revealed.size <= 3)
    }
}
