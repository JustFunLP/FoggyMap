package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripModeClassifierTest {
    /** Feeds a trip (one fix every 3 s) and returns metres per mode. */
    private fun trip(vararg parts: Pair<Int, (Int) -> Double>, sensor: TravelMode? = null): Map<TravelMode, Double> {
        val c = TripModeClassifier()
        sensor?.let { c.activity(0, it) }
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

    @Test fun ridingAlongTheRailsIsATrainAndHandChoiceWins() {
        val c = TripModeClassifier()
        var t = 0L
        val total = HashMap<TravelMode, Double>()
        fun merge(m: Map<TravelMode, Double>) = m.forEach { (k, v) -> total[k] = (total[k] ?: 0.0) + v }
        // 8 minutes at 60–90 km/h right on the tracks
        for (s in 0 until 480 step 3) { val v = 17.0 + (s % 30) * 0.25; merge(c.add(t, v, v * 3, onRail = true)); t += 3_000 }
        assertEquals(TravelMode.TRAIN, c.current())
        // a car at the same speed on a street is not a train
        val car = trip(480 to { s -> 17.0 + (s % 30) * 0.25 })
        assertTrue(share(car, TravelMode.CAR) > 0.99)
        merge(c.flush())
        assertTrue(share(total, TravelMode.TRAIN) > 0.99)
        c.manual = TravelMode.BIKE
        assertEquals(TravelMode.BIKE, c.current())
        assertEquals(mapOf(TravelMode.BIKE to 30.0), c.add(t + 200_000, 3.0, 30.0).let { c.flush() })
    }

    @Test fun activityRecognitionSettlesTrafficJamsSlowBikesAndFastRunners() {
        // A car crawling in a jam (never above 25 km/h) looks like a bike by speed alone.
        val jam = { s: Int -> val ph = s % 40; if (ph < 10) 0.0 else 6.5 }
        assertTrue(share(trip(600 to jam), TravelMode.CAR) < 0.5)
        assertTrue("jam with sensor ${trip(600 to jam, sensor = TravelMode.CAR)}", share(trip(600 to jam, sensor = TravelMode.CAR), TravelMode.CAR) > 0.95)
        // A relaxed cyclist at 13 km/h looks like a runner.
        val slowBike = { _: Int -> 3.6 }
        assertTrue(share(trip(600 to slowBike), TravelMode.WALK) > 0.95)
        assertTrue(share(trip(600 to slowBike, sensor = TravelMode.BIKE), TravelMode.BIKE) > 0.95)
        // A sprinter at 18 km/h looks like a bike.
        val sprint = { _: Int -> 5.0 }
        assertTrue(share(trip(300 to sprint, sensor = TravelMode.WALK), TravelMode.WALK) > 0.95)
        // A "bike" hint at motorway speed is ignored.
        assertTrue(share(trip(300 to { 25.0 }, sensor = TravelMode.BIKE), TravelMode.CAR) > 0.95)
        val c = TripModeClassifier()
        c.activity(0, TravelMode.CAR)
        c.speed(10_000, 1.0)
        assertEquals(TravelMode.STILL, c.current())   // standing in the car (traffic light)
        c.speed(13_000, 6.0)
        assertEquals(TravelMode.CAR, c.current())
    }
}
