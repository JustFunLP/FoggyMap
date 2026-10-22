package de.foggymap.app.core

import kotlin.math.floor

/**
 * Fixed grid of street-network cells (~1.8 × 1.7 km in Germany). Only streets, railways and rivers
 * are loaded per cell (buildings come separately and lazily, see [BuildingTileKey]), so one download
 * usually covers a whole walk.
 */
data class CellKey(val i: Int, val j: Int) {
    val name get() = "r_${i}_$j"
    /** Cell area expanded by a margin so that junctions of all streets inside the cell are known. */
    fun queryBox(): BBox = core().expand(MARGIN)
    fun core(): BBox = BBox(i * LAT_STEP, j * LON_STEP, (i + 1) * LAT_STEP, (j + 1) * LON_STEP)

    companion object {
        const val LAT_STEP = 0.016
        const val LON_STEP = 0.024
        const val MARGIN = 300.0
        /** Look this far ahead in the direction of travel to load the next cell in time. */
        const val LOOKAHEAD = 300.0
        /** Without a known direction, load a neighbour only when this close to its edge. */
        const val EDGE_PREFETCH = 120.0

        fun of(p: LatLon) = CellKey(floor(p.lat / LAT_STEP).toInt(), floor(p.lon / LON_STEP).toInt())

        /** Cells needed at [p]: the current one plus, if close, the one ahead (or the nearest neighbour). */
        fun needed(p: LatLon, heading: Double? = null): List<CellKey> {
            val c = of(p)
            val result = linkedSetOf(c)
            if (heading != null) {
                val rad = Math.toRadians(heading)
                val ahead = LatLon(p.lat + LOOKAHEAD * Math.cos(rad) / Geo.M_PER_DEG_LAT,
                    p.lon + LOOKAHEAD * Math.sin(rad) / (Geo.M_PER_DEG_LAT * Math.cos(Math.toRadians(p.lat))))
                result += of(ahead)
            } else {
                val inner = c.core().expand(-EDGE_PREFETCH)
                val di = when { p.lat < inner.south -> -1; p.lat > inner.north -> 1; else -> 0 }
                val dj = when { p.lon < inner.west -> -1; p.lon > inner.east -> 1; else -> 0 }
                if (di != 0) result += CellKey(c.i + di, c.j)
                if (dj != 0) result += CellKey(c.i, c.j + dj)
            }
            return result.toList()
        }
    }
}

/**
 * Glue between GPS, map data and reveals. Pure logic: works offline as long as the map cells
 * around the user are cached, and never reports a street twice.
 */
class Explorer(knownRoadIds: Collection<String>) {
    private val models = LinkedHashMap<CellKey, WorldModel>()
    val matcher = MapMatcher()
    private val revealedSegments = HashSet<String>()

    init {
        val segIds = knownRoadIds.filter { it.startsWith("r:") }.map { it.removePrefix("r:") }
        revealedSegments.addAll(segIds)
        matcher.markKnown(segIds)
    }

    data class Step(val result: MapMatcher.Result, val items: List<RevealItem>, val segments: List<Segment>)

    fun hasCell(k: CellKey) = models.containsKey(k)
    fun loadedCells(): Set<CellKey> = models.keys
    fun missingCells(p: LatLon, heading: Double? = null) = CellKey.needed(p, heading).filter { it !in models }

    private var lastPos: LatLon? = null

    fun addCell(k: CellKey, model: WorldModel) {
        models[k] = model
        // Keep memory bounded (a car trip crosses many cells) – but never drop the cells around the
        // user: evict the one farthest away.
        while (models.size > MAX_CELLS) {
            val here = lastPos ?: break
            val far = models.keys.maxByOrNull { Geo.distance(it.core().center, here) } ?: break
            models.remove(far)
        }
    }

    /** Ids of segments synced from friends: they count as known so we do not reveal them again. */
    fun markKnown(roadIds: Collection<String>) {
        val segIds = roadIds.filter { it.startsWith("r:") }.map { it.removePrefix("r:") }
        revealedSegments.addAll(segIds)
        matcher.markKnown(segIds)
    }

    fun onFix(fix: Fix): Step {
        lastPos = fix.pos
        val result = matcher.process(fix) { pos, radius -> candidates(pos, radius) }
        if (result.newlyConfirmed.isEmpty()) return Step(result, emptyList(), emptyList())
        val items = LinkedHashMap<String, RevealItem>()
        val segs = ArrayList<Segment>()
        for (s in result.newlyConfirmed) {
            if (!revealedSegments.add(s.id)) continue
            val model = bestModelFor(s) ?: continue
            val local = model.segmentById[s.id] ?: continue
            segs += local
            model.reveal(local).forEach { items.putIfAbsent(it.id, it) }
        }
        return Step(result, items.values.toList(), segs)
    }

    private fun candidates(p: LatLon, radius: Double): List<Candidate> {
        val byId = HashMap<String, Candidate>()
        for (m in models.values) for (c in m.candidates(p, radius)) {
            val old = byId[c.segment.id]
            if (old == null || c.distance < old.distance) byId[c.segment.id] = c
        }
        return byId.values.toList()
    }

    private fun bestModelFor(s: Segment): WorldModel? {
        val mid = s.points[s.points.size / 2]
        return models.values.filter { it.segmentById[s.id]?.complete == true }
            .minByOrNull { Geo.distance(it.data.bbox.center, mid) }
    }

    companion object { const val MAX_CELLS = 4 }

    /** Street length in the loaded cells (complete segments, de-duplicated) — base for the percentage. */
    fun knownStreetLength(): Pair<Double, Double> {
        val seen = HashMap<String, Double>()
        for (m in models.values) for (s in m.completeSegments) seen.putIfAbsent(s.id, s.length)
        val total = seen.values.sum()
        val explored = seen.filterKeys { it in revealedSegments }.values.sum()
        return explored to total
    }
}
