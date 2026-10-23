package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Street graph from vector tiles: tiles are generated like a tile server does (cut at tile edges
 * with a buffer, rounded to the 4096 grid, junction vertices on straight streets simplified away).
 */
class TileNetworkTest {
    // Origin on the boundary between tile A (west) and tile B (east), vertically in the middle of the tiles.
    private val a = TileKey.of(LatLon(51.0, 7.0))
    private val b = TileKey(a.x + 1, a.y)
    private val f = MvtTestTiles.Frame(LatLon(TileMath.lat((a.y + 0.5) * TileMath.E), TileMath.lon((a.x + 1) * TileMath.E)))

    private fun minor(vararg p: Pair<Double, Double>) = f.line("transportation", mapOf("class" to "minor"), *p)
    private fun footway(vararg p: Pair<Double, Double>) = f.line("transportation", mapOf("class" to "path", "subclass" to "footway"), *p)

    private val world = listOf(
        minor(-600.0 to 0.0, 600.0 to 0.0),                                // main street across the tile edge, no junction vertices
        minor(-250.0 to -300.0, -250.0 to 300.0),                           // crosses main without a shared vertex
        minor(250.0 to 0.0, 250.0 to 400.0),                                // T-junction, ends on main
        f.line("transportation", mapOf("class" to "primary", "brunnel" to "bridge", "layer" to 1), 100.0 to -300.0, 100.0 to 300.0),
        footway(-200.0 to 6.0, 200.0 to 6.0),                              // sidewalk along main
        footway(50.0 to 9.0, 50.0 to -9.0),                                 // zebra crossing
        footway(-450.0 to 300.0, -330.0 to 450.0),                          // path in a park
        f.line("transportation_name", mapOf("name" to "Lange Straße", "class" to "minor"), -600.0 to 0.0, 600.0 to 0.0),
        f.line("transportation_name", mapOf("name" to "Querweg", "class" to "minor"), -250.0 to -300.0, -250.0 to 300.0),
        f.rect("building", mapOf("render_height" to 12.0), -10.0, 30.0, 20.0, 12.0, id = 4711),   // cut by the tile edge
        f.rect("building", mapOf("render_height" to 7.0), -150.0, 30.0, 15.0, 10.0, id = 4712),
    )

    private fun cell(t: TileKey): TileNetwork.Output {
        val c = CellKey(t.x, t.y)
        return TileNetwork.build(MvtTestTiles.tiles(world, c.tiles()), c.queryBox())
    }

    private fun xOf(p: LatLon) = (TileMath.gx(p.lon) - f.ox) * f.mpu

    @Test fun decoderReadsWhatTheEncoderWrote() {
        val layers = Mvt.decode(MvtTestTiles.encodeTile(world, b))   // the bridge (x = 100 m) lies in tile B
        val t = layers["transportation"]!!
        assertEquals(4096, t.extent)
        assertTrue(t.features.any { it.str("class") == "primary" && it.str("brunnel") == "bridge" && it.num("layer") == 1.0 })
        val bld = Mvt.decode(MvtTestTiles.encodeTile(world, a))["building"]!!.features
        assertTrue(bld.any { it.id == 4712L && it.num("render_height") == 7.0 && it.polygons().size == 1 })
    }

    @Test fun streetsAreJoinedAcrossTileEdgesAndSplitAtRealJunctionsOnly() {
        val m = WorldModel(cell(a).data)
        val main = m.segments.filter { it.name == "Lange Straße" }.sortedBy { xOf(it.points.first()) + xOf(it.points.last()) }
        assertEquals("main street: split at the crossing and the T-junction, not at the tile edge or the bridge",
            3, main.size)
        assertEquals(500.0, main[1].length, 3.0)          // −250 … +250 m, across the tile edge and under the bridge
        assertTrue(main[1].complete)
        val cross = m.segments.filter { it.name == "Querweg" }
        assertEquals("the crossing street is split by the main street", 2, cross.size)
        val bridge = m.segments.filter { it.roadClass == RoadClass.PRIMARY }
        assertEquals(1, bridge.size)
        assertTrue(bridge[0].elevated)
        assertEquals(600.0, bridge[0].length, 3.0)
    }

    @Test fun sidewalksAndCrossingsAreDroppedParkPathsKept() {
        val m = WorldModel(cell(a).data)
        val paths = m.segments.filter { it.roadClass == RoadClass.PATH }
        assertEquals("only the park path remains: ${paths.map { it.points.first() }}", 1, paths.size)
        assertTrue(paths[0].length > 150)
    }

    @Test fun segmentIdsAreIdenticalInNeighbouringCells() {
        val ma = WorldModel(cell(a).data)
        val mb = WorldModel(cell(b).data)
        val ia = ma.segments.filter { it.complete }.map { it.id }.toSet()
        val ib = mb.segments.filter { it.complete }.map { it.id }.toSet()
        val common = ia.intersect(ib)
        // Middle main segment, T street, bridge and both halves of the crossing street are complete in both cells.
        assertTrue("common ids: $common", common.size >= 4)
        val mid = ma.segments.first { it.name == "Lange Straße" && it.complete && it.length in 490.0..510.0 }
        assertTrue(mid.id in ib)
        assertTrue(RevealItem.ID_PATTERN.matches("r:${mid.id}"))
        // Junction nodes match as well (matcher uses them for continuity).
        val midB = mb.segmentById[mid.id]!!
        assertEquals(setOf(mid.startNode, mid.endNode), setOf(midB.startNode, midB.endNode))
    }

    @Test fun buildingsCutByTheTileEdgeAreWholeAgain() {
        val out = cell(a)
        val big = out.buildings.filter { b -> b.ring.any { xOf(it) < -5 } && b.ring.any { xOf(it) > 5 } }
        assertEquals("the building across the tile edge is one footprint again", 1, big.size)
        val xs = big[0].ring.map { xOf(it) }
        assertEquals(-10.0, xs.minOrNull()!!, 0.6)
        assertEquals(10.0, xs.maxOrNull()!!, 0.6)
        assertEquals(12.0, big[0].height, 0.01)
        assertEquals(2, out.buildings.size)
        // Ids come from the position: identical when the neighbouring cell computes them.
        assertEquals(out.buildings.map { it.id }.toSet(), cell(b).buildings.map { it.id }.toSet())
    }

    @Test fun walkingTheMainStreetRevealsItAcrossTheTileEdge() {
        val m = WorldModel(cell(a).data)
        val e = Explorer(emptyList()).apply { addCell(CellKey(a.x, a.y), m) }
        var t = 0L
        val revealed = ArrayList<Segment>()
        var x = -200.0
        while (x <= 200.0) {
            val p = f.ll(x, 1.5)
            t += 2500
            revealed += e.onFix(Fix(p.lat, p.lon, 6.0, t, 1.3, 90.0)).segments
            x += 3.0
        }
        assertEquals(listOf("Lange Straße"), revealed.map { it.name }.distinct())
        assertEquals(1, revealed.size)
        assertFalse(revealed.any { it.roadClass == RoadClass.PRIMARY })
    }
}
