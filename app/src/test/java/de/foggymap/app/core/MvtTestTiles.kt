package de.foggymap.app.core

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import java.io.ByteArrayOutputStream

/**
 * Builds vector tiles the way a tile server does: world geometry is cut into z14 tiles with a
 * buffer, coordinates are rounded to the 4096 grid, then encoded as MVT protobuf.
 */
object MvtTestTiles {
    class WorldFeature(val layer: String, val type: Int, val props: Map<String, Any>, val coords: List<DoubleArray>, val id: Long = 0)

    /** Local metres (x east, y north) around [origin] → global units. */
    class Frame(val origin: LatLon) {
        val ox = TileMath.gx(origin.lon); val oy = TileMath.gy(origin.lat)
        val mpu = TileMath.metersPerUnit(origin.lat)
        fun u(xm: Double, ym: Double) = doubleArrayOf(ox + xm / mpu, oy - ym / mpu)
        fun line(layer: String, props: Map<String, Any>, vararg pts: Pair<Double, Double>, id: Long = 0) =
            WorldFeature(layer, Mvt.LINESTRING, props, pts.map { u(it.first, it.second) }, id)
        fun rect(layer: String, props: Map<String, Any>, x: Double, y: Double, w: Double, h: Double, id: Long = 0) =
            WorldFeature(layer, Mvt.POLYGON, props, listOf(u(x, y), u(x + w, y), u(x + w, y + h), u(x, y + h), u(x, y)), id)
        fun ll(xm: Double, ym: Double): LatLon { val g = u(xm, ym); return LatLon(TileMath.lat(g[1]), TileMath.lon(g[0])) }
    }

    fun tiles(features: List<WorldFeature>, keys: Collection<TileKey>, buffer: Int = 64): Map<TileKey, Map<String, Mvt.Layer>> =
        keys.associateWith { t -> Mvt.decode(encodeTile(features, t, buffer), TileNetwork.LAYERS) }

    fun encodeTile(features: List<WorldFeature>, t: TileKey, buffer: Int = 64): ByteArray {
        val x0 = t.x * 4096.0 - buffer; val y0 = t.y * 4096.0 - buffer
        val x1 = (t.x + 1) * 4096.0 + buffer; val y1 = (t.y + 1) * 4096.0 + buffer
        val byLayer = LinkedHashMap<String, MutableList<Triple<WorldFeature, Int, List<IntArray>>>>()
        val gf = GeometryFactory()
        for (f in features) {
            val parts: List<IntArray> = if (f.type == Mvt.LINESTRING) clipLine(f.coords, x0, y0, x1, y1).map { local(it, t) }.filter { it.size >= 4 }
            else {
                val poly = gf.createPolygon(f.coords.map { Coordinate(it[0], it[1]) }.toTypedArray())
                val box = gf.toGeometry(org.locationtech.jts.geom.Envelope(x0, x1, y0, y1))
                val cut: Geometry = poly.intersection(box)
                (0 until cut.numGeometries).mapNotNull { k -> (cut.getGeometryN(k) as? Polygon)?.exteriorRing?.coordinates }
                    .map { cs -> local(cs.map { doubleArrayOf(it.x, it.y) }, t) }
                    .filter { it.size >= 8 }
                    .map { r -> if (Mvt.signedArea(r) < 0) reverseRing(r) else r }
            }
            if (parts.isEmpty()) continue
            byLayer.getOrPut(f.layer) { ArrayList() } += Triple(f, f.type, parts)
        }
        val tile = ByteArrayOutputStream()
        for ((name, list) in byLayer) {
            val keys = ArrayList<String>(); val values = ArrayList<Any>()
            val layer = ByteArrayOutputStream()
            writeVarintField(layer, 15, 2)
            writeBytesField(layer, 1, name.toByteArray())
            for ((f, type, parts) in list) {
                val feat = ByteArrayOutputStream()
                if (f.id != 0L) writeVarintField(feat, 1, f.id)
                val tags = ArrayList<Int>()
                for ((k, v) in f.props) {
                    var ki = keys.indexOf(k); if (ki < 0) { keys += k; ki = keys.size - 1 }
                    var vi = values.indexOf(v); if (vi < 0) { values += v; vi = values.size - 1 }
                    tags += ki; tags += vi
                }
                writePacked(feat, 2, tags)
                writeVarintField(feat, 3, type.toLong())
                writePacked(feat, 4, geometry(parts, type == Mvt.POLYGON))
                writeBytesField(layer, 2, feat.toByteArray())
            }
            for (k in keys) writeBytesField(layer, 3, k.toByteArray())
            for (v in values) {
                val vb = ByteArrayOutputStream()
                when (v) {
                    is String -> writeBytesField(vb, 1, v.toByteArray())
                    is Double -> { writeTag(vb, 3, 1); val bits = java.lang.Double.doubleToLongBits(v); for (i in 0 until 8) vb.write(((bits ushr (8 * i)) and 0xff).toInt()) }
                    is Int -> writeVarintField(vb, 4, v.toLong())
                    is Long -> writeVarintField(vb, 4, v)
                    is Boolean -> writeVarintField(vb, 7, if (v) 1 else 0)
                    else -> writeBytesField(vb, 1, v.toString().toByteArray())
                }
                writeBytesField(layer, 4, vb.toByteArray())
            }
            writeVarintField(layer, 5, 4096)
            writeBytesField(tile, 3, layer.toByteArray())
        }
        return tile.toByteArray()
    }

    private fun reverseRing(r: IntArray): IntArray {
        val n = r.size / 2
        return IntArray(r.size) { i -> val p = n - 1 - i / 2; r[2 * p + i % 2] }
    }

    private fun local(pts: List<DoubleArray>, t: TileKey): IntArray {
        val out = ArrayList<Int>()
        for (p in pts) {
            val x = Math.round(p[0] - t.x * 4096.0).toInt(); val y = Math.round(p[1] - t.y * 4096.0).toInt()
            if (out.size >= 2 && out[out.size - 2] == x && out[out.size - 1] == y) continue
            out += x; out += y
        }
        return out.toIntArray()
    }

    private fun clipLine(c: List<DoubleArray>, x0: Double, y0: Double, x1: Double, y1: Double): List<List<DoubleArray>> {
        val out = ArrayList<List<DoubleArray>>()
        var cur: MutableList<DoubleArray>? = null
        for (i in 0 until c.size - 1) {
            val a = c[i]; val b = c[i + 1]
            var t0 = 0.0; var t1 = 1.0
            val dx = b[0] - a[0]; val dy = b[1] - a[1]
            val p = doubleArrayOf(-dx, dx, -dy, dy); val q = doubleArrayOf(a[0] - x0, x1 - a[0], a[1] - y0, y1 - a[1])
            var ok = true
            for (k in 0..3) {
                if (p[k] == 0.0) { if (q[k] < 0) ok = false; continue }
                val r = q[k] / p[k]
                if (p[k] < 0) { if (r > t1) ok = false else if (r > t0) t0 = r } else { if (r < t0) ok = false else if (r < t1) t1 = r }
            }
            if (!ok) { cur = null; continue }
            if (cur == null) { cur = ArrayList(); out += cur; cur += doubleArrayOf(a[0] + t0 * dx, a[1] + t0 * dy) }
            cur += doubleArrayOf(a[0] + t1 * dx, a[1] + t1 * dy)
            if (t1 < 1.0) cur = null
        }
        return out.filter { it.size >= 2 }
    }

    private fun geometry(parts: List<IntArray>, polygon: Boolean): List<Int> {
        val g = ArrayList<Int>()
        var cx = 0; var cy = 0
        for (p in parts) {
            val n = p.size / 2 - if (polygon) 1 else 0   // rings: last point = first point is implied by ClosePath
            g += (1 and 7) or (1 shl 3)
            g += zz(p[0] - cx); g += zz(p[1] - cy); cx = p[0]; cy = p[1]
            g += (2 and 7) or ((n - 1) shl 3)
            for (i in 1 until n) { g += zz(p[2 * i] - cx); g += zz(p[2 * i + 1] - cy); cx = p[2 * i]; cy = p[2 * i + 1] }
            if (polygon) g += (7 and 7) or (1 shl 3)
        }
        return g
    }

    private fun zz(n: Int) = (n shl 1) xor (n shr 31)
    private fun writeTag(o: ByteArrayOutputStream, field: Int, wire: Int) = writeVarint(o, ((field shl 3) or wire).toLong())
    private fun writeVarint(o: ByteArrayOutputStream, v: Long) {
        var x = v
        while (true) { if (x and 0x7fL.inv() == 0L) { o.write(x.toInt()); return }; o.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
    }
    private fun writeVarintField(o: ByteArrayOutputStream, field: Int, v: Long) { writeTag(o, field, 0); writeVarint(o, v) }
    private fun writeBytesField(o: ByteArrayOutputStream, field: Int, b: ByteArray) { writeTag(o, field, 2); writeVarint(o, b.size.toLong()); o.write(b) }
    private fun writePacked(o: ByteArrayOutputStream, field: Int, vals: List<Int>) {
        val b = ByteArrayOutputStream(); for (v in vals) writeVarint(b, v.toLong() and 0xffffffffL)
        writeBytesField(o, field, b.toByteArray())
    }
}
