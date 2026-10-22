package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeTest {
    private fun revealAlong(m: WorldModel, x: Double, ys: IntProgression): List<RevealItem> {
        val e = Explorer(emptyList()).apply { addCell(CellKey(0, 0), m) }
        var t = 0L
        val out = ArrayList<RevealItem>()
        for (y in ys) { val p = TestWorlds.ll(x, y.toDouble()); t += 2000; out += e.onFix(Fix(p.lat, p.lon, 5.0, t)).items }
        return out
    }

    @Test fun twoPlayersMergeAsUnionWithoutLossOrDuplicates() {
        val m = TestWorlds.grid()
        val alice = revealAlong(m, 100.0, 110..190 step 3)
        val bob = revealAlong(m, 200.0, 110..190 step 3)
        // Both reveal the middle block (100..200 × 100..200) → identical id.
        val shared = alice.map { it.id }.toSet().intersect(bob.map { it.id }.toSet())
        assertTrue("middle block must have the same id for both players", shared.any { it.startsWith("b:") })

        val merged = RevealSet()
        assertEquals(alice.size, merged.merge(alice).size)
        val added = merged.merge(bob)
        assertEquals(bob.size - shared.size, added.size)
        // Merging again (retry after network failure) changes nothing.
        assertEquals(0, merged.merge(alice + bob).size)
        // Order independence.
        val other = RevealSet().apply { merge(bob); merge(alice) }
        assertEquals(merged.ids(), other.ids())

        val fog = FogBuilder(51.0).apply { add(merged.all()) }
        assertTrue(fog.containsPoint(TestWorlds.ll(150.0, 150.0)))
        assertTrue(fog.containsPoint(TestWorlds.ll(50.0, 150.0)))
        assertTrue(fog.containsPoint(TestWorlds.ll(250.0, 150.0)))
    }

    @Test fun fogGeoJsonIsWellFormed() {
        val m = TestWorlds.grid()
        val fog = FogBuilder(51.0).apply { add(revealAlong(m, 100.0, 110..190 step 3)) }
        for (g in listOf(0.0, 8.0, 20.0)) {
            val json = org.json.JSONObject(fog.fogGeoJson(g))
            assertEquals("FeatureCollection", json.getString("type"))
            val geom = json.getJSONArray("features").getJSONObject(0).getJSONObject("geometry")
            assertTrue(geom.getString("type").endsWith("Polygon"))
        }
        org.json.JSONObject(fog.edgeGeoJson())
        org.json.JSONObject(fog.roadsGeoJson())
        val b = org.json.JSONObject(fog.buildingsGeoJson())
        assertEquals(2, b.getJSONArray("features").length())
    }
}
