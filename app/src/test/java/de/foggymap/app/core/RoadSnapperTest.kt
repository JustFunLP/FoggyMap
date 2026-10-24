package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RoadSnapperTest {
    private val world = TestWorlds.grid(withBuildings = false)
    private val proj = LocalProjection(TestWorlds.origin)
    private fun xy(p: LatLon) = proj.toLocal(p).let { it.x to it.y }

    @Test fun wobblyGpsWalksOnTheStreetAndGlidesAhead() {
        val s = RoadSnapper()
        // Walking east on "Ost-West 1" (y = 100) with GPS jumping 8 m to either side.
        for (i in 0..20) {
            val x = 110.0 + i * 4.0
            val y = 100.0 + if (i % 2 == 0) 8.0 else -7.0
            val snap = s.snap(TestWorlds.ll(x, y), 10.0, 90.0, true, i * 3000L, world::candidates)
            assertTrue("fix $i snapped", snap.snapped)
            val (sx, sy) = xy(snap.pos)
            assertTrue("on the street centre line (fix $i): $sy", abs(sy - 100.0) < 0.3)
            assertEquals(x, sx, 0.3)
            assertEquals(90.0, snap.bearing!!, 1.0)
            // the stretch ahead points east – across the junction at x = 200, where the street goes on straight – 60 m long
            val (ex, ey) = xy(snap.ahead.last())
            assertTrue("ahead ends at $ex (from $sx)", abs(ex - (sx + 60.0)) < 0.5); assertEquals(100.0, ey, 0.3)
            assertTrue(abs(RoadSnapper.length(snap.ahead) - RoadSnapper.AHEAD_M) < 0.5)
        }
    }

    @Test fun staysOnItsStreetAtAJunctionAndLeavesItInOpenSpace() {
        val s = RoadSnapper()
        s.snap(TestWorlds.ll(90.0, 101.0), 8.0, 90.0, true, 0, world::candidates)
        // Right past the crossing the fix is closer to the cross street (3 m) than to ours (9 m) – still ours.
        val j = s.snap(TestWorlds.ll(103.0, 109.0), 8.0, 90.0, true, 3000, world::candidates)
        assertEquals(100.0, xy(j.pos).second, 0.3)
        // Walking north: the cross street takes over.
        val n = s.snap(TestWorlds.ll(102.0, 125.0), 8.0, 0.0, true, 6000, world::candidates)
        assertEquals(100.0, xy(n.pos).first, 0.3)
        assertEquals(0.0, n.bearing!!, 1.0)
        // In the middle of a block (50 m from every street) the figure stays where GPS says.
        val open = RoadSnapper().snap(TestWorlds.ll(50.0, 50.0), 8.0, null, false, 0, world::candidates)
        assertFalse(open.snapped)
        assertTrue(open.ahead.isEmpty())
    }

    @Test fun noFlipFlopAtTheSnapEdge() {
        // 19 m from the street: a fresh snapper does not snap (limit 15 m) …
        assertFalse(RoadSnapper().snap(TestWorlds.ll(150.0, 119.0), 10.0, null, false, 0, world::candidates).snapped)
        // … but one that is already on the street keeps it (limit 15 + 8 m).
        val s = RoadSnapper()
        assertTrue(s.snap(TestWorlds.ll(150.0, 110.0), 10.0, null, false, 0, world::candidates).snapped)
        assertTrue(s.snap(TestWorlds.ll(150.0, 119.0), 10.0, null, false, 3000, world::candidates).snapped)
        assertFalse(s.snap(TestWorlds.ll(150.0, 128.0), 10.0, null, false, 6000, world::candidates).snapped)
    }

    @Test fun standingKeepsTheLastDirection() {
        val s = RoadSnapper()
        val a = s.snap(TestWorlds.ll(150.0, 103.0), 8.0, 270.0, true, 0, world::candidates)
        assertEquals(270.0, a.bearing!!, 1.0)
        val b = s.snap(TestWorlds.ll(149.0, 97.0), 8.0, null, false, 3000, world::candidates)
        assertEquals(270.0, b.bearing!!, 1.0)
        assertTrue(xy(b.ahead.last()).first < 149.0)
    }

    @Test fun pathHelpers() {
        val path = listOf(TestWorlds.ll(0.0, 0.0), TestWorlds.ll(30.0, 0.0), TestWorlds.ll(30.0, 40.0))
        assertEquals(70.0, RoadSnapper.length(path), 0.2)
        val (x1, y1) = xy(RoadSnapper.pointAlong(path, 50.0))
        assertEquals(30.0, x1, 0.2); assertEquals(20.0, y1, 0.2)
        assertEquals(path.last(), RoadSnapper.pointAlong(path, 500.0))
        assertEquals(12.0, RoadSnapper.alongOffset(path, TestWorlds.ll(12.0, 3.0))!!, 0.2)
        assertEquals(-5.0, RoadSnapper.alongOffset(path, TestWorlds.ll(-5.0, 1.0))!!, 0.2)
        assertTrue(RoadSnapper.alongOffset(path, TestWorlds.ll(10.0, 30.0)) == null)   // far to the side: not comparable
        assertTrue(abs(Geo.angleDifference(350.0, 10.0) - 20.0) < 1e-9)
    }
}
