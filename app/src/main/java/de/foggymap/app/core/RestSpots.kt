package de.foggymap.app.core

/**
 * Places where you keep standing still (home, office, the bakery …), learned on the phone only.
 *
 * GPS is the biggest battery drain while you do not move. Normally FoggyMap waits ~2.5 minutes before it
 * pauses GPS (you might just be waiting at a crossing). At a place where you have already stood still
 * a few times, it pauses after ~40 seconds and checks less often – that is the whole "movement profile".
 *
 * Never shared, never uploaded; cleared with "Daten löschen".
 */
class RestSpots(private val spots: MutableList<Spot> = ArrayList()) {
    data class Spot(val lat: Double, val lon: Double, val count: Int, val lastSeen: Long)

    val size get() = spots.size

    /** A still period at [p] began (called once per stop). */
    fun record(p: LatLon, now: Long) {
        val i = nearest(p)
        if (i >= 0) {
            val s = spots[i]
            // Move the spot a little towards the new position, so it settles in the middle of where you stand.
            val w = 1.0 / (minOf(s.count, 9) + 1)
            spots[i] = Spot(s.lat + (p.lat - s.lat) * w, s.lon + (p.lon - s.lon) * w, s.count + 1, now)
        } else {
            spots += Spot(p.lat, p.lon, 1, now)
            if (spots.size > MAX_SPOTS) spots.remove(spots.minBy { it.lastSeen })
        }
    }

    /** You have stood still here [KNOWN_AFTER] times or more. */
    fun isKnown(p: LatLon): Boolean = nearest(p).let { it >= 0 && spots[it].count >= KNOWN_AFTER }

    private fun nearest(p: LatLon): Int {
        var best = -1; var bestD = RADIUS_M
        spots.forEachIndexed { i, s ->
            val d = Geo.distance(p, LatLon(s.lat, s.lon))
            if (d <= bestD) { best = i; bestD = d }
        }
        return best
    }

    fun encode(): String = spots.joinToString(";") { "%.5f,%.5f,%d,%d".format(java.util.Locale.ROOT, it.lat, it.lon, it.count, it.lastSeen) }

    companion object {
        const val RADIUS_M = 60.0
        const val KNOWN_AFTER = 2
        const val MAX_SPOTS = 40

        fun decode(s: String?): RestSpots = RestSpots(s.orEmpty().split(';').mapNotNull { part ->
            val f = part.split(',')
            if (f.size != 4) null else runCatching { Spot(f[0].toDouble(), f[1].toDouble(), f[2].toInt(), f[3].toLong()) }.getOrNull()
        }.toMutableList())
    }
}
