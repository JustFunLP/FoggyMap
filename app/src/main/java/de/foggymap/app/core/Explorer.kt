package de.foggymap.app.core

import kotlin.math.floor

/** Fixed grid of map cells (~0.9 × 0.9 km) used to load and cache OpenStreetMap data. */
data class CellKey(val i: Int, val j: Int) {
    val name get() = "c_${i}_$j"
    /** Cell area expanded by a margin so that junctions of all streets inside the cell are known. */
    fun queryBox(): BBox = BBox(i * LAT_STEP, j * LON_STEP, (i + 1) * LAT_STEP, (j + 1) * LON_STEP).expand(MARGIN)
    fun core(): BBox = BBox(i * LAT_STEP, j * LON_STEP, (i + 1) * LAT_STEP, (j + 1) * LON_STEP)

    companion object {
        const val LAT_STEP = 0.008
        const val LON_STEP = 0.012
        const val MARGIN = 350.0
        /** Load neighbour cells early when closer than this to a cell edge. */
        const val PREFETCH = 220.0

        fun of(p: LatLon) = CellKey(floor(p.lat / LAT_STEP).toInt(), floor(p.lon / LON_STEP).toInt())

        fun needed(p: LatLon): List<CellKey> {
            val c = of(p)
            val result = linkedSetOf(c)
            val near = c.core().let { BBox(it.south, it.west, it.north, it.east) }
            val inner = near.expand(-PREFETCH)
            val di = when { p.lat < inner.south -> -1; p.lat > inner.north -> 1; else -> 0 }
            val dj = when { p.lon < inner.west -> -1; p.lon > inner.east -> 1; else -> 0 }
            if (di != 0) result += CellKey(c.i + di, c.j)
            if (dj != 0) result += CellKey(c.i, c.j + dj)
            if (di != 0 && dj != 0) result += CellKey(c.i + di, c.j + dj)
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
    fun missingCells(p: LatLon) = CellKey.needed(p).filter { it !in models }

    fun addCell(k: CellKey, model: WorldModel) {
        models[k] = model
        // Keep memory bounded: a car trip can cross many cells.
        while (models.size > 12) models.remove(models.keys.first())
    }

    /** Ids of segments synced from friends: they count as known so we do not reveal them again. */
    fun markKnown(roadIds: Collection<String>) {
        val segIds = roadIds.filter { it.startsWith("r:") }.map { it.removePrefix("r:") }
        revealedSegments.addAll(segIds)
        matcher.markKnown(segIds)
    }

    fun onFix(fix: Fix): Step {
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

    /** Street length in the loaded cells (complete segments, de-duplicated) — base for the percentage. */
    fun knownStreetLength(): Pair<Double, Double> {
        val seen = HashMap<String, Double>()
        for (m in models.values) for (s in m.completeSegments) seen.putIfAbsent(s.id, s.length)
        val total = seen.values.sum()
        val explored = seen.filterKeys { it in revealedSegments }.values.sum()
        return explored to total
    }
}
