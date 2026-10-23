package de.foggymap.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The little walking mannequins for you and your group: upright figures that walk on the spot while
 * someone moves, seen from the side, from behind or from the front depending on where they head
 * relative to the screen. A light cone on the ground shows the viewing direction.
 *
 * Drawn once at start (no image files): 2 colours × 4 views × (8 walking frames + standing).
 * Coordinates are in dp of a 32 × 48 box; the feet stand on y = 45.5.
 */
object Figures {
    enum class View { RIGHT, LEFT, BACK, FRONT }
    const val FRAMES = 8
    private const val W = 32f
    private const val H = 48f
    private const val GROUND = 45.5f
    private const val OUTLINE = 0xEB0B0D1A.toInt()

    class Scheme(val id: String, val near: Int, val far: Int, val torso: Int, val head: Int, val visor: Int, val cone: Int)
    val ME = Scheme("me", 0xFFB39DFB.toInt(), 0xFF7357D9.toInt(), 0xFF8B5CF6.toInt(), 0xFFD4C8FE.toInt(), 0xFF3B2A7A.toInt(), 0xFF8B5CF6.toInt())
    val FRIEND = Scheme("friend", 0xFF5EE3F5.toInt(), 0xFF0E93AD.toInt(), 0xFF1CB9D6.toInt(), 0xFFC2F7FD.toInt(), 0xFF0B4E5E.toInt(), 0xFF22D3EE.toInt())

    fun imageName(scheme: Scheme, view: View, frame: Int) =
        "fig-${scheme.id}-${view.name.lowercase()}-${if (frame < 0) "idle" else frame.toString()}"
    fun coneName(scheme: Scheme) = "cone-${scheme.id}"

    /** Which way the figure is shown: [relative] = heading minus map rotation (degrees). */
    fun viewFor(relative: Double?): View {
        if (relative == null) return View.FRONT
        val r = ((relative % 360) + 540) % 360 - 180
        return when {
            kotlin.math.abs(r) < 35 -> View.BACK          // walking "into" the screen
            kotlin.math.abs(r) > 145 -> View.FRONT
            r > 0 -> View.RIGHT
            else -> View.LEFT
        }
    }

    /** All images for the map style. [pr] = pixel ratio (screen density, rounded). */
    fun all(pr: Int): Map<String, Bitmap> {
        val out = LinkedHashMap<String, Bitmap>()
        for (s in listOf(ME, FRIEND)) {
            for (v in View.entries) for (f in -1 until FRAMES) out[imageName(s, v, f)] = walker(s, v, f, pr)
            out[coneName(s)] = cone(s, pr)
        }
        return out
    }

    // ------------------------------------------------------------------ shapes

    private sealed class Shape(val color: (Scheme) -> Int)
    private class Cap(val ax: Float, val ay: Float, val bx: Float, val by: Float, val w: Float, color: (Scheme) -> Int) : Shape(color)
    private class Dot(val x: Float, val y: Float, val r: Float, color: (Scheme) -> Int) : Shape(color)
    private class Oval(val x: Float, val y: Float, val rx: Float, val ry: Float, color: (Scheme) -> Int) : Shape(color)

    private val near: (Scheme) -> Int = { it.near }
    private val far: (Scheme) -> Int = { it.far }
    private val torso: (Scheme) -> Int = { it.torso }
    private val head: (Scheme) -> Int = { it.head }
    private val visor: (Scheme) -> Int = { it.visor }

    private fun rad(d: Double) = d * PI / 180

    private fun shapes(view: View, frame: Int): List<Shape> {
        val walking = frame >= 0
        val tw = 2 * PI * (if (walking) frame.toDouble() / FRAMES else 0.0)
        val sh = ArrayList<Shape>()
        if (view == View.RIGHT || view == View.LEFT) {
            val lean = if (walking) 1.1 else 0.0
            val hipX = 16.0; val hipY = 27.0
            val shoX = 16.0 + lean; val shoY = 16.5
            val headX = 16.0 + lean * 1.4; val headY = 9.6
            class Leg(val kx: Double, val ky: Double, val fx: Double, val fy: Double)
            class Arm(val ex: Double, val ey: Double, val hx: Double, val hy: Double)
            fun leg(i: Int): Leg {
                val ph = tw + i * PI
                val a = if (walking) rad(30.0) * sin(ph) else 0.0
                val bend = if (walking) rad(45.0) * max(0.0, sin(ph + PI / 2)) else 0.0
                val kx = hipX + 8.6 * sin(a); val ky = hipY + 8.6 * cos(a)
                val b = a - bend
                return Leg(kx, ky, kx + 8.4 * sin(b), ky + 8.4 * cos(b))
            }
            fun arm(i: Int): Arm {
                val ph = tw + i * PI + PI
                val a = if (walking) rad(26.0) * sin(ph) else rad(3.0)
                val ex = shoX + 6.6 * sin(a); val ey = shoY + 6.6 * cos(a)
                val fa = a + rad(if (walking) 28.0 else 8.0)
                return Arm(ex, ey, ex + 6.0 * sin(fa), ey + 6.0 * cos(fa))
            }
            fun c(ax: Double, ay: Double, bx: Double, by: Double, w: Double, col: (Scheme) -> Int) =
                Cap(ax.toFloat(), ay.toFloat(), bx.toFloat(), by.toFloat(), w.toFloat(), col)
            val farLeg = leg(1); val nearLeg = leg(0); val farArm = arm(1); val nearArm = arm(0)
            sh += c(hipX, hipY, farLeg.kx, farLeg.ky, 3.6, far); sh += c(farLeg.kx, farLeg.ky, farLeg.fx, farLeg.fy, 3.3, far)
            sh += c(farLeg.fx, farLeg.fy, farLeg.fx + 2.2, farLeg.fy, 2.6, far)
            sh += c(shoX, shoY, farArm.ex, farArm.ey, 3.0, far); sh += c(farArm.ex, farArm.ey, farArm.hx, farArm.hy, 2.7, far)
            sh += c(hipX, hipY, shoX, shoY, 7.0, torso); sh += Dot(hipX.toFloat(), hipY.toFloat(), 3.4f, torso)
            sh += c(hipX, hipY, nearLeg.kx, nearLeg.ky, 3.8, near); sh += c(nearLeg.kx, nearLeg.ky, nearLeg.fx, nearLeg.fy, 3.5, near)
            sh += c(nearLeg.fx, nearLeg.fy, nearLeg.fx + 2.2, nearLeg.fy, 2.8, near)
            sh += Dot((headX - 0.6).toFloat(), (headY + 3.6).toFloat(), 1.9f, torso)
            sh += Dot(headX.toFloat(), headY.toFloat(), 4.7f, head)
            sh += Oval((headX + 2.2).toFloat(), (headY - 0.2).toFloat(), 2.3f, 1.5f, visor)
            sh += c(shoX, shoY, nearArm.ex, nearArm.ey, 3.2, near); sh += c(nearArm.ex, nearArm.ey, nearArm.hx, nearArm.hy, 2.9, near)
            return if (view == View.LEFT) sh.map { mirror(it) } else sh
        }
        // from behind / from the front
        for ((i, side) in listOf(0 to -1, 1 to 1)) {
            val ph = tw + i * PI
            val lift = if (walking) 5.0 * max(0.0, sin(ph)) else 0.0
            val topX = 16 + side * 2.9; val topY = 28.0
            val footX = 16 + side * 3.3; val footY = 44.5 - lift
            val kneeX = 16 + side * 3.4; val kneeY = (topY + footY) / 2 - lift * 0.3
            val col = if ((view == View.FRONT) == (side < 0)) near else far
            sh += Cap(topX.toFloat(), topY.toFloat(), kneeX.toFloat(), kneeY.toFloat(), 4.0f, col)
            sh += Cap(kneeX.toFloat(), kneeY.toFloat(), footX.toFloat(), footY.toFloat(), 3.6f, col)
        }
        sh += Cap(16f, 27f, 16f, 18.5f, 9f, torso); sh += Cap(11.2f, 17.6f, 20.8f, 17.6f, 5f, torso)
        for ((i, side) in listOf(0 to -1, 1 to 1)) {
            val ph = tw + i * PI + PI
            val sw = if (walking) sin(ph) else 0.0
            val shoX = 16 + side * 5.6; val shoY = 17.8
            val handX = 16 + side * (7.0 + 0.8 * max(0.0, sw)); val handY = 30.0 - 4.5 * max(0.0, sw) + 1.0 * max(0.0, -sw)
            val elbowX = 16 + side * 6.9; val elbowY = (shoY + handY) / 2
            sh += Cap(shoX.toFloat(), shoY.toFloat(), elbowX.toFloat(), elbowY.toFloat(), 3.2f, near)
            sh += Cap(elbowX.toFloat(), elbowY.toFloat(), handX.toFloat(), handY.toFloat(), 3.0f, near)
        }
        sh += Dot(16f, 13.6f, 2f, torso); sh += Dot(16f, 9.4f, 4.9f, head)
        if (view == View.FRONT) sh += Oval(16f, 9.8f, 3.2f, 1.4f, visor)
        return sh
    }

    private fun mirror(s: Shape): Shape = when (s) {
        is Cap -> Cap(W - s.ax, s.ay, W - s.bx, s.by, s.w, s.color)
        is Dot -> Dot(W - s.x, s.y, s.r, s.color)
        is Oval -> Oval(W - s.x, s.y, s.rx, s.ry, s.color)
    }

    private fun walker(scheme: Scheme, view: View, frame: Int, pr: Int): Bitmap {
        val sh = shapes(view, frame)
        // Feet on the ground: the lowest point touches y = GROUND (this also makes the body bob).
        var lowest = 0f
        for (s in sh) when (s) {
            is Cap -> lowest = maxOf(lowest, s.ay + s.w / 2, s.by + s.w / 2)
            is Dot -> lowest = maxOf(lowest, s.y + s.r)
            is Oval -> {}
        }
        val dy = GROUND - lowest
        val bmp = Bitmap.createBitmap((W * pr).toInt(), (H * pr).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(pr.toFloat(), pr.toFloat())
        c.translate(0f, dy)
        val p = Paint().apply { isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
        fun draw(s: Shape, color: Int, grow: Float) {
            p.color = color
            when (s) {
                is Cap -> { p.style = Paint.Style.STROKE; p.strokeWidth = s.w + grow * 2; c.drawLine(s.ax, s.ay, s.bx, s.by, p) }
                is Dot -> { p.style = Paint.Style.FILL; c.drawCircle(s.x, s.y, s.r + grow, p) }
                is Oval -> if (grow == 0f) { p.style = Paint.Style.FILL; c.drawOval(RectF(s.x - s.rx, s.y - s.ry, s.x + s.rx, s.y + s.ry), p) }
            }
        }
        for (s in sh) draw(s, OUTLINE, 1f)          // dark outline: readable on light buildings
        for (s in sh) draw(s, s.color(scheme), 0f)
        bmp.density = 160 * pr
        return bmp
    }

    /** Light cone on the ground in the viewing direction (up = heading), with a soft shadow in the middle. */
    private fun cone(scheme: Scheme, pr: Int): Bitmap {
        val size = 72f
        val bmp = Bitmap.createBitmap((size * pr).toInt(), (size * pr).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(pr.toFloat(), pr.toFloat())
        val cx = size / 2; val cy = size / 2
        val rgb = scheme.cone and 0xFFFFFF
        val p = Paint().apply { isAntiAlias = true }
        p.shader = RadialGradient(cx, cy, 34f, intArrayOf(0x9A000000.toInt() or rgb, 0x40000000 or rgb, rgb), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawArc(RectF(cx - 34f, cy - 34f, cx + 34f, cy + 34f), -90f - 32f, 64f, true, p)
        p.shader = RadialGradient(cx, cy, 9f, intArrayOf(0x88000000.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawOval(RectF(cx - 9f, cy - 6f, cx + 9f, cy + 6f), p)
        p.shader = null
        p.color = 0xCC000000.toInt() or rgb
        c.drawCircle(cx, cy, 2.6f, p)
        bmp.density = 160 * pr
        return bmp
    }
}
