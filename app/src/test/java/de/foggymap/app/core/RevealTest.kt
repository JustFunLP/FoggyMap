package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevealTest {
    private fun segmentAt(m: WorldModel, x: Double, y: Double): Segment {
        val c = m.candidates(TestWorlds.ll(x, y), 5.0)
        return c.minByOrNull { it.distance }!!.segment
    }

    @Test fun gridHasNineBlocks() {
        val m = TestWorlds.grid()
        assertEquals(9, m.faces.size)
        m.faces.forEach { assertEquals(10_000.0, it.area, 50.0) }
    }

    @Test fun innerSegmentRevealsCorridorAndExactlyTheTwoAdjacentBlocks() {
        val m = TestWorlds.grid()
        // Vertical street x=100 between y=100 and y=200: blocks (0..100,100..200) and (100..200,100..200).
        val seg = segmentAt(m, 100.0, 150.0)
        assertEquals(100.0, seg.length, 0.5)
        val items = m.reveal(seg)
        val blocks = items.filter { it.kind == RevealKind.BLOCK }
        assertEquals(2, blocks.size)
        assertEquals(1, items.count { it.kind == RevealKind.ROAD })
        // Exactly the two buildings in the adjacent blocks become visible.
        val buildings = items.filter { it.kind == RevealKind.BUILDING }
        assertEquals(2, buildings.size)

        // The corridor stops at the junctions: points on the cross street just beyond the junction stay hidden.
        val fog = FogBuilder(51.0).apply { add(items) }
        assertTrue(fog.containsPoint(TestWorlds.ll(100.0, 150.0)))
        assertTrue(fog.containsPoint(TestWorlds.ll(50.0, 150.0)))     // inside left block
        assertTrue(fog.containsPoint(TestWorlds.ll(150.0, 150.0)))    // inside right block
        assertFalse(fog.containsPoint(TestWorlds.ll(250.0, 150.0)))   // block beyond the parallel street
        assertFalse(fog.containsPoint(TestWorlds.ll(200.0, 150.0)))   // the parallel street itself
        assertFalse(fog.containsPoint(TestWorlds.ll(100.0, 230.0)))   // continuation beyond the junction
        assertFalse(fog.containsPoint(TestWorlds.ll(150.0, 100.0)))   // centre line of the cross street
    }

    @Test fun edgeSegmentRevealsOnlyItsOneBlock() {
        val m = TestWorlds.grid()
        val seg = segmentAt(m, 0.0, 150.0)
        assertEquals(1, m.reveal(seg).count { it.kind == RevealKind.BLOCK })
    }

    @Test fun hugeIndustrialBlockOnlyRevealsAStrip() {
        val b = TestWorlds.Builder()
        // 600 m × 400 m = 24 ha face.
        b.street(0.0 to 0.0, 600.0 to 0.0, 600.0 to 400.0, 0.0 to 400.0, 0.0 to 0.0)
        b.building(300.0, 200.0, 50.0, 50.0)     // deep inside — must stay hidden
        b.building(300.0, 10.0, 30.0, 20.0)      // at the street — visible
        val m = b.build()
        assertEquals(1, m.faces.size)
        val seg = segmentAt(m, 300.0, 0.0)
        val items = m.reveal(seg)
        assertEquals(0, items.count { it.kind == RevealKind.BLOCK })
        assertTrue(items.count { it.kind == RevealKind.AREA } >= 1)
        val fog = FogBuilder(51.0).apply { add(items) }
        assertTrue(fog.containsPoint(TestWorlds.ll(300.0, 25.0)))
        assertFalse(fog.containsPoint(TestWorlds.ll(300.0, 220.0)))
        assertEquals(1, items.count { it.kind == RevealKind.BUILDING })
    }

    @Test fun openSettlementRevealsHousesAlongTheStreetButNotAcrossAnotherStreet() {
        val b = TestWorlds.Builder()
        b.street(0.0 to 0.0, 200.0 to 0.0, cls = "unclassified")
        b.street(0.0 to 25.0, 200.0 to 25.0, cls = "unclassified")    // parallel street 25 m away
        b.building(50.0, -20.0, 10.0, 10.0)   // south of street 1 → visible
        b.building(100.0, 30.0, 10.0, 10.0)   // north of street 2 → across the other street → hidden
        val m = b.build()
        assertEquals(0, m.faces.size)
        val seg = segmentAt(m, 100.0, 0.0)
        val ids = m.reveal(seg).filter { it.kind == RevealKind.BUILDING }.map { it.id }
        assertEquals(1, ids.size)
    }

    @Test fun footpathInsideAParkRevealsAStripNotTheWholePark() {
        val b = TestWorlds.Builder()
        b.street(0.0 to 0.0, 400.0 to 0.0, 400.0 to 400.0, 0.0 to 400.0, 0.0 to 0.0)
        b.street(100.0 to 200.0, 300.0 to 200.0, cls = "footway")
        val m = b.build()
        val seg = m.segments.first { it.roadClass == RoadClass.PATH }
        val items = m.reveal(seg)
        val fog = FogBuilder(51.0).apply { add(items) }
        assertTrue(fog.containsPoint(TestWorlds.ll(200.0, 215.0)))
        assertFalse(fog.containsPoint(TestWorlds.ll(200.0, 300.0)))
    }

    @Test fun railwayBoundsBlocks() {
        val b = TestWorlds.Builder()
        b.street(0.0 to 0.0, 200.0 to 0.0, 200.0 to 200.0, 0.0 to 200.0, 0.0 to 0.0)
        b.way(mapOf("railway" to "rail"), -20.0 to 100.0, 220.0 to 100.0)
        val m = b.build()
        assertEquals(2, m.faces.size)
    }

    @Test fun revealItemJsonRoundTrip() {
        val m = TestWorlds.grid()
        val items = m.reveal(segmentAt(m, 100.0, 150.0))
        for (it in items) {
            val back = RevealItem.fromJson(it.toJson())!!
            assertEquals(it.id, back.id)
            assertEquals(it.kind, back.kind)
            assertEquals(it.polygons.size, back.polygons.size)
            assertEquals(it.height, back.height, 0.051)
        }
        assertEquals(null, RevealItem.fromJson(org.json.JSONObject("{\"id\":\"x\",\"k\":\"r\",\"p\":[]}")))
    }
}
