package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripModeClassifierTest {
    /** Feeds a trip (one fix every 3 s) and returns metres per mode. */
    private fun trip(vararg parts: Pair<Int, (Int) -> Double>): Map<TravelMode, Double> {
        val c = TripModeClassifier()
        val total = HashMap<TravelMode, Double>()
        var t = 0L
        fun merge(m: Map<TravelMode, Double>) = m.forEach { (k, v) -> total[k] = (total[k] ?: 0.0) + v }
        for ((seconds, speedAt) in parts) {
            for (s in 0 until seconds step 3) {
                val v = speedAt(s)
                merge(c.add(t, v, v * 3)); t += 3_000
            }
        }
        merge(c.flush())
        return total
    }

    private fun share(m: Map<TravelMode, Double>, mode: TravelMode) = (m[mode] ?: 0.0) / m.values.sum()

    @Test fun stopAndGoInTownIsACar() {
        // 10 minutes: traffic lights, 0 → 45 km/h → 0, every 60 s
        val m = trip(600 to { s -> val ph = s % 60; if (ph < 15) 0.0 else if (ph < 35) (ph - 15) * 0.65 else 12.5 - (ph - 35) * 0.5 })
        assertTrue("car share ${share(m, TravelMode.CAR)}", share(m, TravelMode.CAR) > 0.95)
    }

    @Test fun joggingIsOnFootAndCyclingIsABike() {
        val jog = trip(900 to { s -> 3.0 + (s % 20) * 0.04 })        // 10.8–13.5 km/h
        assertTrue(share(jog, TravelMode.WALK) > 0.99)
        val bike = trip(900 to { s -> 5.0 + (s % 30) * 0.05 })       // 18–23 km/h
        assertTrue(share(bike, TravelMode.BIKE) > 0.99)
    }

    @Test fun walkingAwayFromTheCarCountsAsWalking() {
        val m = trip(300 to { 13.0 }, 30 to { 0.0 }, 300 to { 1.4 })
        val walk = m[TravelMode.WALK] ?: 0.0
        assertTrue("walked ${walk.toInt()} of 420 m", walk > 0.8 * 420)
        assertEquals(300 * 13.0, m[TravelMode.CAR] ?: 0.0, 300 * 13.0 * 0.03)
    }
}
