package de.foggymap.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sparse fixes (battery saver, fast driving): the way between them is filled along the streets. */
class GapFillTest {
    private fun explorer(m: WorldModel) = Explorer(emptyList()).apply { addCell(CellKey(0, 0), m) }

    /** Drives through [pts] (local metres) with a fix every [spacing] m at [speed] m/s. */
    private fun drive(e: Explorer, pts: List<Pair<Double, Double>>, spacing: Double, speed: Double): Set<String> {
        val out = HashSet<String>()
        var t = 0L
        val dense = ArrayList<Pair<Double, Double>>()
        for (i in 0 until pts.size - 1) {
            val (ax, ay) = pts[i]; val (bx, by) = pts[i + 1]
            val len = Math.hypot(bx - ax, by - ay)
            var d = 0.0
            while (d < len) { dense += (ax + (bx - ax) * d / len) to (ay + (by - ay) * d / len); d += 1.0 }
        }
        dense += pts.last()
        var since = spacing
        var last: Pair<Double, Double>? = null
        for (p in dense) {
            if (last != null) since += Math.hypot(p.first - last.first, p.second - last.second)
            last = p
            if (since < spacing) continue
            since = 0.0
            t += (spacing / speed * 1000).toLong()
            val ll = TestWorlds.ll(p.first + 1.5, p.second + 1.0)
            out += e.onFix(Fix(ll.lat, ll.lon, 8.0, t, speed, null)).segments.map { seg -> name(seg) }
        }
        return out
    }

    private fun name(s: Segment): String {
        val a = s.line.coordinates.first(); val b = s.line.coordinates.last()
        fun r(v: Double) = Math.round(v / 100).toInt()
        val p = listOf(r(a.x) to r(a.y), r(b.x) to r(b.y)).sortedWith(compareBy({ it.first }, { it.second }))
        return "${p[0].first},${p[0].second}-${p[1].first},${p[1].second}"
    }

    @Test fun carWithSparseFixesRevealsEveryStreetItDrove() {
        val e = explorer(TestWorlds.grid())
        // North on x = 0, then east on y = 300 – a fix only every 60 m (battery saver at 54 km/h).
        val got = drive(e, listOf(0.0 to 10.0, 0.0 to 300.0, 290.0 to 300.0), 60.0, 15.0)
        val expected = setOf("0,0-0,1", "0,1-0,2", "0,2-0,3", "0,3-1,3", "1,3-2,3", "2,3-3,3")
        assertTrue("missing ${expected - got}", got.containsAll(expected))
        assertEquals("nothing else: ${got - expected}", expected, got)
    }

    @Test fun cornerWithoutAFixInBetweenIsFilled() {
        val e = explorer(TestWorlds.grid())
        val got = drive(e, listOf(100.0 to 30.0, 100.0 to 100.0, 170.0 to 100.0), 70.0, 12.0)
        assertTrue("got $got", got.containsAll(setOf("1,0-1,1", "1,1-2,1")))
        assertTrue("got $got", got.size <= 3)
    }

    @Test fun twoEquallyShortWaysAroundABlockRevealNothingInBetween() {
        val e = explorer(TestWorlds.grid())
        var t = 0L
        val got = HashSet<String>()
        // Moving along x = 100 (south part), then a single fix far away diagonally across the block.
        for ((x, y) in listOf(100.0 to 20.0, 100.0 to 50.0, 100.0 to 80.0, 103.0 to 100.0, 197.0 to 197.0)) {
            val ll = TestWorlds.ll(x, y)
            t += 3000
            got += e.onFix(Fix(ll.lat, ll.lon, 6.0, t, 10.0, null)).segments.map { name(it) }
        }
        // Left or right around the block (1,1)-(2,2) is equally long: neither side may be revealed.
        assertTrue("got $got", "1,1-1,2" !in got && "1,2-2,2" !in got && "1,1-2,1" !in got && "2,1-2,2" !in got)
    }

    /** Standing next to a street with realistic GPS jitter: filling must not add anything to what the matcher does. */
    @Test fun standingStillNeverFillsAnything() {
        for (seed in 1L..6L) {
            val withFill = standing(true, seed)
            val matcherOnly = standing(false, seed)
            assertTrue("seed $seed: filled ${withFill - matcherOnly}", (withFill - matcherOnly).isEmpty())
            assertTrue("seed $seed: $matcherOnly", matcherOnly.size <= 1)
        }
    }

    private fun standing(fill: Boolean, seed: Long): Set<String> {
        val e = explorer(TestWorlds.grid()).apply { gapFillEnabled = fill }
        var t = 0L
        val got = HashSet<String>()
        val rnd = java.util.Random(seed)
        repeat(120) {
            // Waiting 6 m beside the street x = 100, near the junction (100, 200).
            val ll = TestWorlds.ll(106.0 + rnd.nextGaussian() * 7, 185.0 + rnd.nextGaussian() * 7)
            t += 4000
            got += e.onFix(Fix(ll.lat, ll.lon, 12.0, t, 0.3, null)).segments.map { name(it) }
        }
        return got
    }
}
