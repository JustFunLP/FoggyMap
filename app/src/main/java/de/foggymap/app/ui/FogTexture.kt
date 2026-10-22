package de.foggymap.app.ui

import android.graphics.Bitmap
import kotlin.math.floor

/** Seamless, procedurally generated cloud texture for the fog layer (no image assets needed). */
object FogTexture {
    fun create(size: Int = 256, seed: Int = 7): Bitmap {
        val px = IntArray(size * size)
        val octaves = intArrayOf(4, 8, 16, 32, 64)
        val weights = floatArrayOf(0.45f, 0.27f, 0.15f, 0.08f, 0.05f)
        val lattices = octaves.map { g -> FloatArray(g * g) { i -> hash(i * 7919 + g * 104729 + seed) } }
        for (y in 0 until size) for (x in 0 until size) {
            var v = 0f
            for (o in octaves.indices) {
                val g = octaves[o]
                val fx = x.toFloat() / size * g; val fy = y.toFloat() / size * g
                v += weights[o] * sample(lattices[o], g, fx, fy)
            }
            // Contrast curve: mostly dark fog with soft brighter wisps.
            val w = ((v - 0.35f) / 0.5f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
            val r = lerp(9f, 46f, w); val gC = lerp(11f, 46f, w); val b = lerp(26f, 84f, w)
            val a = lerp(228f, 250f, w)
            px[y * size + x] = (a.toInt() shl 24) or (r.toInt() shl 16) or (gC.toInt() shl 8) or b.toInt()
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun sample(l: FloatArray, g: Int, x: Float, y: Float): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val tx = smooth(x - x0); val ty = smooth(y - y0)
        fun at(i: Int, j: Int) = l[((j % g + g) % g) * g + ((i % g + g) % g)]   // wraps → tileable
        val a = lerp(at(x0, y0), at(x0 + 1, y0), tx)
        val b = lerp(at(x0, y0 + 1), at(x0 + 1, y0 + 1), tx)
        return lerp(a, b, ty)
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun hash(n: Int): Float {
        var h = n * 374761393 + 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }
}
