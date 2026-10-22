package de.foggymap.app.core

import org.json.JSONArray
import org.json.JSONObject
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier

enum class RevealKind(val code: String) {
    ROAD("r"), BLOCK("b"), AREA("s"), BUILDING("g");
    companion object { fun of(code: String) = entries.firstOrNull { it.code == code } }
}

typealias Ring = List<LatLon>

/**
 * A permanently uncovered piece of the world. Revealed areas form a grow-only set keyed by [id]:
 * merging two players' sets is a plain union, so concurrent updates can never delete progress.
 */
data class RevealItem(
    val id: String,
    val kind: RevealKind,
    /** MultiPolygon: list of polygons, each = outer ring followed by holes. */
    val polygons: List<List<Ring>>,
    val line: List<LatLon>? = null,
    val height: Double = 0.0,
    val minHeight: Double = 0.0,
    val length: Double = 0.0,
    val name: String? = null,
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("id", id).put("k", kind.code)
        o.put("p", JSONArray().apply {
            polygons.forEach { poly -> put(JSONArray().apply { poly.forEach { ring -> put(flat(ring)) } }) }
        })
        line?.let { o.put("l", flat(it)) }
        if (height > 0) o.put("h", Math.round(height * 10) / 10.0)
        if (minHeight > 0) o.put("mh", Math.round(minHeight * 10) / 10.0)
        if (length > 0) o.put("len", Math.round(length * 10) / 10.0)
        name?.let { o.put("n", it.take(80)) }
        return o
    }

    val vertexCount get() = polygons.sumOf { p -> p.sumOf { it.size } }

    companion object {
        val ID_PATTERN = Regex("^[rbsg]:[A-Za-z0-9:._~\\-]{1,110}$")
        private const val MAX_VERTICES = 4000

        fun fromJson(o: JSONObject): RevealItem? = try {
            val id = o.getString("id")
            require(ID_PATTERN.matches(id))
            val kind = RevealKind.of(o.getString("k")) ?: error("kind")
            require(id.startsWith(kind.code + ":"))
            val pArr = o.getJSONArray("p")
            val polys = (0 until pArr.length()).map { i ->
                val rings = pArr.getJSONArray(i)
                (0 until rings.length()).map { j -> unflat(rings.getJSONArray(j)).also { require(it.size >= 4) } }
            }
            require(polys.isNotEmpty() && polys.all { it.isNotEmpty() })
            val line = o.optJSONArray("l")?.let(::unflat)
            val item = RevealItem(id, kind, polys, line, o.optDouble("h", 0.0).orZero(), o.optDouble("mh", 0.0).orZero(),
                o.optDouble("len", 0.0).orZero(), o.optString("n").ifEmpty { null })
            require(item.vertexCount <= MAX_VERTICES)
            item
        } catch (_: Exception) { null }

        private fun Double.orZero() = if (isNaN() || this < 0 || this > 100_000) 0.0 else this

        private fun flat(r: List<LatLon>) = JSONArray().apply { r.forEach { put(Geo.round6(it.lat)); put(Geo.round6(it.lon)) } }
        private fun unflat(a: JSONArray): List<LatLon> {
            require(a.length() % 2 == 0)
            return (0 until a.length() / 2).map {
                val lat = a.getDouble(2 * it); val lon = a.getDouble(2 * it + 1)
                require(lat in -90.0..90.0 && lon in -180.0..180.0)
                LatLon(lat, lon)
            }
        }

        /** Local-metre geometry → geographic polygons, simplified to ~0.6 m. */
        fun polygonsOf(g: Geometry, proj: LocalProjection, tolerance: Double = 0.6): List<List<Ring>> {
            if (g.isEmpty) return emptyList()
            val s = DouglasPeuckerSimplifier.simplify(g, tolerance)
            val polys = when (s) {
                is Polygon -> listOf(s)
                is MultiPolygon -> (0 until s.numGeometries).map { s.getGeometryN(it) as Polygon }
                else -> (0 until s.numGeometries).mapNotNull { s.getGeometryN(it) as? Polygon }
            }
            return polys.filter { it.area >= 4.0 }.map { p ->
                val rings = ArrayList<Ring>()
                rings.add(p.exteriorRing.coordinates.map(proj::toGeo))
                for (h in 0 until p.numInteriorRing) {
                    val hole = p.getInteriorRingN(h)
                    if (hole.coordinates.size >= 4) rings.add(hole.coordinates.map(proj::toGeo))
                }
                rings
            }
        }
    }
}
