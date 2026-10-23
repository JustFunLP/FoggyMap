package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PositionFilterTest {
    private val here = LatLon(52.5438, 13.4177)
    private fun offset(p: LatLon, northM: Double, eastM: Double) =
        LatLon(p.lat + northM / 111_320.0, p.lon + eastM / (111_320.0 * Math.cos(Math.toRadians(p.lat))))

    @Test fun standingStillSettlesCloserThanSingleFixes() {
        val rnd = Random(3)
        var rawErr = 0.0; var filtErr = 0.0; var n = 0
        repeat(20) { run ->
            val f = PositionFilter()
            for (i in 0 until 40) {
                val fix = offset(here, rnd.nextGaussian() * 6, rnd.nextGaussian() * 6)
                val e = f.update(fix.lat, fix.lon, 8.0, 1_000L * i * 3, 0.1)
                if (i >= 10) {
                    rawErr += Geo.distance(here, fix); filtErr += Geo.distance(here, LatLon(e.lat, e.lon)); n++
                }
            }
        }
        println("standing: raw ${"%.1f".format(rawErr / n)} m, shown ${"%.1f".format(filtErr / n)} m")
        assertTrue("shown position at least twice as steady", filtErr < rawErr / 2)
    }

    @Test fun aWorseWifiFixDoesNotPullTheFigureAway() {
        val f = PositionFilter()
        f.update(here.lat, here.lon, 5.0, 0, 0.0)
        val wifi = offset(here, 35.0, 10.0)
        val e = f.update(wifi.lat, wifi.lon, 60.0, 5_000, null)
        assertTrue(Geo.distance(here, LatLon(e.lat, e.lon)) < 1.0)
    }

    @Test fun movingFollowsImmediately() {
        val f = PositionFilter()
        f.update(here.lat, here.lon, 5.0, 0, 0.0)
        val ahead = offset(here, 12.0, 0.0)
        val e = f.update(ahead.lat, ahead.lon, 6.0, 3_000, 4.0)
        assertEquals(0.0, Geo.distance(ahead, LatLon(e.lat, e.lon)), 0.01)
        // walked far without speed info → also taken at once
        val far = offset(here, 80.0, 0.0)
        val e2 = f.update(far.lat, far.lon, 6.0, 30_000, null)
        assertEquals(0.0, Geo.distance(far, LatLon(e2.lat, e2.lon)), 0.01)
    }
}
