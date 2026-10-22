package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class MapMatcherTest {
    private val rnd = Random(42)

    private fun walk(explorer: Explorer, path: List<Pair<Double, Double>>, stepM: Double, dtMs: Long,
                     noise: Double, accuracy: Double = 8.0, startMs: Long = 1_000_000L,
                     gpsSpeed: Boolean = false): Set<String> {
        val found = HashSet<String>()
        var t = startMs
        for (i in 0 until path.size - 1) {
            val (x0, y0) = path[i]; val (x1, y1) = path[i + 1]
            val len = Math.hypot(x1 - x0, y1 - y0)
            val n = maxOf(1, (len / stepM).toInt())
            for (k in 0 until n) {
                val f = k.toDouble() / n
                val p = TestWorlds.ll(x0 + (x1 - x0) * f + rnd.nextGaussian() * noise, y0 + (y1 - y0) * f + rnd.nextGaussian() * noise)
                t += dtMs
                // Android GPS fixes carry Doppler speed and bearing; emulate that when requested.
                val speed = if (gpsSpeed) stepM / (dtMs / 1000.0) else null
                val bearing = if (gpsSpeed) Geo.bearing(TestWorlds.ll(x0, y0), TestWorlds.ll(x1, y1)) else null
                explorer.onFix(Fix(p.lat, p.lon, accuracy, t, speed, bearing)).segments.forEach { found += it.id }
            }
        }
        return found
    }

    private fun explorerFor(m: WorldModel) = Explorer(emptyList()).apply { addCell(CellKey(0, 0), m) }
    private fun segAt(m: WorldModel, x: Double, y: Double) = m.candidates(TestWorlds.ll(x, y), 5.0).minBy { it.distance }.segment.id

    @Test fun walkingAlongOneStreetWithJitterRevealsOnlyThatStreet() {
        val m = TestWorlds.grid()
        val found = walk(explorerFor(m), listOf(100.0 to 110.0, 100.0 to 190.0), 1.4 * 2, 2000, noise = 4.0)
        assertEquals(setOf(segAt(m, 100.0, 150.0)), found)
    }

    @Test fun standingAtAJunctionWithJitterRevealsNothing() {
        val m = TestWorlds.grid()
        val e = explorerFor(m)
        val found = HashSet<String>()
        var t = 0L
        repeat(120) {
            val p = TestWorlds.ll(100.0 + rnd.nextGaussian() * 5, 100.0 + rnd.nextGaussian() * 5)
            t += 1000
            e.onFix(Fix(p.lat, p.lon, 10.0, t)).segments.forEach { found += it.id }
        }
        assertTrue("jitter at a junction must not unlock side streets, got $found", found.size <= 1)
    }

    @Test fun parallelStreetIsNotRevealed() {
        val b = TestWorlds.Builder()
        b.street(0.0 to 0.0, 300.0 to 0.0, name = "A")
        b.street(0.0 to 22.0, 300.0 to 22.0, name = "B")   // only 22 m away
        val m = b.build()
        val found = walk(explorerFor(m), listOf(10.0 to -2.0, 290.0 to -2.0), 3.0, 2000, noise = 4.0)
        assertEquals(setOf(segAt(m, 150.0, 0.0)), found)
    }

    @Test fun poorAccuracyIsIgnored() {
        val m = TestWorlds.grid()
        val found = walk(explorerFor(m), listOf(100.0 to 110.0, 100.0 to 190.0), 2.8, 2000, noise = 2.0, accuracy = 60.0)
        assertTrue(found.isEmpty())
    }

    @Test fun gpsJumpIsRejected() {
        val m = TestWorlds.grid()
        val e = explorerFor(m)
        val a = TestWorlds.ll(100.0, 120.0)
        assertTrue(e.onFix(Fix(a.lat, a.lon, 5.0, 1000)).result.accepted)
        val far = TestWorlds.ll(3000.0, 120.0)
        val r = e.onFix(Fix(far.lat, far.lon, 5.0, 2000)).result
        assertFalse(r.accepted)
        assertEquals("jump", r.reason)
    }

    @Test fun carWithSparseFixesStillRevealsShortSegments() {
        val b = TestWorlds.Builder()
        // Main road with side streets every 35 m → short segments.
        b.street(*(0..10).map { it * 35.0 to 0.0 }.toTypedArray(), cls = "secondary")
        for (k in 1..9) b.street(k * 35.0 to 0.0, k * 35.0 to 60.0)
        val m = b.build()
        // 50 km/h, one fix every 3 s (≈ 42 m apart).
        val found = walk(explorerFor(m), listOf(0.0 to 0.0, 350.0 to 0.0), 41.7, 3000, noise = 2.0, accuracy = 6.0, gpsSpeed = true)
        val main = m.segments.filter { it.roadClass == RoadClass.SECONDARY }.map { it.id }.toSet()
        assertTrue("revealed ${found.intersect(main).size} of ${main.size}, missing ${main - found}", found.intersect(main).size >= main.size - 1)
        assertTrue("no side street may be revealed: ${found - main}", (found - main).isEmpty())
    }

    @Test fun offlineExplorationSurvivesRestartWithoutDuplicates() {
        val m = TestWorlds.grid()
        val e1 = explorerFor(m)
        val items = ArrayList<RevealItem>()
        var t = 0L
        for (y in 110..190 step 3) {
            val p = TestWorlds.ll(100.0, y.toDouble()); t += 2000
            items += e1.onFix(Fix(p.lat, p.lon, 6.0, t)).items
        }
        assertTrue(items.isNotEmpty())
        // "Persist" as JSON (what the database stores) and restart.
        val stored = items.map { RevealItem.fromJson(org.json.JSONObject(it.toJson().toString()))!! }
        val e2 = Explorer(stored.map { it.id }).apply { addCell(CellKey(0, 0), m) }
        var again = 0
        for (y in 110..190 step 3) {
            val p = TestWorlds.ll(100.0, y.toDouble()); t += 2000
            again += e2.onFix(Fix(p.lat, p.lon, 6.0, t)).items.size
        }
        assertEquals(0, again)
    }

    @Test fun distanceStatisticIgnoresJitterWhileStanding() {
        val m = TestWorlds.grid()
        val e = explorerFor(m)
        var dist = 0.0
        var t = 0L
        repeat(300) {
            val p = TestWorlds.ll(50.0 + rnd.nextGaussian() * 4, 100.0 + rnd.nextGaussian() * 4)
            t += 1000
            dist += e.onFix(Fix(p.lat, p.lon, 8.0, t)).result.countedDistance
        }
        assertTrue("counted $dist m while standing", dist < 60)
    }

    @Test fun robustOverManyRandomRuns() {
        var junctionFalse = 0; var parallelFalse = 0; var missed = 0
        for (seed in 1..15) {
            val r = Random(seed.toLong())
            val e = explorerFor(TestWorlds.grid())
            var t = 0L
            repeat(150) {
                val p = TestWorlds.ll(200 + r.nextGaussian() * 7, 200 + r.nextGaussian() * 7); t += 1000
                junctionFalse += e.onFix(Fix(p.lat, p.lon, 12.0, t)).segments.size
            }
            val b = TestWorlds.Builder()
            b.street(0.0 to 0.0, 300.0 to 0.0); b.street(0.0 to 20.0, 300.0 to 20.0)
            val m = b.build()
            val target = segAt(m, 150.0, 0.0)
            val e2 = explorerFor(m)
            val got = HashSet<String>()
            var x = 5.0; t = 0
            while (x < 295) {
                val p = TestWorlds.ll(x + r.nextGaussian() * 5, -3 + r.nextGaussian() * 5); t += 2000
                e2.onFix(Fix(p.lat, p.lon, 10.0, t, 1.4, 90.0)).segments.forEach { got += it.id }; x += 2.8
            }
            if (target !in got) missed++
            parallelFalse += (got - target).size
        }
        assertEquals("false reveals while standing at a junction", 0, junctionFalse)
        assertEquals("parallel street 20 m away revealed", 0, parallelFalse)
        assertEquals("walked street not revealed", 0, missed)
    }
}
