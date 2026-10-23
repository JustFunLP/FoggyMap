package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.locationtech.jts.geom.GeometryFactory

class Buildings3dTest {
    private val origin = LatLon(52.5438, 13.4177)
    private val proj = LocalProjection(origin)
    private val gf = GeometryFactory()

    /** A house x0..x1 metres east, 0..12 m north – stored 0.8 m larger, like the real reveal does. */
    private fun house(id: String, x0: Double, x1: Double): RevealItem {
        val p = gf.toGeometry(org.locationtech.jts.geom.Envelope(x0, x1, 0.0, 12.0)).buffer(0.8)
        return RevealItem(id, RevealKind.BUILDING, RevealItem.polygonsOf(p, proj, 0.3), height = 15.0)
    }

    @Test fun neighbouringHousesNoLongerOverlapAndDuplicatesAreDrawnOnce() {
        val fog = FogBuilder(origin.lat)
        // a row of three houses wall to wall, plus the middle one again under another id (old data / a friend)
        fog.add(listOf(house("g:1", 0.0, 10.0), house("g:2", 10.0, 20.0), house("g:3", 20.0, 30.0), house("g:2b", 10.0, 20.0)))
        val json = org.json.JSONObject(fog.buildingsGeoJson())
        val features = json.getJSONArray("features")
        assertEquals("duplicate drawn once", 3, features.length())
        val local = (0 until features.length()).map { i ->
            val geom = features.getJSONObject(i).getJSONObject("geometry")
            val coords = geom.getJSONArray("coordinates")
            val ring = if (geom.getString("type") == "Polygon") coords.getJSONArray(0) else coords.getJSONArray(0).getJSONArray(0)
            val c = (0 until ring.length()).map { k -> ring.getJSONArray(k).let { proj.toLocal(LatLon(it.getDouble(1), it.getDouble(0))) } }
            gf.createPolygon((if (c.first().equals2D(c.last())) c else c + c.first()).toTypedArray())
        }
        for (i in local.indices) for (j in i + 1 until local.size)
            assertTrue("no overlapping walls", local[i].intersection(local[j]).area < 0.01)
        // and the shape still matches the house (not shrunk away)
        assertTrue(local.all { it.area > 9.0 * 11.0 })
    }
}
