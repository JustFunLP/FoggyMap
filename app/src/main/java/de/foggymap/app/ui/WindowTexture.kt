package de.foggymap.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Night-city facade for the 3D buildings: 4 windows × 3 floors per tile (32 × 24 map pixels),
 * some lit warm, some cool, some dark.
 *
 * Against flickering while the map turns (there are no mipmaps for patterns):
 *  - drawn at the screen's own pixel density (rounded down), so the texture is never shrunk on screen,
 *  - fairly large windows with soft, anti-aliased edges instead of tiny hard-edged ones,
 *  - lit windows a little less bright against the wall (lower contrast = less shimmer).
 * Measured in the browser preview with real Berlin tiles: about a quarter less frame-to-frame flicker.
 */
object WindowTexture {
    private const val W = 32f
    private const val H = 24f
    private const val COLS = 4
    private const val FLOORS = 3
    private const val WALL = 0xFF2A2E5C.toInt()
    private const val FLOOR_LINE = 0xFF22254A.toInt()
    // Window colours already blended 75 % towards the wall colour.
    private const val WARM = 0xFFC2A075.toInt()
    private const val DIM = 0xFFA17454.toInt()
    private const val COOL = 0xFF818FCB.toInt()
    private const val DARK = 0xFF1E2041.toInt()

    fun create(pixelRatio: Int, seed: Long = 11): Bitmap {
        val pr = pixelRatio.coerceIn(1, 4)
        val bmp = Bitmap.createBitmap((W * pr).toInt(), (H * pr).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(pr.toFloat(), pr.toFloat())
        c.drawColor(WALL)
        val p = Paint().apply { isAntiAlias = true }
        val rnd = java.util.Random(seed)
        val fh = H / FLOORS; val cw = W / COLS
        for (f in 0 until FLOORS) {
            p.color = FLOOR_LINE
            c.drawRect(RectF(0f, f * fh, W, f * fh + 0.6f), p)
            for (w in 0 until COLS) {
                val r = rnd.nextDouble()
                p.color = when {
                    r < 0.42 -> WARM
                    r < 0.56 -> DIM
                    r < 0.64 -> COOL
                    else -> DARK
                }
                c.drawRect(RectF(w * cw + cw * 0.22f, f * fh + fh * 0.25f, w * cw + cw * 0.78f, f * fh + fh * 0.85f), p)
            }
        }
        bmp.density = 160 * pr      // MapLibre: pixel ratio = density / 160
        return bmp
    }
}
