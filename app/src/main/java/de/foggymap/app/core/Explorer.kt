package de.foggymap.app.core


/**
 * Street-network cell = one z14 map tile (~1.5 × 1.5 km in Germany). Its street graph is built from
 * the tile and its 8 neighbours, so every junction of a street inside the cell (plus [MARGIN]) is known.
 */
data class CellKey(val x: Int, val y: Int) {
    val name get() = "t_${x}_$y"
    val tile get() = TileKey(x, y)
    /** Cell area expanded by a margin so that junctions of all streets inside the cell are known. */
    fun queryBox(): BBox = core().expand(MARGIN)
    fun core(): BBox = tile.bounds()
    /** Tiles whose data the street graph of this cell is built from. */
    fun tiles(): List<TileKey> = tile.neighbourhood()

    companion object {
        const val MARGIN = 300.0
        /** Look this far ahead in the direction of travel to load the next cell in time. */
        const val LOOKAHEAD = 300.0
        /** Without a known direction, load a neighbour only when this close to its edge. */
        const val EDGE_PREFETCH = 120.0

        fun of(p: LatLon) = TileKey.of(p).let { CellKey(it.x, it.y) }

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
                val dy = when { p.lat < inner.south -> 1; p.lat > inner.north -> -1; else -> 0 }   // tile y grows southwards
                val dx = when { p.lon < inner.west -> -1; p.lon > inner.east -> 1; else -> 0 }
                if (dy != 0) result += CellKey(c.x, c.y + dy)
                if (dx != 0) result += CellKey(c.x + dx, c.y)
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

    private val gapFill = GapFill()
    /** Tests can switch the gap filling off to look at the matcher alone. */
    var gapFillEnabled = true
    private var graph: GapFill.Graph? = null
    private var lastGood: Fix? = null

    private fun graph(): GapFill.Graph =
        graph ?: GapFill.Graph(models.values.flatMap { it.segments }.distinctBy { it.id }).also { graph = it }

    fun addCell(k: CellKey, model: WorldModel) {
        models[k] = model
        graph = null
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
        // Sparse fixes (battery saver, batching, fast driving): fill the way between them along the streets.
        val filled = ArrayList<Segment>()
        if (result.accepted) {
            // Only between two fixes that both clearly belong to a movement (never GPS jitter while standing).
            if (result.moving && fix.accuracy <= 25.0) {
                val prev = lastGood
                if (prev != null && gapFillEnabled)
                    gapFill.fill(prev, fix, result.mode == TravelMode.CAR, graph()) { p, r -> candidates(p, r) }
                        .filterTo(filled) { it.matchable && it.id !in revealedSegments }
                lastGood = fix
            } else lastGood = null
        }
        if (filled.isNotEmpty()) matcher.markKnown(filled.map { it.id })
        val confirmed = result.newlyConfirmed + filled
        if (confirmed.isEmpty()) return Step(result, emptyList(), emptyList())
        val items = LinkedHashMap<String, RevealItem>()
        val segs = ArrayList<Segment>()
        for (s in confirmed) {
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

    /**
     * Segments lying inside street corridors that were revealed earlier under other ids (older data
     * source, friends on an older app version) count as known: nothing is revealed or counted twice.
     */
    fun markCovered(model: WorldModel, roads: Collection<RevealItem>): Int {
        val gf = model.gf
        val polys = ArrayList<org.locationtech.jts.geom.Geometry>()
        for (it in roads) {
            if (it.kind != RevealKind.ROAD || it.id.removePrefix("r:") in model.segmentById) continue
            for (p in it.polygons) try {
                val ring = if (p[0].first() == p[0].last()) p[0] else p[0] + p[0].first()
                polys += gf.createPolygon(ring.map(model.proj::toLocal).toTypedArray()).buffer(0.0)
            } catch (_: Exception) { }
        }
        if (polys.isEmpty()) return 0
        val area = try { org.locationtech.jts.operation.union.UnaryUnionOp.union(polys).buffer(2.0) } catch (_: Exception) { return 0 }
        val prepared = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(area)
        var n = 0
        for (s in model.segments) {
            if (!s.matchable || s.id in revealedSegments || !prepared.intersects(s.line)) continue
            val len = s.length
            val k = maxOf(3, (len / 10).toInt())
            val idx = org.locationtech.jts.linearref.LengthIndexedLine(s.line)
            var inside = 0
            for (i in 0..k) if (prepared.contains(gf.createPoint(idx.extractPoint(len * i / k)))) inside++
            if (inside >= 0.85 * (k + 1)) { revealedSegments += s.id; matcher.markKnown(listOf(s.id)); n++ }
        }
        return n
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
