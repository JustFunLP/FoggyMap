package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TourStatsTest {
    @Test fun gpsQualityAndStages() {
        val t = TourStats(0)
        listOf(4f, 5f, 6f, 8f, 10f, 12f, 15f, 40f, 60f, 6f).forEach { t.addFix(it) }
        assertEquals(10, t.fixes); assertEquals(2, t.vague)
        assertEquals(8.0, t.accuracyPercentile(0.5)!!.toDouble(), 0.01)
        assertEquals(40.0, t.accuracyPercentile(0.9)!!.toDouble(), 0.01)
        t.stage("MOVING", 0); t.stage("SLOW", 60_000); t.stage("MOVING", 90_000)
        val d = t.stageDurations(150_000)
        assertEquals(120_000L, d["MOVING"]); assertEquals(30_000L, d["SLOW"])
    }

    @Test fun batteryPerHourFromChargeCounterIgnoresCharging() {
        val t = TourStats(0)
        val cap = 4_000_000L   // 4000 mAh
        fun s(min: Int, pct: Double, plugged: Boolean = false, live: Boolean = false) =
            TourStats.BatterySample(min * 60_000L, pct, (cap * pct / 100).toLong(), plugged, false, live)
        // 30 min: 80 % → 77 %  (3 % in 0.5 h = 6 %/h)
        for (m in 0..30) t.addBattery(s(m, 80.0 - m * 0.1))
        // charging for 10 minutes: not counted
        t.addBattery(s(31, 77.5, plugged = true)); t.addBattery(s(40, 79.0, plugged = true))
        // 30 min with live sharing: 79 % → 74.5 % (9 %/h)
        for (m in 41..71) t.addBattery(s(m, 79.0 - (m - 41) * 0.15, live = true))
        assertEquals(6.0, (t.drain.pct - t.drain.livePct) / (t.drain.hours - t.drain.liveHours), 0.05)
        assertEquals(9.0, t.drain.livePerHour!!, 0.05)
        assertEquals(1.0, t.drain.hours, 0.02)
    }

    @Test fun shortMeasurementsGiveNoRate() {
        val t = TourStats(0)
        t.addBattery(TourStats.BatterySample(0, 50.0, 0, false, true, false))
        t.addBattery(TourStats.BatterySample(120_000, 49.0, 0, false, true, false))   // percentage fallback
        assertEquals(1.0, t.drain.pct, 0.001)
        assertTrue("2 minutes are too short for a rate", t.drain.perHour == null)
        assertTrue(t.summary(120_000, 0, 0.0).has("drainPct"))
    }
}
