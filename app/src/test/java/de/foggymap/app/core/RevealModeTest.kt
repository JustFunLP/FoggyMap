package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reveal modes from the settings, and streets with a block on only one side. */
class RevealModeTest {
    private fun segmentAt(m: WorldModel, x: Double, y: Double) =
        m.candidates(TestWorlds.ll(x, y), 5.0).minBy { it.distance }.segment

    private fun fogOf(items: List<RevealItem>) = FogBuilder(51.0).apply { add(items) }

    @Test fun streetWithABlockOnOneSideAndOpenLandOnTheOtherShowsBoth() {
        // Like a lake-shore street: a 1.5 ha block north of the street, nothing closed to the south.
        val b = TestWorlds.Builder()
        b.street(-100.0 to 0.0, 0.0 to 0.0, 150.0 to 0.0, 250.0 to 0.0)
        b.street(0.0 to 0.0, 0.0 to 100.0, 150.0 to 100.0, 150.0 to 0.0)
        b.building(60.0, 40.0)        // in the block
        b.building(60.0, -25.0)       // south, next to the street
        b.building(60.0, -80.0)       // south, far away
        val m = b.build()
        val seg = segmentAt(m, 75.0, 0.0)
        val items = m.reveal(seg)
        assertEquals(1, items.count { it.kind == RevealKind.BLOCK })
        assertTrue("open side gets a strip: ${items.map { it.id }}", items.any { it.kind == RevealKind.AREA })
        val fog = fogOf(items)
        assertTrue(fog.containsPoint(TestWorlds.ll(75.0, 50.0)))
        assertTrue(fog.containsPoint(TestWorlds.ll(75.0, -20.0)))
        assertFalse(fog.containsPoint(TestWorlds.ll(75.0, -60.0)))
        assertFalse("strip stops at the junctions", fog.containsPoint(TestWorlds.ll(200.0, -20.0)))
        assertEquals(2, BuildingRevealer.reveal(BuildingParser.parse(m.data), items).size)
    }

    @Test fun hugeBlockOnlyShowsTheHousesAlongTheStreet() {
        val b = TestWorlds.Builder()
        b.street(-50.0 to 0.0, 0.0 to 0.0, 400.0 to 0.0, 450.0 to 0.0)
        b.street(0.0 to 0.0, 0.0 to 160.0, 400.0 to 160.0, 400.0 to 0.0)   // 6.4 ha
        b.building(140.0, 20.0)   // along the street
        b.building(140.0, 120.0)  // deep inside, next to the far street
        val m = b.build()
        val items = m.reveal(segmentAt(m, 150.0, 0.0))
        assertEquals(0, items.count { it.kind == RevealKind.BLOCK })
        val shown = BuildingRevealer.reveal(BuildingParser.parse(m.data), items)
        assertEquals(1, shown.size)
    }

    @Test fun streetModeShowsAStripOfTheChosenWidthNotWholeBlocks() {
        val m = TestWorlds.grid()
        val seg = segmentAt(m, 100.0, 150.0)
        val items = m.reveal(seg, RevealSettings(RevealMode.STREET, 25.0))
        assertEquals(0, items.count { it.kind == RevealKind.BLOCK })
        val fog = fogOf(items)
        assertTrue(fog.containsPoint(TestWorlds.ll(118.0, 150.0)))
        assertTrue(fog.containsPoint(TestWorlds.ll(82.0, 150.0)))
        assertFalse(fog.containsPoint(TestWorlds.ll(140.0, 150.0)))
        assertFalse(fog.containsPoint(TestWorlds.ll(118.0, 230.0)))
        // Village-style: a wide strip, but never across the next street (blocks are 100 m wide).
        val wide = fogOf(m.reveal(seg, RevealSettings(RevealMode.STREET, 150.0)))
        assertTrue(wide.containsPoint(TestWorlds.ll(160.0, 150.0)))
        assertFalse(wide.containsPoint(TestWorlds.ll(220.0, 150.0)))
    }

    @Test fun radiusModeRevealsACircleAroundThePathImmediately() {
        val r = RadiusRevealer()
        val items = ArrayList<RevealItem>()
        var t = 0L
        for (x in 0..200 step 5) { val p = TestWorlds.ll(x.toDouble(), 500.0); t += 3000; items += r.onFix(Fix(p.lat, p.lon, 6.0, t), 40.0) }
        assertTrue(items.size in 10..25)
        assertTrue(items.all { RevealItem.ID_PATTERN.matches(it.id) })
        val fog = fogOf(items)
        assertTrue(fog.containsPoint(TestWorlds.ll(100.0, 535.0)))
        assertTrue(fog.containsPoint(TestWorlds.ll(225.0, 500.0)))
        assertFalse(fog.containsPoint(TestWorlds.ll(100.0, 550.0)))
        // Standing still with GPS jitter adds (almost) nothing.
        val rnd = java.util.Random(2)
        var more = 0
        repeat(200) { val p = TestWorlds.ll(200.0 + rnd.nextGaussian() * 3, 500.0 + rnd.nextGaussian() * 3); t += 3000; more += r.onFix(Fix(p.lat, p.lon, 6.0, t), 40.0).size }
        assertTrue("standing added $more", more <= 3)
    }

    @Test fun radiusModeStillCountsTheStreetsButRevealsNoBlocks() {
        val m = TestWorlds.grid()
        val e = Explorer(emptyList()).apply { addCell(CellKey(0, 0), m); settings = RevealSettings(RevealMode.RADIUS, 60.0) }
        var t = 0L
        val items = ArrayList<RevealItem>()
        for (y in 110..190 step 3) { val p = TestWorlds.ll(100.0, y.toDouble()); t += 2000; items += e.onFix(Fix(p.lat, p.lon, 5.0, t)).items }
        assertTrue(items.any { it.kind == RevealKind.ROAD })
        assertTrue(items.none { it.kind == RevealKind.BLOCK || it.kind == RevealKind.AREA })
    }
}
