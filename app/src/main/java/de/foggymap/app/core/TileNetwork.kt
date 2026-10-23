package de.foggymap.app.core

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.index.strtree.STRtree
import org.locationtech.jts.operation.union.UnaryUnionOp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** Web-mercator "global units": the z14 tile grid with 4096 units per tile (MVT precision). */
object TileMath {
    const val E = 4096.0
    const val WORLD = 67_108_864.0          // 2^14 · 4096
    fun gx(lon: Double) = (lon + 180.0) / 360.0 * WORLD
    fun gy(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * WORLD
    }
    fun lon(gx: Double) = gx / WORLD * 360.0 - 180.0
    fun lat(gy: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * gy / WORLD))))
    fun metersPerUnit(lat: Double) = 40_075_016.686 * cos(Math.toRadians(lat)) / WORLD
    fun envelope(b: BBox) = Envelope(gx(b.west), gx(b.east), gy(b.north), gy(b.south))
}

/** z14 web-mercator tile – the level at which OpenFreeMap serves full street and building detail. */
data class TileKey(val x: Int, val y: Int) {
    val name get() = "14_${x}_$y"
    fun bounds() = BBox(TileMath.lat((y + 1) * TileMath.E), TileMath.lon(x * TileMath.E),
        TileMath.lat(y * TileMath.E), TileMath.lon((x + 1) * TileMath.E))
    fun neighbourhood(): List<TileKey> = (-1..1).flatMap { dx -> (-1..1).map { dy -> TileKey(x + dx, y + dy) } }

    companion object {
        fun of(p: LatLon) = TileKey(floor(TileMath.gx(p.lon) / TileMath.E).toInt(), floor(TileMath.gy(p.lat) / TileMath.E).toInt())
        fun covering(b: BBox): List<TileKey> {
            val a = of(LatLon(b.north, b.west)); val z = of(LatLon(b.south, b.east))
            return (a.x..z.x).flatMap { x -> (a.y..z.y).map { y -> TileKey(x, y) } }
        }
    }
}

/**
 * Turns OpenMapTiles vector tiles (as served by OpenFreeMap) into the same street graph the rest
 * of FoggyMap works with – no Overpass needed.
 *
 * Vector tiles have no OSM node ids and lines are cut at tile edges, so this:
 *  1. clips every line to its own tile and re-joins the pieces across tile edges,
 *  2. finds junctions geometrically (shared vertices, T-junctions, at-grade crossings; bridges and
 *     tunnels only connect at their ends),
 *  3. drops separately mapped sidewalks and crossings (tiles do not carry `footway=sidewalk`),
 *  4. attaches street names from the `transportation_name` layer,
 *  5. derives stable ids from coordinates, so every phone computes the same segment ids.
 */
object TileNetwork {
    val LAYERS = setOf("transportation", "transportation_name", "waterway", "building")
    private const val E = TileMath.E

    /** Street graph; [buildings] is computed on first access (it is not needed to start revealing). */
    class Output(val data: OsmData, private val tiles: Map<TileKey, Map<String, Mvt.Layer>>, private val box: BBox) {
        val buildings: List<BuildingFootprint> by lazy { timed("buildings") { buildings(tiles, box) } }
    }

    enum class Kind { ROAD, RAIL, WATER }

    data class Sig(val kind: Kind, val cls: String, val sub: String, val brunnel: String, val layer: Int,
                   val ramp: Boolean, val service: String, val access: String) {
        val ground get() = brunnel != "bridge" && brunnel != "tunnel" && layer == 0
        val group get() = if (ground) "g" else "$brunnel$layer"
        /** Streets for cars (reference for sidewalk / crossing detection); driveways and parking aisles do not count. */
        val carRoad get() = kind == Kind.ROAD && cls != "path" && cls != "track" &&
            !(cls == "service" && (service == "driveway" || service == "parking_aisle" || service == "drive-through"))
        val sidewalkCandidate get() = kind == Kind.ROAD && cls == "path" && sub in setOf("footway", "path", "cycleway", "bridleway", "")
    }

    private class Piece(val tile: TileKey, val sig: Sig, val c: DoubleArray, val openStart: Boolean, val openEnd: Boolean)

    class Line(val sig: Sig, var c: DoubleArray) {
        var name: String? = null
        val n get() = c.size / 2
        fun x(i: Int) = c[2 * i]
        fun y(i: Int) = c[2 * i + 1]
        fun lengthUnits(): Double { var s = 0.0; for (i in 0 until n - 1) s += hypot(x(i + 1) - x(i), y(i + 1) - y(i)); return s }
        fun envelope(): Envelope { val e = Envelope(); for (i in 0 until n) e.expandToInclude(x(i), y(i)); return e }
    }

    // ------------------------------------------------------------------ entry point

    /** Optional timing hook (debug builds / tests). */
    var timing: ((String, Long) -> Unit)? = null
    internal inline fun <T> timed(stage: String, block: () -> T): T {
        val t0 = System.nanoTime(); val r = block(); timing?.invoke(stage, (System.nanoTime() - t0) / 1_000_000); return r
    }

    fun build(tiles: Map<TileKey, Map<String, Mvt.Layer>>, box: BBox): Output {
        val mpu = TileMath.metersPerUnit(box.center.lat)
        val keep = TileMath.envelope(box).also { it.expandBy(150.0 / mpu) }
        val pieces = ArrayList<Piece>()
        val names = ArrayList<Pair<DoubleArray, String>>()
        for ((t, layers) in tiles) {
            val ox = t.x * E; val oy = t.y * E
            layers["transportation"]?.let { l ->
                val s = E / l.extent
                for (f in l.features) {
                    if (f.type != Mvt.LINESTRING) continue
                    val sig = transportSig(f) ?: continue
                    for (p in f.paths()) clipToTile(toGlobal(p, ox, oy, s), t, sig, pieces)
                }
            }
            layers["waterway"]?.let { l ->
                val s = E / l.extent
                for (f in l.features) {
                    if (f.type != Mvt.LINESTRING) continue
                    val cls = f.str("class") ?: continue
                    if (cls != "river" && cls != "canal") continue
                    val sig = Sig(Kind.WATER, cls, "", f.str("brunnel") ?: "", f.num("layer")?.toInt() ?: 0, false, "", "")
                    for (p in f.paths()) clipToTile(toGlobal(p, ox, oy, s), t, sig, pieces)
                }
            }
            layers["transportation_name"]?.let { l ->
                val s = E / l.extent
                for (f in l.features) {
                    if (f.type != Mvt.LINESTRING) continue
                    val nm = (f.str("name:de")?.takeIf { it.isNotBlank() } ?: f.str("name"))?.trim()
                    if (nm.isNullOrEmpty()) continue
                    for (p in f.paths()) names += toGlobal(p, ox, oy, s) to nm
                }
            }
        }
        val all = timed("stitch") { stitch(pieces).filter { it.envelope().intersects(keep) } }
        val roads = ArrayList<Line>(); val barriers = ArrayList<Line>()
        for (l in all) if (l.sig.kind == Kind.ROAD) roads += l else barriers += l

        val streets = timed("sidewalks") { removeSidewalksAndCrossings(roads, mpu) }
        val named = timed("names") { assignNames(streets, names, mpu) }
        timed("junctions") { node(named, tolerance = maxOf(1.2, 0.45 / mpu)) }
        val merged = timed("merge") { mergeThrough(named) }

        val ways = ArrayList<OsmWay>(named.size + barriers.size)
        var id = 1L
        for (l in merged) {
            dedupe(l)
            if (l.n < 2) continue
            ways += OsmWay(id++, LongArray(l.n) { nodeKey(l.x(it), l.y(it)) }, points(l), roadTags(l.sig, l.name))
        }
        for (l in barriers) {
            dedupe(l)
            if (l.n < 2) continue
            ways += OsmWay(id++, LongArray(l.n) { nodeKey(l.x(it), l.y(it)) }, points(l), barrierTags(l.sig))
        }
        return Output(OsmData(ways, box, syntheticIds = true), tiles, box)
    }

    /** Node id from the global position (identical for every cell that sees this point). */
    fun nodeKey(x: Double, y: Double): Long = (Math.round(x * 4.0) shl 30) or Math.round(y * 4.0)

    private fun points(l: Line) = List(l.n) { LatLon(TileMath.lat(l.y(it)), TileMath.lon(l.x(it))) }

    private fun toGlobal(p: IntArray, ox: Double, oy: Double, s: Double) =
        DoubleArray(p.size) { i -> if (i % 2 == 0) ox + p[i] * s else oy + p[i] * s }

    // ------------------------------------------------------------------ attributes

    fun transportSig(f: Mvt.Feature): Sig? {
        val cls = f.str("class") ?: return null
        if (f.bool("indoor")) return null
        val sub = f.str("subclass") ?: ""
        val kind = when (cls) {
            "motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service", "track", "busway" -> Kind.ROAD
            "path" -> if (sub == "corridor" || sub == "platform") return null else Kind.ROAD
            "rail" -> if (sub == "rail" || sub == "narrow_gauge" || sub == "preserved" || sub == "") Kind.RAIL else return null
            "transit" -> if (sub == "light_rail" || sub == "subway") Kind.RAIL else return null
            else -> return null   // ferry, raceway, construction, pier, bus_guideway, tram …
        }
        return Sig(kind, cls, sub, f.str("brunnel") ?: "", f.num("layer")?.toInt() ?: 0, f.bool("ramp"),
            f.str("service") ?: "", f.str("access") ?: "")
    }

    fun roadTags(s: Sig, name: String?): Map<String, String> {
        val t = HashMap<String, String>()
        t["highway"] = when (s.cls) {
            "motorway", "trunk", "primary", "secondary", "tertiary" -> if (s.ramp) "${s.cls}_link" else s.cls
            "minor" -> "residential"
            "path" -> when (s.sub) { "pedestrian" -> "pedestrian"; "footway", "cycleway", "steps", "bridleway", "path" -> s.sub; else -> "path" }
            else -> s.cls
        }
        if (s.service.isNotEmpty()) t["service"] = s.service
        if (s.access == "no") t["access"] = "no"
        brunnelTags(s, t)
        name?.let { t["name"] = it }
        return t
    }

    private fun barrierTags(s: Sig): Map<String, String> {
        val t = HashMap<String, String>()
        if (s.kind == Kind.RAIL) t["railway"] = s.sub.ifEmpty { "rail" } else t["waterway"] = s.cls
        brunnelTags(s, t)
        return t
    }

    private fun brunnelTags(s: Sig, t: MutableMap<String, String>) {
        if (s.brunnel == "bridge") t["bridge"] = "yes"
        if (s.brunnel == "tunnel") t["tunnel"] = "yes"
        if (s.layer != 0) t["layer"] = s.layer.toString()
    }

    // ------------------------------------------------------------------ 1. clip + stitch across tile edges

    private fun clipToTile(c: DoubleArray, t: TileKey, sig: Sig, out: MutableList<Piece>) {
        val n = c.size / 2
        if (n < 2) return
        val x0 = t.x * E; val y0 = t.y * E; val x1 = x0 + E; val y1 = y0 + E
        var cur: DoubleBuilder? = null
        var openStart = false
        fun emit(b: DoubleBuilder, openEnd: Boolean) {
            if (b.points < 2) return
            val a = b.toArray()
            // A piece running exactly along the east/south edge belongs to the neighbour tile.
            val onEast = (0 until a.size / 2).all { a[2 * it] == x1 }
            val onSouth = (0 until a.size / 2).all { a[2 * it + 1] == y1 }
            if (onEast || onSouth) return
            out += Piece(t, sig, a, openStart, openEnd)
        }
        for (i in 0 until n - 1) {
            val ax = c[2 * i]; val ay = c[2 * i + 1]; val bx = c[2 * i + 2]; val by = c[2 * i + 3]
            val r = clipSegment(ax, ay, bx, by, x0, y0, x1, y1) ?: continue
            val t0 = r[0]; val t1 = r[1]
            if (cur == null) {
                cur = DoubleBuilder()
                openStart = t0 > 0.0
                cur.addDistinct(ax + t0 * (bx - ax), ay + t0 * (by - ay))
            }
            cur.addDistinct(ax + t1 * (bx - ax), ay + t1 * (by - ay))
            if (t1 < 1.0) { emit(cur, true); cur = null }
        }
        cur?.let { emit(it, false) }
    }

    /** Liang–Barsky: parameter interval of segment a→b inside the rectangle, or null. */
    private fun clipSegment(ax: Double, ay: Double, bx: Double, by: Double, x0: Double, y0: Double, x1: Double, y1: Double): DoubleArray? {
        var t0 = 0.0; var t1 = 1.0
        val dx = bx - ax; val dy = by - ay
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(ax - x0, x1 - ax, ay - y0, y1 - ay)
        for (k in 0..3) {
            if (p[k] == 0.0) { if (q[k] < 0) return null; continue }
            val r = q[k] / p[k]
            if (p[k] < 0) { if (r > t1) return null; if (r > t0) t0 = r }
            else { if (r < t0) return null; if (r < t1) t1 = r }
        }
        return doubleArrayOf(t0, t1)
    }

    private fun stitch(pieces: List<Piece>): List<Line> {
        val match = 2.5      // units (~1 m): both tiles cut the same original edge at the same tile edge
        val cell = 4.0
        fun ex(e: Int): Double { val c = pieces[e / 2].c; return if (e % 2 == 0) c[0] else c[c.size - 2] }
        fun ey(e: Int): Double { val c = pieces[e / 2].c; return if (e % 2 == 0) c[1] else c[c.size - 1] }
        fun gkey(x: Double, y: Double) = (floor(x / cell).toLong() shl 32) xor (floor(y / cell).toLong() and 0xffffffffL)
        val open = ArrayList<Int>()
        for ((i, p) in pieces.withIndex()) { if (p.openStart) open += 2 * i; if (p.openEnd) open += 2 * i + 1 }
        val grid = HashMap<Long, MutableList<Int>>()
        for (e in open) grid.getOrPut(gkey(ex(e), ey(e))) { ArrayList(2) }.add(e)
        val cand = ArrayList<Triple<Double, Int, Int>>()
        for (e in open) {
            val cx = floor(ex(e) / cell).toLong(); val cy = floor(ey(e) / cell).toLong()
            for (dx in -1L..1L) for (dy in -1L..1L) {
                val list = grid[((cx + dx) shl 32) xor ((cy + dy) and 0xffffffffL)] ?: continue
                for (f in list) {
                    if (f <= e) continue
                    val pe = pieces[e / 2]; val pf = pieces[f / 2]
                    if (pe.tile == pf.tile || pe.sig != pf.sig) continue
                    val d = hypot(ex(e) - ex(f), ey(e) - ey(f))
                    // Prefer end→start pairs (the feature keeps its direction across tiles).
                    if (d <= match) cand += Triple(d + if (e % 2 == f % 2) 0.75 else 0.0, e, f)
                }
            }
        }
        cand.sortBy { it.first }
        val link = IntArray(pieces.size * 2) { -1 }
        for ((_, e, f) in cand) if (link[e] < 0 && link[f] < 0) { link[e] = f; link[f] = e }

        val used = BooleanArray(pieces.size)
        val out = ArrayList<Line>()
        fun walk(start: Int, forward: Boolean) {
            val b = DoubleBuilder()
            var p = start; var fwd = forward
            while (true) {
                used[p] = true
                val c = pieces[p].c
                val n = c.size / 2
                for (k in 0 until n) {
                    val idx = if (fwd) k else n - 1 - k
                    val x = c[2 * idx]; val y = c[2 * idx + 1]
                    if (k == 0 && b.points > 0) b.setLast((b.lastX() + x) / 2, (b.lastY() + y) / 2)   // join point
                    else b.addDistinct(x, y)
                }
                val exit = if (fwd) 2 * p + 1 else 2 * p
                val next = link[exit]
                if (next < 0) break
                val q = next / 2
                if (used[q]) {
                    if (q == start && b.points > 2) {   // closed ring across tiles
                        val mx = (b.lastX() + b.x(0)) / 2; val my = (b.lastY() + b.y(0)) / 2
                        b.setLast(mx, my); b.set(0, mx, my)
                    }
                    break
                }
                p = q; fwd = next % 2 == 0
            }
            if (b.points >= 2) out += Line(pieces[start].sig, b.toArray())
        }
        for (i in pieces.indices) {
            if (used[i]) continue
            if (link[2 * i] < 0) walk(i, true) else if (link[2 * i + 1] < 0) walk(i, false)
        }
        for (i in pieces.indices) if (!used[i]) walk(i, true)
        return out
    }

    // ------------------------------------------------------------------ 3. sidewalks and crossings

    /**
     * Separately mapped sidewalks run alongside a street (1–16 m away, same direction); they are
     * removed so that walking on the pavement reveals the street itself. Short footways that cross
     * a street (zebra crossings) are removed as well.
     */
    internal fun removeSidewalksAndCrossings(roads: List<Line>, mpu: Double): List<Line> {
        val car = roads.filter { it.sig.carRoad }
        val tree = STRtree()
        for ((li, l) in car.withIndex()) for (k in 0 until l.n - 1) {
            val env = Envelope(l.x(k), l.x(k + 1), l.y(k), l.y(k + 1))
            tree.insert(env, longArrayOf(li.toLong(), k.toLong()))
        }
        if (car.isEmpty()) return roads
        val near = 16.0 / mpu; val minD = 1.0 / mpu
        val step = 4.0 / mpu
        val out = ArrayList<Line>(roads.size)
        for (l in roads) {
            if (!l.sig.sidewalkCandidate) { out += l; continue }
            val lenM = l.lengthUnits() * mpu
            if (lenM <= 30.0 && crossesCarRoad(l, car, tree, mpu)) continue
            // Per edge: share of sample points that run alongside a street.
            val attached = BooleanArray(l.n - 1)
            for (k in 0 until l.n - 1) {
                val ax = l.x(k); val ay = l.y(k); val bx = l.x(k + 1); val by = l.y(k + 1)
                val len = hypot(bx - ax, by - ay)
                if (len <= 0.0) continue
                val dir = bearingUnits(bx - ax, by - ay)
                val samples = maxOf(1, (len / step).toInt())
                var hits = 0
                for (s in 0 until samples) {
                    val t = (s + 0.5) / samples
                    val px = ax + t * (bx - ax); val py = ay + t * (by - ay)
                    @Suppress("UNCHECKED_CAST")
                    val items = tree.query(Envelope(px - near, px + near, py - near, py + near)) as List<LongArray>
                    // A street running alongside (cross streets and driveways in between do not matter).
                    var alongside = false
                    for (it in items) {
                        val m = car[it[0].toInt()]; val e = it[1].toInt()
                        val d = pointSegment(px, py, m.x(e), m.y(e), m.x(e + 1), m.y(e + 1))[0]
                        if (d < minD || d > near) continue
                        if (Geo.axisDifference(dir, bearingUnits(m.x(e + 1) - m.x(e), m.y(e + 1) - m.y(e))) <= 25.0) { alongside = true; break }
                    }
                    if (alongside) hits++
                }
                attached[k] = hits >= 0.7 * samples
            }
            // Drop sidewalk runs of 8 m or more; keep the rest (a path may run along a street and then leave it).
            val drop = BooleanArray(l.n - 1)
            var k = 0
            while (k < l.n - 1) {
                var j = k
                var runLen = 0.0
                while (j < l.n - 1 && attached[j] == attached[k]) { runLen += hypot(l.x(j + 1) - l.x(j), l.y(j + 1) - l.y(j)); j++ }
                if (attached[k] && runLen * mpu >= 8.0) for (e in k until j) drop[e] = true
                k = j
            }
            if (drop.none { it }) { out += l; continue }
            var start = -1
            for (e in 0..l.n - 1) {
                val keepEdge = e < l.n - 1 && !drop[e]
                if (keepEdge && start < 0) start = e
                if (!keepEdge && start >= 0) {
                    val part = Line(l.sig, l.c.copyOfRange(2 * start, 2 * e + 2))
                    // Short leftovers of a sidewalk (corners, kerb ramps) are not paths of their own.
                    if (part.lengthUnits() * mpu >= 20.0) out += part
                    start = -1
                }
            }
        }
        return out.filter { it.n >= 2 }
    }

    private fun crossesCarRoad(l: Line, car: List<Line>, tree: STRtree, mpu: Double): Boolean {
        val endGap = 2.0 / mpu
        for (k in 0 until l.n - 1) {
            val env = Envelope(l.x(k), l.x(k + 1), l.y(k), l.y(k + 1))
            @Suppress("UNCHECKED_CAST")
            for (it in tree.query(env) as List<LongArray>) {
                val m = car[it[0].toInt()]; val e = it[1].toInt()
                val r = intersect(l.x(k), l.y(k), l.x(k + 1), l.y(k + 1), m.x(e), m.y(e), m.x(e + 1), m.y(e + 1)) ?: continue
                // Crossing point in the interior of the footway (not where it ends at the street).
                val along = distanceAlong(l, k, r[0])
                val total = l.lengthUnits()
                if (along < endGap || total - along < endGap) continue
                val axis = Geo.axisDifference(bearingUnits(l.x(k + 1) - l.x(k), l.y(k + 1) - l.y(k)),
                    bearingUnits(m.x(e + 1) - m.x(e), m.y(e + 1) - m.y(e)))
                if (axis >= 45.0) return true
            }
        }
        return false
    }

    private fun distanceAlong(l: Line, edge: Int, t: Double): Double {
        var s = 0.0
        for (i in 0 until edge) s += hypot(l.x(i + 1) - l.x(i), l.y(i + 1) - l.y(i))
        return s + t * hypot(l.x(edge + 1) - l.x(edge), l.y(edge + 1) - l.y(edge))
    }

    /** Compass bearing of a direction in global units (y grows southwards). */
    private fun bearingUnits(dx: Double, dy: Double) = (Math.toDegrees(kotlin.math.atan2(dx, -dy)) + 360.0) % 360.0

    // ------------------------------------------------------------------ 4. names

    private fun assignNames(roads: List<Line>, names: List<Pair<DoubleArray, String>>, mpu: Double): MutableList<Line> {
        if (names.isEmpty()) return roads.toMutableList()
        val tree = STRtree()
        for ((ni, nl) in names.withIndex()) {
            val c = nl.first
            for (k in 0 until c.size / 2 - 1) tree.insert(Envelope(c[2 * k], c[2 * k + 2], c[2 * k + 1], c[2 * k + 3]), longArrayOf(ni.toLong(), k.toLong()))
        }
        val tol = maxOf(3.0, 1.2 / mpu)
        val out = ArrayList<Line>(roads.size)
        for (l in roads) {
            val edgeNames = arrayOfNulls<String>(l.n - 1)
            for (k in 0 until l.n - 1) {
                val mx = (l.x(k) + l.x(k + 1)) / 2; val my = (l.y(k) + l.y(k + 1)) / 2
                val dir = bearingUnits(l.x(k + 1) - l.x(k), l.y(k + 1) - l.y(k))
                @Suppress("UNCHECKED_CAST")
                val items = tree.query(Envelope(mx - tol, mx + tol, my - tol, my + tol)) as List<LongArray>
                var best = tol; var nm: String? = null
                for (it in items) {
                    val c = names[it[0].toInt()].first; val e = it[1].toInt()
                    val d = pointSegment(mx, my, c[2 * e], c[2 * e + 1], c[2 * e + 2], c[2 * e + 3])[0]
                    if (d <= best && Geo.axisDifference(dir, bearingUnits(c[2 * e + 2] - c[2 * e], c[2 * e + 3] - c[2 * e + 1])) < 30.0) {
                        best = d; nm = names[it[0].toInt()].second
                    }
                }
                edgeNames[k] = nm
            }
            // Tiny unnamed gaps between two parts of the same street are lookup misses, not a new street.
            for (k in edgeNames.indices) if (edgeNames[k] == null) {
                val prev = (k - 1 downTo 0).firstOrNull { edgeNames[it] != null }
                val next = (k + 1 until edgeNames.size).firstOrNull { edgeNames[it] != null }
                if (prev != null && next != null && edgeNames[prev] == edgeNames[next] && next - prev <= 3) edgeNames[k] = edgeNames[prev]
            }
            var k = 0
            while (k < l.n - 1) {
                var j = k
                while (j < l.n - 1 && edgeNames[j] == edgeNames[k]) j++
                out += Line(l.sig, l.c.copyOfRange(2 * k, 2 * j + 2)).also { it.name = edgeNames[k] }
                k = j
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 2. junctions

    /**
     * Inserts the junction vertices the tile simplification removed and nodes at-grade crossings.
     * Bridges and tunnels never connect in their middle – only at their end points.
     */
    fun node(lines: MutableList<Line>, tolerance: Double) {
        val tree = STRtree()
        for ((li, l) in lines.withIndex()) for (k in 0 until l.n - 1) {
            val env = Envelope(l.x(k), l.x(k + 1), l.y(k), l.y(k + 1)).also { it.expandBy(tolerance) }
            tree.insert(env, longArrayOf(li.toLong(), k.toLong()))
        }
        val inserts = Array(lines.size) { ArrayList<DoubleArray>() }   // edge, t, x, y
        for ((li, l) in lines.withIndex()) {
            // (a) vertex of one line on the interior of another line's edge
            for (k in 0 until l.n) {
                val vx = l.x(k); val vy = l.y(k)
                val endpoint = k == 0 || k == l.n - 1
                @Suppress("UNCHECKED_CAST")
                for (it in tree.query(Envelope(vx, vx, vy, vy)) as List<LongArray>) {
                    val lj = it[0].toInt(); val e = it[1].toInt()
                    if (lj == li && (e == k || e == k - 1)) continue   // own neighbouring edges
                    val m = lines[lj]
                    if (!endpoint && m.sig.group != l.sig.group) continue
                    val r = pointSegment(vx, vy, m.x(e), m.y(e), m.x(e + 1), m.y(e + 1))
                    if (r[0] > tolerance) continue
                    val len = hypot(m.x(e + 1) - m.x(e), m.y(e + 1) - m.y(e))
                    if (r[1] * len <= tolerance || (1 - r[1]) * len <= tolerance) continue   // near a vertex: snapping below
                    inserts[lj] += doubleArrayOf(e.toDouble(), r[1], vx, vy)
                }
            }
            // (b) proper crossings of two edges on the same level
            for (k in 0 until l.n - 1) {
                val env = Envelope(l.x(k), l.x(k + 1), l.y(k), l.y(k + 1))
                @Suppress("UNCHECKED_CAST")
                for (it in tree.query(env) as List<LongArray>) {
                    val lj = it[0].toInt(); val e = it[1].toInt()
                    if (lj < li || (lj == li && e <= k + 1)) continue
                    val m = lines[lj]
                    if (m.sig.group != l.sig.group) continue
                    val r = intersect(l.x(k), l.y(k), l.x(k + 1), l.y(k + 1), m.x(e), m.y(e), m.x(e + 1), m.y(e + 1)) ?: continue
                    val la = hypot(l.x(k + 1) - l.x(k), l.y(k + 1) - l.y(k))
                    val lb = hypot(m.x(e + 1) - m.x(e), m.y(e + 1) - m.y(e))
                    if (r[0] * la <= tolerance || (1 - r[0]) * la <= tolerance) continue
                    if (r[1] * lb <= tolerance || (1 - r[1]) * lb <= tolerance) continue
                    inserts[li] += doubleArrayOf(k.toDouble(), r[0], r[2], r[3])
                    inserts[lj] += doubleArrayOf(e.toDouble(), r[1], r[2], r[3])
                }
            }
        }
        for ((li, l) in lines.withIndex()) {
            val ins = inserts[li]
            if (ins.isEmpty()) continue
            ins.sortWith(compareBy<DoubleArray> { it[0] }.thenBy { it[1] })
            val b = DoubleBuilder()
            var p = 0
            for (k in 0 until l.n) {
                b.addDistinct(l.x(k), l.y(k))
                while (p < ins.size && ins[p][0].toInt() == k) { b.addDistinct(ins[p][2], ins[p][3]); p++ }
            }
            l.c = b.toArray()
        }
        snapVertices(lines, tolerance)
    }

    /**
     * Joins lines that merely continue each other (an OSM way split because e.g. the surface changes,
     * no other street there): a segment then really runs from junction to junction.
     */
    fun mergeThrough(lines: List<Line>): List<Line> {
        val degree = HashMap<Long, Int>()
        for (l in lines) for (k in 0 until l.n) {
            val key = nodeKey(l.x(k), l.y(k))
            degree[key] = (degree[key] ?: 0) + if (k == 0 || k == l.n - 1) 1 else 2
        }
        val ends = HashMap<Long, MutableList<Int>>()   // key -> endpoint ids (line*2 + 0 start / 1 end)
        for ((i, l) in lines.withIndex()) {
            if (l.n < 2) continue
            ends.getOrPut(nodeKey(l.x(0), l.y(0))) { ArrayList(2) }.add(2 * i)
            ends.getOrPut(nodeKey(l.x(l.n - 1), l.y(l.n - 1))) { ArrayList(2) }.add(2 * i + 1)
        }
        val link = IntArray(lines.size * 2) { -1 }
        for ((key, list) in ends) {
            if (list.size != 2 || degree[key] != 2) continue
            val (e, f) = list
            if (e / 2 == f / 2) continue                                  // a closed ring
            val a = lines[e / 2]; val b = lines[f / 2]
            if (a.sig != b.sig || a.name != b.name) continue
            link[e] = f; link[f] = e
        }
        if (link.all { it < 0 }) return lines
        val used = BooleanArray(lines.size)
        val out = ArrayList<Line>(lines.size)
        fun walk(start: Int, forward: Boolean) {
            val b = DoubleBuilder()
            var p = start; var fwd = forward
            while (true) {
                used[p] = true
                val l = lines[p]
                for (k in 0 until l.n) { val idx = if (fwd) k else l.n - 1 - k; b.addDistinct(l.x(idx), l.y(idx)) }
                val next = link[if (fwd) 2 * p + 1 else 2 * p]
                if (next < 0 || used[next / 2]) break
                p = next / 2; fwd = next % 2 == 0
            }
            out += Line(lines[start].sig, b.toArray()).also { it.name = lines[start].name }
        }
        for (i in lines.indices) {
            if (used[i]) continue
            if (link[2 * i] < 0) walk(i, true) else if (link[2 * i + 1] < 0) walk(i, false)
        }
        for (i in lines.indices) if (!used[i]) walk(i, true)   // rings made of several lines
        return out
    }

    /** Vertices of different lines closer than the tolerance become one junction (same coordinates). */
    private fun snapVertices(lines: List<Line>, tolerance: Double) {
        data class V(val line: Int, val idx: Int)
        val cell = tolerance * 2
        val grid = HashMap<Long, MutableList<V>>()
        fun gk(x: Double, y: Double) = (floor(x / cell).toLong() shl 32) xor (floor(y / cell).toLong() and 0xffffffffL)
        for ((li, l) in lines.withIndex()) for (k in 0 until l.n) grid.getOrPut(gk(l.x(k), l.y(k))) { ArrayList(2) }.add(V(li, k))
        val parent = HashMap<V, V>()
        fun find(v: V): V { var r = v; while (true) { val p = parent[r] ?: return r; if (p == r) return r; r = p } }
        for ((li, l) in lines.withIndex()) for (k in 0 until l.n) {
            val a = V(li, k)
            val cx = floor(l.x(k) / cell).toLong(); val cy = floor(l.y(k) / cell).toLong()
            for (dx in -1L..1L) for (dy in -1L..1L) {
                val list = grid[((cx + dx) shl 32) xor ((cy + dy) and 0xffffffffL)] ?: continue
                for (b in list) {
                    if (b.line == li) continue
                    val m = lines[b.line]
                    val d = hypot(m.x(b.idx) - l.x(k), m.y(b.idx) - l.y(k))
                    if (d == 0.0 || d > tolerance) continue
                    val endA = k == 0 || k == l.n - 1
                    val endB = b.idx == 0 || b.idx == m.n - 1
                    if (l.sig.group != m.sig.group && !endA && !endB) continue
                    val ra = find(a); val rb = find(b)
                    if (ra != rb) parent[ra] = rb
                }
            }
        }
        if (parent.isEmpty()) return
        // Canonical coordinate per cluster: the lexicographically smallest member (order independent).
        val best = HashMap<V, DoubleArray>()
        for (v in parent.keys + parent.values) {
            val r = find(v)
            val l = lines[v.line]
            val x = l.x(v.idx); val y = l.y(v.idx)
            val cur = best[r]
            if (cur == null || x < cur[0] || (x == cur[0] && y < cur[1])) best[r] = doubleArrayOf(x, y)
        }
        for (v in parent.keys + parent.values) {
            val c = best[find(v)] ?: continue
            val l = lines[v.line]
            l.c[2 * v.idx] = c[0]; l.c[2 * v.idx + 1] = c[1]
        }
    }

    private fun dedupe(l: Line) {
        val b = DoubleBuilder()
        for (k in 0 until l.n) b.addDistinct(l.x(k), l.y(k))
        l.c = b.toArray()
    }

    // ------------------------------------------------------------------ 5. buildings

    fun buildings(tiles: Map<TileKey, Map<String, Mvt.Layer>>, box: BBox): List<BuildingFootprint> {
        val mpu = TileMath.metersPerUnit(box.center.lat)
        val gf = GeometryFactory()
        val keep = TileMath.envelope(box)
        val minArea = 4.0 / (mpu * mpu)
        val out = ArrayList<BuildingFootprint>()
        val seen = HashSet<Long>()
        fun emit(ring: DoubleArray, h: Double, mh: Double) {
            // Id from the position (OpenFreeMap merges buildings into shared features, so feature ids are not per building).
            var sx = 0.0; var sy = 0.0
            val n = ring.size / 2 - 1
            for (k in 0 until n) { sx += ring[2 * k]; sy += ring[2 * k + 1] }
            var id = -(nodeKey(Math.floor(sx / n), Math.floor(sy / n)) and 0x3fffffffffffffL)
            while (!seen.add(id)) id--
            out += BuildingFootprint(id, List(ring.size / 2) { LatLon(TileMath.lat(ring[2 * it + 1]), TileMath.lon(ring[2 * it])) }, h, mh)
        }
        class Part(val tile: TileKey, val h: Double, val mh: Double, val g: Geometry)
        val edge = ArrayList<Part>()
        for ((t, layers) in tiles) {
            val l = layers["building"] ?: continue
            val s = E / l.extent; val ox = t.x * E; val oy = t.y * E
            val x0 = ox; val y0 = oy; val x1 = ox + E; val y1 = oy + E
            val sq = Envelope(x0, x1, y0, y1)
            val sqPoly by lazy { gf.toGeometry(sq) }
            for (f in l.features) {
                if (f.type != Mvt.POLYGON || f.bool("hide_3d")) continue
                val h = (f.num("render_height") ?: 9.0).coerceIn(2.5, 400.0)
                val mh = (f.num("render_min_height") ?: 0.0).coerceIn(0.0, h - 1)
                for (poly in f.polygons()) {
                    val r = poly[0]
                    if (r.size < 8 || Mvt.signedArea(r) * s * s < minArea) continue
                    val ring = DoubleArray(r.size) { i -> if (i % 2 == 0) ox + r[i] * s else oy + r[i] * s }
                    var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
                    for (k in 0 until ring.size / 2) {
                        val x = ring[2 * k]; val y = ring[2 * k + 1]
                        if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                    }
                    if (maxX < keep.minX || minX > keep.maxX || maxY < keep.minY || minY > keep.maxY) continue
                    if (minX >= x0 && maxX <= x1 && minY >= y0 && maxY <= y1) { emit(ring, h, mh); continue }
                    // Cut by the tile edge (the tile holds it with a buffer): clip exactly, re-join below.
                    val g = try {
                        gf.createPolygon(Array(ring.size / 2) { Coordinate(ring[2 * it], ring[2 * it + 1]) }).let { if (it.isValid) it else it.buffer(0.0) }
                            .intersection(sqPoly)
                    } catch (_: Exception) { continue }
                    if (!g.isEmpty) edge += Part(t, h, mh, g)
                }
            }
        }
        // Re-join buildings that the tile grid cut in two.
        val parent = IntArray(edge.size) { it }
        fun find(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; return r }
        val tree = STRtree()
        edge.forEachIndexed { i, p -> tree.insert(p.g.envelopeInternal.also { it.expandBy(0.6) }, i) }
        for ((i, p) in edge.withIndex()) {
            @Suppress("UNCHECKED_CAST")
            for (j in tree.query(p.g.envelopeInternal) as List<Int>) {
                if (j <= i) continue
                val q = edge[j]
                if (q.tile == p.tile || abs(p.h - q.h) > 0.6 || p.g.distance(q.g) > 0.6) continue
                parent[find(i)] = find(j)
            }
        }
        val groups = HashMap<Int, MutableList<Part>>()
        edge.forEachIndexed { i, p -> groups.getOrPut(find(i)) { ArrayList() }.add(p) }
        for (g in groups.values) {
            val geom = if (g.size == 1) g[0].g else try { UnaryUnionOp.union(g.map { it.g.buffer(0.3) }).buffer(-0.3) } catch (_: Exception) { g[0].g }
            for (k in 0 until geom.numGeometries) {
                val poly = geom.getGeometryN(k) as? Polygon ?: continue
                if (poly.isEmpty || poly.area < minArea) continue
                val cs = poly.exteriorRing.coordinates
                emit(DoubleArray(cs.size * 2) { i -> if (i % 2 == 0) cs[i / 2].x else cs[i / 2].y }, g.maxOf { it.h }, g.minOf { it.mh })
            }
        }
        return out
    }

    // ------------------------------------------------------------------ geometry helpers

    /** [distance, t] of a point to segment a→b. */
    fun pointSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): DoubleArray {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 == 0.0) return doubleArrayOf(hypot(px - ax, py - ay), 0.0)
        val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return doubleArrayOf(hypot(px - (ax + t * dx), py - (ay + t * dy)), t)
    }

    /** Proper intersection of two segments: [t on first, u on second, x, y] or null. */
    fun intersect(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): DoubleArray? {
        val rX = bx - ax; val rY = by - ay; val sX = dx - cx; val sY = dy - cy
        val den = rX * sY - rY * sX
        if (abs(den) < 1e-12) return null
        val t = ((cx - ax) * sY - (cy - ay) * sX) / den
        val u = ((cx - ax) * rY - (cy - ay) * rX) / den
        if (t <= 0.0 || t >= 1.0 || u <= 0.0 || u >= 1.0) return null
        return doubleArrayOf(t, u, ax + t * rX, ay + t * rY)
    }
}

/** Growable list of 2D points stored as interleaved doubles. */
class DoubleBuilder {
    private var a = DoubleArray(32)
    private var size = 0
    val points get() = size / 2
    fun x(i: Int) = a[2 * i]
    fun y(i: Int) = a[2 * i + 1]
    fun lastX() = a[size - 2]
    fun lastY() = a[size - 1]
    fun set(i: Int, x: Double, y: Double) { a[2 * i] = x; a[2 * i + 1] = y }
    fun setLast(x: Double, y: Double) { a[size - 2] = x; a[size - 1] = y }
    fun addDistinct(x: Double, y: Double) {
        if (size >= 2 && a[size - 2] == x && a[size - 1] == y) return
        if (size + 2 > a.size) a = a.copyOf(a.size * 2)
        a[size++] = x; a[size++] = y
    }
    fun toArray(): DoubleArray = a.copyOf(size)
}
