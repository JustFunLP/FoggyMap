package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestSpotsTest {
    private val home = LatLon(52.54380, 13.41770)
    private fun near(p: LatLon, northM: Double) = LatLon(p.lat + northM / 111_320.0, p.lon)

    @Test fun aPlaceBecomesKnownAfterStandingThereTwice() {
        val r = RestSpots()
        assertFalse(r.isKnown(home))
        r.record(home, 1)
        assertFalse("once is not a habit", r.isKnown(home))
        r.record(near(home, 25.0), 2)          // GPS scatter indoors
        assertTrue(r.isKnown(home))
        assertTrue(r.isKnown(near(home, 40.0)))
        assertFalse("the next street is not home", r.isKnown(near(home, 150.0)))
        assertEquals(1, r.size)
    }

    @Test fun survivesSavingAndKeepsOnlyRecentPlaces() {
        val r = RestSpots()
        r.record(home, 1); r.record(home, 2)
        for (k in 1..RestSpots.MAX_SPOTS + 5) r.record(near(home, 500.0 * k), 10L + k)
        assertEquals(RestSpots.MAX_SPOTS, r.size)
        val back = RestSpots.decode(r.encode())
        assertEquals(RestSpots.MAX_SPOTS, back.size)
        assertFalse("oldest place dropped first", back.isKnown(home))
        assertTrue(RestSpots.decode("kaputt;1,2").size == 0)
    }
}
