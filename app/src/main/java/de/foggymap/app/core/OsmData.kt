package de.foggymap.app.core

import org.json.JSONObject

/** One OSM way with resolved geometry, as delivered by Overpass `out geom`. */
class OsmWay(
    val id: Long,
    val nodes: LongArray,
    val points: List<LatLon>,
    val tags: Map<String, String>,
) {
    operator fun get(key: String) = tags[key]
}

/**
 * [syntheticIds]: way/node ids do not come from OSM but from coordinates (vector tiles), so segment
 * ids must be derived from the geometry instead of the way id.
 */
class OsmData(val ways: List<OsmWay>, val bbox: BBox, val syntheticIds: Boolean = false)

object OverpassQuery {
    private const val HIGHWAY_FILTER =
        "[\"highway\"][\"highway\"!~\"^(proposed|construction|abandoned|platform|raceway|elevator|bus_stop|rest_area|services|razed)$\"]"

    /** Street network for one cell: streets, railways and rivers with node ids (junctions) and geometry. */
    fun roads(b: BBox): String {
        val box = box(b)
        return """
            [out:json][timeout:60][maxsize:134217728];
            (
              way$HIGHWAY_FILTER($box);
              way["railway"~"^(rail|light_rail|subway|narrow_gauge)$"]($box);
              way["waterway"~"^(river|canal)$"]($box);
            );
            out body geom qt;
        """.trimIndent()
    }

    /** Building footprints of one small tile (tags + geometry only, no node ids). */
    fun buildings(b: BBox): String = """
            [out:json][timeout:40];
            way["building"](${box(b)});
            out tags geom qt;
        """.trimIndent()

    private fun box(b: BBox) = "${b.south},${b.west},${b.north},${b.east}"
}

object OsmParser {
    fun parse(json: String, bbox: BBox): OsmData {
        val root = JSONObject(json)
        val elements = root.optJSONArray("elements") ?: return OsmData(emptyList(), bbox)
        val ways = ArrayList<OsmWay>(elements.length())
        for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue
            if (e.optString("type") != "way") continue
            val geo = e.optJSONArray("geometry") ?: continue
            val nodesArr = e.optJSONArray("nodes")
            if (geo.length() < 2 || (nodesArr != null && geo.length() != nodesArr.length())) continue
            val tagsObj = e.optJSONObject("tags") ?: continue
            val tags = HashMap<String, String>()
            for (k in tagsObj.keys()) tags[k] = tagsObj.optString(k)
            val pts = ArrayList<LatLon>(geo.length())
            var ok = true
            for (j in 0 until geo.length()) {
                val g = geo.optJSONObject(j)
                if (g == null || !g.has("lat")) { ok = false; break }   // null for nodes outside a clipped query
                pts.add(LatLon(g.getDouble("lat"), g.getDouble("lon")))
            }
            if (!ok) continue
            if (nodesArr == null && !tags.containsKey("building")) continue   // streets must have node ids
            val nodes = if (nodesArr == null) LongArray(0) else LongArray(nodesArr.length()) { nodesArr.getLong(it) }
            ways.add(OsmWay(e.getLong("id"), nodes, pts, tags))
        }
        return OsmData(ways, bbox)
    }
}
