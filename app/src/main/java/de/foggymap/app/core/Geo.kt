package de.foggymap.app.core

import org.locationtech.jts.geom.Coordinate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** Plain WGS84 coordinate. Kept free of Android types so the core can be unit-tested on the JVM. */
data class LatLon(val lat: Double, val lon: Double) {
    fun distanceTo(o: LatLon): Double = Geo.distance(this, o)
}

data class BBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(p: LatLon) = p.lat in south..north && p.lon in west..east
    fun expand(meters: Double): BBox {
        val dLat = meters / Geo.M_PER_DEG_LAT
        val dLon = meters / (Geo.M_PER_DEG_LAT * cos(Math.toRadians((south + north) / 2)))
        return BBox(south - dLat, west - dLon, north + dLat, east + dLon)
    }
    val center get() = LatLon((south + north) / 2, (west + east) / 2)
}

object Geo {
    const val M_PER_DEG_LAT = 111_320.0
    private const val R = 6_371_008.8

    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing in degrees 0..360 (0 = north). */
    fun bearing(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Smallest difference between two undirected line directions, 0..90 degrees. */
    fun axisDifference(a: Double, b: Double): Double {
        var d = abs(a - b) % 180.0
        if (d > 90.0) d = 180.0 - d
        return d
    }

    fun round6(v: Double) = round(v * 1e6) / 1e6
}

/**
 * Local equirectangular projection in metres around an origin. Accurate to well below a metre
 * over a few kilometres, which is all a map cell needs, and lets JTS work in metres.
 */
class LocalProjection(val origin: LatLon) {
    private val mLat = Geo.M_PER_DEG_LAT
    private val mLon = Geo.M_PER_DEG_LAT * cos(Math.toRadians(origin.lat))
    fun toLocal(p: LatLon) = Coordinate((p.lon - origin.lon) * mLon, (p.lat - origin.lat) * mLat)
    fun toGeo(c: Coordinate) = LatLon(origin.lat + c.y / mLat, origin.lon + c.x / mLon)
    /** Compass bearing of a local direction vector. */
    fun bearingOf(dx: Double, dy: Double) = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
}

internal fun Double.toRad() = this * PI / 180.0
