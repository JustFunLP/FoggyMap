package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadNetworkTest {

    @Test fun streetIsSplitAtEveryRealJunction() {
        val b = TestWorlds.Builder()
        // One long way crossing two side streets → 3 segments.
        b.street(0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0, 300.0 to 0.0)
        b.street(100.0 to -80.0, 100.0 to 0.0, 100.0 to 80.0)
        b.street(200.0 to 0.0, 200.0 to 90.0)
        val m = b.build()
        val main = m.segments.filter { it.name == null && it.wayId == m.segments.first().wayId }
        assertEquals(3, main.size)
        assertEquals(100.0, main[0].length, 0.5)
        // Cross street is split at the main street; the dead end side street is one piece.
        assertEquals(6, m.segments.size)
    }

    @Test fun footpathJoiningDoesNotSplitTheStreet() {
        val b = TestWorlds.Builder()
        b.street(0.0 to 0.0, 50.0 to 0.0, 100.0 to 0.0)
        b.street(50.0 to 0.0, 50.0 to 40.0, cls = "footway")
        val m = b.build()
        val street = m.segments.filter { it.roadClass == RoadClass.RESIDENTIAL }
        assertEquals("a footway must not cut the street in two", 1, street.size)
        assertEquals(100.0, street[0].length, 0.5)
        assertEquals(1, m.segments.count { it.roadClass == RoadClass.PATH })
    }

    @Test fun sidewalksDrivewaysAndAreasAreIgnored() {
        assertNull(RoadClass.of(mapOf("highway" to "footway", "footway" to "sidewalk")))
        assertNull(RoadClass.of(mapOf("highway" to "service", "service" to "driveway")))
        assertNull(RoadClass.of(mapOf("highway" to "pedestrian", "area" to "yes")))
        assertNull(RoadClass.of(mapOf("highway" to "service", "access" to "private")))
        assertEquals(RoadClass.PATH, RoadClass.of(mapOf("highway" to "cycleway")))
        assertEquals(RoadClass.PRIMARY, RoadClass.of(mapOf("highway" to "primary_link")))
    }

    @Test fun bridgeCrossingWithoutSharedNodeIsNoJunction() {
        val b = TestWorlds.Builder()
        b.street(0.0 to 0.0, 200.0 to 0.0)
        b.way(mapOf("highway" to "primary", "bridge" to "yes", "layer" to "1"), 100.0 to -100.0, 100.0 to 100.0)
        val m = b.build()
        assertEquals(2, m.segments.size)
        assertTrue(m.segments.first { it.roadClass == RoadClass.PRIMARY }.elevated)
        assertFalse(m.segments.first { it.roadClass == RoadClass.PRIMARY }.blockForming)
    }

    @Test fun veryLongRuralRoadIsChunked() {
        val b = TestWorlds.Builder()
        b.street(0.0 to 0.0, 2500.0 to 0.0, cls = "unclassified")
        val m = b.build()
        assertEquals(3, m.segments.size)
        assertTrue(m.segments.all { it.length <= RoadNetworkBuilder.MAX_SEGMENT_LENGTH + 1 })
        assertEquals(3, m.segments.map { it.id }.toSet().size)
    }

    @Test fun segmentsLeavingTheQueryBoxAreNotMatchable() {
        val b = TestWorlds.Builder()
        b.street(0.0 to 0.0, 100.0 to 0.0)
        b.street(100.0 to 0.0, 5000.0 to 0.0)  // runs out of the small box
        val box = BBox(TestWorlds.ll(-200.0, -200.0).lat, TestWorlds.ll(-200.0, -200.0).lon,
            TestWorlds.ll(1000.0, 200.0).lat, TestWorlds.ll(1000.0, 200.0).lon)
        val m = b.build(box)
        assertEquals(1, m.segments.count { it.matchable })
    }

    @Test fun segmentIdsAreStableAcrossCells() {
        val m1 = TestWorlds.grid()
        val m2 = TestWorlds.grid()
        assertEquals(m1.segments.map { it.id }.toSet(), m2.segments.map { it.id }.toSet())
        assertEquals(m1.faces.map { it.id }.toSet(), m2.faces.map { it.id }.toSet())
    }

    @Test fun heightParsing() {
        assertEquals(12.0, WorldModel.parseMeters("12 m")!!, 1e-9)
        assertEquals(12.5, WorldModel.parseMeters("12,5")!!, 1e-9)
        assertEquals(3.048, WorldModel.parseMeters("10 ft")!!, 1e-3)
        assertNull(WorldModel.parseMeters("hoch"))
    }
}
