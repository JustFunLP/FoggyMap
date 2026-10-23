package de.foggymap.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * Night-city facade for the 3D buildings: 4 × 4 windows, some lit warm, some cool, some dark.
 * Drawn at 4× and marked as such (density 640), so it is crisp on every screen and the same size
 * everywhere: 32 × 24 map pixels ≈ 4 floors.
 */
object WindowTexture {
    fun create(seed: Long = 11): Bitmap {
        val s = 2   // the layout below is designed for 2×; drawn at 4× = 2 × s
        val bmp = Bitmap.createBitmap(64 * s, 48 * s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint().apply { isAntiAlias = false }
        c.drawColor(0xFF282C58.toInt())
        val rnd = java.util.Random(seed)
        for (floor in 0 until 4) {
            p.color = 0xFF1E2142.toInt()
            c.drawRect(0f, (floor * 12 * s).toFloat(), (64 * s).toFloat(), ((floor * 12 + 1) * s).toFloat(), p)
            for (w in 0 until 4) {
                val r = rnd.nextDouble()
                p.color = when {
                    r < 0.42 -> 0xFFFFCE80.toInt()     // warm light
                    r < 0.56 -> 0xFFD6924E.toInt()     // dim warm
                    r < 0.64 -> 0xFFAABAFF.toInt()     // cool (TV, LED)
                    else -> 0xFF161830.toInt()         // dark
                }
                val x0 = (w * 16 + 4) * s; val y0 = (floor * 12 + 3) * s
                c.drawRect(x0.toFloat(), y0.toFloat(), (x0 + 8 * s).toFloat(), (y0 + 7 * s).toFloat(), p)
            }
        }
        bmp.density = 640
        return bmp
    }
}
