package de.foggymap.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Night-city facade for the 3D buildings: 2 wide windows × 3 floors per tile (32 × 24 map pixels),
 * some lit warm, some cool, some dark.
 *
 * Against flickering while the map turns (patterns have no mipmaps, so small sharp details shimmer):
 *  - drawn at the screen's own pixel density (rounded down), so the texture is never shrunk on screen,
 *  - few, wide windows with blurred edges: little fine detail left that could shimmer,
 *  - lit windows clearly but not glaringly brighter than the wall (contrast 60 %).
 * Measured in the browser preview with real Berlin tiles while turning in 0.05° steps: share of
 * flickering pixels 1.85 % (old texture) → 0.85 % (Build 40) → 0.29 % (this one).
 */
object WindowTexture {
    private const val W = 32f
    private const val H = 24f
    private const val COLS = 2
    private const val FLOORS = 3
    private const val WALL = 0xFF2A2E5C.toInt()
    private const val FLOOR_LINE = 0xFF22254A.toInt()
    // Window colours blended 60 % from the wall colour towards the light.
    private const val WARM = 0xFFA38970.toInt()
    private const val DIM = 0xFF896656.toInt()
    private const val COOL = 0xFF707CB4.toInt()
    private const val DARK = 0xFF202346.toInt()
    /** Soft window edges (map pixels). */
    private const val BLUR = 1.0f

    fun create(pixelRatio: Int, seed: Long = 11): Bitmap {
        val pr = pixelRatio.coerceIn(1, 4)
        val bmp = Bitmap.createBitmap((W * pr).toInt(), (H * pr).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(pr.toFloat(), pr.toFloat())
        c.drawColor(WALL)
        val p = Paint().apply { isAntiAlias = true }
        val soft = Paint().apply { isAntiAlias = true; maskFilter = android.graphics.BlurMaskFilter(BLUR, android.graphics.BlurMaskFilter.Blur.NORMAL) }
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
                soft.color = p.color
                c.drawRect(RectF(w * cw + cw * 0.18f, f * fh + fh * 0.28f, w * cw + cw * 0.82f, f * fh + fh * 0.82f), soft)
            }
        }
        bmp.density = 160 * pr      // MapLibre: pixel ratio = density / 160
        return bmp
    }
}
