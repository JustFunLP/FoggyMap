package de.foggymap.app.core

/**
 * Minimal reader for Mapbox Vector Tiles (protobuf, spec 2.1). Only what FoggyMap needs, no
 * dependencies, so it runs in plain JVM unit tests. Layers that are not [wanted] are skipped
 * without decoding their features.
 */
object Mvt {
    const val POINT = 1
    const val LINESTRING = 2
    const val POLYGON = 3

    class Layer(val name: String, val extent: Int, val features: List<Feature>)

    class Feature(val id: Long, val type: Int, val props: Map<String, Any>, private val geometry: IntArray) {
        fun str(key: String): String? = props[key]?.toString()
        fun num(key: String): Double? = when (val v = props[key]) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            is Boolean -> if (v) 1.0 else 0.0
            else -> null
        }
        fun bool(key: String): Boolean = when (val v = props[key]) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v == "true" || v == "1" || v == "yes"
            else -> false
        }

        /**
         * Lines (LINESTRING) or rings (POLYGON) in tile coordinates (x right, y down) as
         * interleaved x,y arrays. Rings are closed (last point = first point).
         */
        fun paths(): List<IntArray> {
            val out = ArrayList<IntArray>()
            var cur: IntArrayBuilder? = null
            var x = 0; var y = 0; var i = 0
            val g = geometry
            while (i < g.size) {
                val cmd = g[i] and 7
                val count = g[i] ushr 3
                i++
                when (cmd) {
                    1 -> repeat(count) {   // MoveTo
                        if (i + 1 >= g.size) return out
                        x += zigzag(g[i]); y += zigzag(g[i + 1]); i += 2
                        cur?.let { if (it.size >= 2) out += it.toArray() }
                        cur = IntArrayBuilder().also { b -> b.add(x, y) }
                    }
                    2 -> repeat(count) {   // LineTo
                        if (i + 1 >= g.size) return out
                        x += zigzag(g[i]); y += zigzag(g[i + 1]); i += 2
                        cur?.add(x, y)
                    }
                    7 -> cur?.let { b -> b.add(b[0], b[1]) }   // ClosePath
                    else -> return out
                }
            }
            cur?.let { if (it.size >= 2) out += it.toArray() }
            return out
        }

        /** Polygons as lists of rings (first = outer), grouped by winding as the spec defines. */
        fun polygons(): List<List<IntArray>> {
            val result = ArrayList<MutableList<IntArray>>()
            for (r in paths()) {
                if (r.size < 8) continue
                val a = signedArea(r)
                if (a > 0) result += mutableListOf(r)
                else if (a < 0) result.lastOrNull()?.add(r)
            }
            return result
        }
    }

    /** Surveyor's formula in tile coordinates; > 0 means exterior ring (clockwise with y down). */
    fun signedArea(r: IntArray): Double {
        var s = 0.0
        var k = 0
        while (k + 3 < r.size) {
            s += r[k].toDouble() * r[k + 3] - r[k + 2].toDouble() * r[k + 1]
            k += 2
        }
        return s / 2
    }

    fun decode(bytes: ByteArray, wanted: Set<String>? = null): Map<String, Layer> {
        val out = HashMap<String, Layer>()
        val r = Pb(bytes, 0, bytes.size)
        while (r.hasMore()) {
            val (field, wire) = r.tag()
            if (field == 3 && wire == 2) {
                val (s, e) = r.lengthDelimited()
                readLayer(bytes, s, e, wanted)?.let { out[it.name] = it }
            } else r.skip(wire)
        }
        return out
    }

    private fun readLayer(bytes: ByteArray, start: Int, end: Int, wanted: Set<String>?): Layer? {
        // First pass: name, keys, values, extent; remember feature ranges.
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val featureRanges = ArrayList<Long>()
        val r = Pb(bytes, start, end)
        while (r.hasMore()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 2 -> name = r.string()
                field == 2 && wire == 2 -> { val (s, e) = r.lengthDelimited(); featureRanges += (s.toLong() shl 32) or e.toLong() }
                field == 3 && wire == 2 -> keys += r.string()
                field == 4 && wire == 2 -> { val (s, e) = r.lengthDelimited(); values += readValue(bytes, s, e) }
                field == 5 && wire == 0 -> extent = r.varint().toInt()
                else -> r.skip(wire)
            }
        }
        if (wanted != null && name !in wanted) return null
        val features = ArrayList<Feature>(featureRanges.size)
        for (fr in featureRanges) {
            val s = (fr ushr 32).toInt(); val e = (fr and 0xffffffffL).toInt()
            readFeature(bytes, s, e, keys, values)?.let { features += it }
        }
        return Layer(name, extent, features)
    }

    private fun readFeature(bytes: ByteArray, start: Int, end: Int, keys: List<String>, values: List<Any>): Feature? {
        var id = 0L
        var type = 0
        val tags = IntArrayBuilder()
        var geom = IntArrayBuilder()
        val r = Pb(bytes, start, end)
        while (r.hasMore()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 0 -> id = r.varint()
                field == 2 && wire == 2 -> r.packed { tags.add(it) }
                field == 2 && wire == 0 -> tags.add(r.varint().toInt())
                field == 3 && wire == 0 -> type = r.varint().toInt()
                field == 4 && wire == 2 -> { geom = IntArrayBuilder(); r.packed { geom.add(it) } }
                field == 4 && wire == 0 -> geom.add(r.varint().toInt())
                else -> r.skip(wire)
            }
        }
        val props = HashMap<String, Any>(tags.size / 2 + 1)
        var k = 0
        while (k + 1 < tags.size) {
            val key = keys.getOrNull(tags[k]); val v = values.getOrNull(tags[k + 1])
            if (key != null && v != null) props[key] = v
            k += 2
        }
        return Feature(id, type, props, geom.toArray())
    }

    private fun readValue(bytes: ByteArray, start: Int, end: Int): Any {
        val r = Pb(bytes, start, end)
        var v: Any = ""
        while (r.hasMore()) {
            val (field, wire) = r.tag()
            v = when {
                field == 1 && wire == 2 -> r.string()
                field == 2 && wire == 5 -> java.lang.Float.intBitsToFloat(r.fixed32()).toDouble()
                field == 3 && wire == 1 -> java.lang.Double.longBitsToDouble(r.fixed64())
                field == 4 && wire == 0 -> r.varint()
                field == 5 && wire == 0 -> r.varint()
                field == 6 && wire == 0 -> r.varint().let { (it ushr 1) xor -(it and 1) }
                field == 7 && wire == 0 -> r.varint() != 0L
                else -> { r.skip(wire); v }
            }
        }
        return v
    }

    private fun zigzag(n: Int) = (n ushr 1) xor -(n and 1)

    /** Tiny protobuf cursor. */
    private class Pb(val b: ByteArray, var pos: Int, val end: Int) {
        fun hasMore() = pos < end
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (pos < end) {
                val c = b[pos++].toInt() and 0xff
                result = result or ((c and 0x7f).toLong() shl shift)
                if (c and 0x80 == 0) return result
                shift += 7
                if (shift > 63) break
            }
            return result
        }
        fun tag(): Pair<Int, Int> { val t = varint(); return (t ushr 3).toInt() to (t and 7).toInt() }
        fun lengthDelimited(): Pair<Int, Int> {
            val len = varint().toInt()
            val s = pos
            val e = (pos + len).coerceAtMost(end)
            pos = e
            return s to e
        }
        fun string(): String { val (s, e) = lengthDelimited(); return String(b, s, e - s, Charsets.UTF_8) }
        fun fixed32(): Int {
            if (pos + 4 > end) { pos = end; return 0 }
            val v = (b[pos].toInt() and 0xff) or ((b[pos + 1].toInt() and 0xff) shl 8) or
                ((b[pos + 2].toInt() and 0xff) shl 16) or ((b[pos + 3].toInt() and 0xff) shl 24)
            pos += 4
            return v
        }
        fun fixed64(): Long {
            val lo = fixed32().toLong() and 0xffffffffL
            val hi = fixed32().toLong() and 0xffffffffL
            return lo or (hi shl 32)
        }
        inline fun packed(each: (Int) -> Unit) {
            val (s, e) = lengthDelimited()
            val save = pos
            pos = s
            val sub = Pb(b, s, e)
            while (sub.hasMore()) each(sub.varint().toInt())
            pos = save
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> lengthDelimited()
                5 -> pos += 4
                else -> pos = end
            }
            if (pos > end) pos = end
        }
    }
}

/** Growable int array without boxing. */
class IntArrayBuilder(capacity: Int = 16) {
    private var a = IntArray(capacity)
    var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun add(x: Int, y: Int) { add(x); add(y) }
    operator fun get(i: Int) = a[i]
    fun toArray(): IntArray = a.copyOf(size)
}
