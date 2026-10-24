package de.foggymap.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import de.foggymap.app.core.TravelMode
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The little figures for you and your group, one per way of moving: a walking mannequin, a cyclist,
 * a car and a train. They move on the spot while someone is under way (legs, pedals, wheels) and
 * stand still otherwise. Side, back or front view follows the direction relative to the screen; a light
 * cone on the ground shows the viewing direction.
 *
 * Drawn once at start (no image files). Coordinates are dp; the figures stand on the bottom edge.
 */
object Figures {
    enum class View { RIGHT, LEFT, BACK, FRONT }
    enum class Kind(val frames: Int, val id: String) { WALK(8, "walk"), BIKE(6, "bike"), CAR(4, "car"), TRAIN(4, "train") }
    /** Longest animation (the frame counter runs 0 until this). */
    const val FRAMES = 8
    private const val OUTLINE = 0xEB0B0D1A.toInt()

    class Scheme(val id: String, val near: Int, val far: Int, val torso: Int, val head: Int, val visor: Int, val cone: Int)
    val ME = Scheme("me", 0xFFB39DFB.toInt(), 0xFF7357D9.toInt(), 0xFF8B5CF6.toInt(), 0xFFD4C8FE.toInt(), 0xFF3B2A7A.toInt(), 0xFF8B5CF6.toInt())
    val FRIEND = Scheme("friend", 0xFF5EE3F5.toInt(), 0xFF0E93AD.toInt(), 0xFF1CB9D6.toInt(), 0xFFC2F7FD.toInt(), 0xFF0B4E5E.toInt(), 0xFF22D3EE.toInt())
    private const val DARK = 0xFF1A1C38.toInt()
    private const val GLASS = 0xFF9FC2FF.toInt()
    private const val LIGHT = 0xFFFFE08A.toInt()
    private const val RED = 0xFFFF5A6E.toInt()
    private const val TIRE = 0xFF20223D.toInt()
    private const val RIM = 0xFFD9DCEF.toInt()

    fun kindFor(mode: TravelMode?) = when (mode) {
        TravelMode.BIKE -> Kind.BIKE
        TravelMode.CAR -> Kind.CAR
        TravelMode.TRAIN -> Kind.TRAIN
        else -> Kind.WALK
    }

    /** Front and back of car and train have nothing that moves: one picture each. */
    private fun animated(kind: Kind, view: View) = kind == Kind.WALK || kind == Kind.BIKE || view == View.LEFT || view == View.RIGHT

    fun imageName(scheme: Scheme, kind: Kind, view: View, frame: Int): String {
        val f = if (frame < 0 || !animated(kind, view)) "idle" else (frame % kind.frames).toString()
        return "fig-${scheme.id}-${kind.id}-${view.name.lowercase()}-$f"
    }
    fun coneName(scheme: Scheme) = "cone-${scheme.id}"

    /** Which way the figure is shown: [relative] = heading minus map rotation (degrees). */
    fun viewFor(relative: Double?): View {
        if (relative == null) return View.FRONT
        val r = ((relative % 360) + 540) % 360 - 180
        return when {
            kotlin.math.abs(r) < 35 -> View.BACK          // moving "into" the screen
            kotlin.math.abs(r) > 145 -> View.FRONT
            r > 0 -> View.RIGHT
            else -> View.LEFT
        }
    }

    /** All images for the map style. [pr] = pixel ratio (screen density, rounded). */
    fun all(pr: Int): Map<String, Bitmap> {
        val out = LinkedHashMap<String, Bitmap>()
        for (s in listOf(ME, FRIEND)) {
            for (k in Kind.entries) for (v in View.entries) {
                if (animated(k, v)) for (f in -1 until k.frames) out[imageName(s, k, v, f)] = draw(s, k, v, f, pr)
                else out[imageName(s, k, v, -1)] = draw(s, k, v, -1, pr)
            }
            out[coneName(s)] = cone(s, pr)
        }
        return out
    }

    // ------------------------------------------------------------------ shapes

    private enum class Role { NEAR, FAR, TORSO, HEAD, VISOR, DARK, GLASS, LIGHT, RED, TIRE, RIM }
    private sealed class Shape(val role: Role, val outline: Boolean)
    private class Cap(val ax: Float, val ay: Float, val bx: Float, val by: Float, val w: Float, role: Role, outline: Boolean = true) : Shape(role, outline)
    private class Dot(val x: Float, val y: Float, val r: Float, role: Role, outline: Boolean = true) : Shape(role, outline)
    private class Oval(val x: Float, val y: Float, val rx: Float, val ry: Float, role: Role) : Shape(role, false)
    private class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val r: Float, role: Role, outline: Boolean = true, val shade: Boolean = false) : Shape(role, outline)
    private class Poly(val pts: FloatArray, role: Role, outline: Boolean = true, val shade: Boolean = false) : Shape(role, outline)
    private class Ring(val x: Float, val y: Float, val r: Float, val w: Float, role: Role) : Shape(role, false)

    private class Sketch(val w: Float, val h: Float) {
        val shapes = ArrayList<Shape>()
        fun cap(ax: Double, ay: Double, bx: Double, by: Double, w: Double, role: Role, outline: Boolean = true) {
            shapes += Cap(ax.toFloat(), ay.toFloat(), bx.toFloat(), by.toFloat(), w.toFloat(), role, outline)
        }
        fun dot(x: Double, y: Double, r: Double, role: Role, outline: Boolean = true) { shapes += Dot(x.toFloat(), y.toFloat(), r.toFloat(), role, outline) }
        fun box(x0: Double, y0: Double, x1: Double, y1: Double, r: Double, role: Role, outline: Boolean = true, shade: Boolean = false) {
            shapes += Box(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), r.toFloat(), role, outline, shade)
        }
        fun poly(vararg p: Double, role: Role, outline: Boolean = true, shade: Boolean = false) {
            shapes += Poly(FloatArray(p.size) { p[it].toFloat() }, role, outline, shade)
        }
        fun wheel(cx: Double, cy: Double, r: Double, ang: Double) {
            dot(cx, cy, r, Role.TIRE)
            shapes += Ring(cx.toFloat(), cy.toFloat(), (r - 1.0).toFloat(), 0.8f, Role.RIM)
            for (i in 0 until 3) {
                val a = ang + i * PI * 2 / 3
                cap(cx + cos(a) * (r - 1.5), cy + sin(a) * (r - 1.5), cx - cos(a) * (r - 1.5), cy - sin(a) * (r - 1.5), 0.7, Role.RIM, false)
            }
            dot(cx, cy, 1.3, Role.RIM, false)
        }
    }

    private fun rad(d: Double) = d * PI / 180

    private fun draw(scheme: Scheme, kind: Kind, view: View, frame: Int, pr: Int): Bitmap {
        val side = view == View.RIGHT || view == View.LEFT
        val sk = when (kind) {
            Kind.WALK -> walker(view, frame)
            Kind.BIKE -> if (side) bikeSide(frame) else bikeEnd(frame, view == View.FRONT)
            Kind.CAR -> if (side) carSide(frame) else carEnd(view == View.FRONT)
            Kind.TRAIN -> if (side) trainSide(frame) else trainEnd(view == View.FRONT)
        }
        return render(sk, scheme, pr, mirror = view == View.LEFT)
    }

    private fun color(s: Scheme, r: Role) = when (r) {
        Role.NEAR -> s.near; Role.FAR -> s.far; Role.TORSO -> s.torso; Role.HEAD -> s.head; Role.VISOR -> s.visor
        Role.DARK -> DARK; Role.GLASS -> GLASS; Role.LIGHT -> LIGHT; Role.RED -> RED; Role.TIRE -> TIRE; Role.RIM -> RIM
    }

    private fun render(sk: Sketch, scheme: Scheme, pr: Int, mirror: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap((sk.w * pr).toInt(), (sk.h * pr).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(pr.toFloat(), pr.toFloat())
        if (mirror) c.scale(-1f, 1f, sk.w / 2, sk.h / 2)
        val p = Paint().apply { isAntiAlias = true; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        fun one(s: Shape, col: Int, grow: Float) {
            p.color = col; p.shader = null
            when (s) {
                is Cap -> { p.style = Paint.Style.STROKE; p.strokeWidth = s.w + grow * 2; c.drawLine(s.ax, s.ay, s.bx, s.by, p) }
                is Dot -> { p.style = Paint.Style.FILL; c.drawCircle(s.x, s.y, s.r + grow, p) }
                is Oval -> { p.style = Paint.Style.FILL; c.drawOval(RectF(s.x - s.rx, s.y - s.ry, s.x + s.rx, s.y + s.ry), p) }
                is Box -> {
                    p.style = Paint.Style.FILL
                    // A little light from above: the bodies of car and train look rounded ("3D").
                    if (s.shade && grow == 0f) p.shader = LinearGradient(0f, s.y0, 0f, s.y1, intArrayOf(lighten(col, 0.28f), col, darken(col, 0.25f)),
                        floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                    c.drawRoundRect(RectF(s.x0 - grow, s.y0 - grow, s.x1 + grow, s.y1 + grow), s.r + grow, s.r + grow, p)
                }
                is Poly -> {
                    val path = Path().apply {
                        moveTo(s.pts[0], s.pts[1]); var i = 2
                        while (i < s.pts.size) { lineTo(s.pts[i], s.pts[i + 1]); i += 2 }
                        close()
                    }
                    if (grow > 0f) { p.style = Paint.Style.STROKE; p.strokeWidth = grow * 2; c.drawPath(path, p) }
                    p.style = Paint.Style.FILL
                    if (s.shade && grow == 0f) {
                        var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                        for (k in 1 until s.pts.size step 2) { y0 = min(y0, s.pts[k]); y1 = max(y1, s.pts[k]) }
                        p.shader = LinearGradient(0f, y0, 0f, y1, intArrayOf(lighten(col, 0.3f), col), null, Shader.TileMode.CLAMP)
                    }
                    c.drawPath(path, p)
                }
                is Ring -> if (grow == 0f) { p.style = Paint.Style.STROKE; p.strokeWidth = s.w; c.drawCircle(s.x, s.y, s.r, p) }
            }
        }
        for (s in sk.shapes) if (s.outline) one(s, OUTLINE, 1f)     // dark outline: readable on light buildings
        for (s in sk.shapes) one(s, color(scheme, s.role), 0f)
        bmp.density = 160 * pr
        return bmp
    }

    private fun lighten(c: Int, t: Float): Int = mix(c, 0xFFFFFFFF.toInt(), t)
    private fun darken(c: Int, t: Float): Int = mix(c, 0xFF000000.toInt(), t)
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, s: Int) = (x shr s) and 0xFF
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    // ------------------------------------------------------------------ walking mannequin (32 × 48)

    private fun walker(view: View, frame: Int): Sketch {
        val sk = Sketch(32f, 48f)
        val walking = frame >= 0
        val tw = 2 * PI * (if (walking) frame.toDouble() / Kind.WALK.frames else 0.0)
        val parts = ArrayList<Shape>()
        fun c(ax: Double, ay: Double, bx: Double, by: Double, w: Double, r: Role) { parts += Cap(ax.toFloat(), ay.toFloat(), bx.toFloat(), by.toFloat(), w.toFloat(), r) }
        fun d(x: Double, y: Double, r: Double, role: Role, outline: Boolean = true) { parts += Dot(x.toFloat(), y.toFloat(), r.toFloat(), role, outline) }
        if (view == View.RIGHT || view == View.LEFT) {
            val lean = if (walking) 1.1 else 0.0
            val hipX = 16.0; val hipY = 27.0; val shoX = 16.0 + lean; val shoY = 16.5; val headX = 16.0 + lean * 1.4; val headY = 9.6
            fun leg(i: Int): DoubleArray {
                val ph = tw + i * PI
                val a = if (walking) rad(30.0) * sin(ph) else 0.0
                val bend = if (walking) rad(45.0) * max(0.0, sin(ph + PI / 2)) else 0.0
                val kx = hipX + 8.6 * sin(a); val ky = hipY + 8.6 * cos(a); val b = a - bend
                return doubleArrayOf(kx, ky, kx + 8.4 * sin(b), ky + 8.4 * cos(b))
            }
            fun arm(i: Int): DoubleArray {
                val ph = tw + i * PI + PI
                val a = if (walking) rad(26.0) * sin(ph) else rad(3.0)
                val ex = shoX + 6.6 * sin(a); val ey = shoY + 6.6 * cos(a); val fa = a + rad(if (walking) 28.0 else 8.0)
                return doubleArrayOf(ex, ey, ex + 6.0 * sin(fa), ey + 6.0 * cos(fa))
            }
            val fl = leg(1); val nl = leg(0); val fa = arm(1); val na = arm(0)
            c(hipX, hipY, fl[0], fl[1], 3.6, Role.FAR); c(fl[0], fl[1], fl[2], fl[3], 3.3, Role.FAR); c(fl[2], fl[3], fl[2] + 2.2, fl[3], 2.6, Role.FAR)
            c(shoX, shoY, fa[0], fa[1], 3.0, Role.FAR); c(fa[0], fa[1], fa[2], fa[3], 2.7, Role.FAR)
            c(hipX, hipY, shoX, shoY, 7.0, Role.TORSO); d(hipX, hipY, 3.4, Role.TORSO)
            c(hipX, hipY, nl[0], nl[1], 3.8, Role.NEAR); c(nl[0], nl[1], nl[2], nl[3], 3.5, Role.NEAR); c(nl[2], nl[3], nl[2] + 2.2, nl[3], 2.8, Role.NEAR)
            d(headX - 0.6, headY + 3.6, 1.9, Role.TORSO); d(headX, headY, 4.7, Role.HEAD)
            parts += Oval((headX + 2.2).toFloat(), (headY - 0.2).toFloat(), 2.3f, 1.5f, Role.VISOR)
            c(shoX, shoY, na[0], na[1], 3.2, Role.NEAR); c(na[0], na[1], na[2], na[3], 2.9, Role.NEAR)
        } else {
            for ((i, side) in listOf(0 to -1, 1 to 1)) {
                val lift = if (walking) 5.0 * max(0.0, sin(tw + i * PI)) else 0.0
                val topX = 16 + side * 2.9; val footX = 16 + side * 3.3; val footY = 44.5 - lift
                val kneeX = 16 + side * 3.4; val kneeY = (28.0 + footY) / 2 - lift * 0.3
                val role = if ((view == View.FRONT) == (side < 0)) Role.NEAR else Role.FAR
                c(topX, 28.0, kneeX, kneeY, 4.0, role); c(kneeX, kneeY, footX, footY, 3.6, role)
            }
            c(16.0, 27.0, 16.0, 18.5, 9.0, Role.TORSO); c(11.2, 17.6, 20.8, 17.6, 5.0, Role.TORSO)
            for ((i, side) in listOf(0 to -1, 1 to 1)) {
                val sw = if (walking) sin(tw + i * PI + PI) else 0.0
                val handX = 16 + side * (7.0 + 0.8 * max(0.0, sw)); val handY = 30.0 - 4.5 * max(0.0, sw) + 1.0 * max(0.0, -sw)
                val elbowX = 16 + side * 6.9; val elbowY = (17.8 + handY) / 2
                c(16 + side * 5.6, 17.8, elbowX, elbowY, 3.2, Role.NEAR); c(elbowX, elbowY, handX, handY, 3.0, Role.NEAR)
            }
            d(16.0, 13.6, 2.0, Role.TORSO); d(16.0, 9.4, 4.9, Role.HEAD)
            if (view == View.FRONT) parts += Oval(16f, 9.8f, 3.2f, 1.4f, Role.VISOR)
        }
        // Feet on the ground (this also makes the body bob while walking).
        var lowest = 0f
        for (s in parts) when (s) { is Cap -> lowest = maxOf(lowest, s.ay + s.w / 2, s.by + s.w / 2); is Dot -> lowest = maxOf(lowest, s.y + s.r); else -> {} }
        val dy = 45.5f - lowest
        for (s in parts) sk.shapes += when (s) {
            is Cap -> Cap(s.ax, s.ay + dy, s.bx, s.by + dy, s.w, s.role, s.outline)
            is Dot -> Dot(s.x, s.y + dy, s.r, s.role, s.outline)
            is Oval -> Oval(s.x, s.y + dy, s.rx, s.ry, s.role)
            else -> s
        }
        return sk
    }

    // ------------------------------------------------------------------ cyclist (40 × 48)

    private fun bikeSide(frame: Int): Sketch {
        val sk = Sketch(40f, 48f)
        val th = 2 * PI * (if (frame >= 0) frame.toDouble() / Kind.BIKE.frames else 0.12)
        val rwX = 9.5; val fwX = 30.5; val wY = 39.5; val bbX = 19.0; val bbY = 38.5; val seatX = 15.5; val seatY = 26.5; val headX = 28.0; val headY = 27.0
        sk.wheel(rwX, wY, 6.8, th); sk.wheel(fwX, wY, 6.8, th)
        fun leg(ph: Double, role: Role) {
            val px = bbX + 3.6 * cos(ph); val py = bbY + 3.6 * sin(ph)
            val hipX = 15.8; val hipY = 24.5; val l1 = 8.6; val l2 = 8.6
            val dx = px - hipX; val dy = py - hipY; val dd = min(hypot(dx, dy), l1 + l2 - 0.01)
            val a = atan2(dy, dx); val b = acos(((l1 * l1 + dd * dd - l2 * l2) / (2 * l1 * dd)).coerceIn(-1.0, 1.0))
            val kx = hipX + l1 * cos(a - b); val ky = hipY + l1 * sin(a - b)
            sk.cap(hipX, hipY, kx, ky, 3.6, role); sk.cap(kx, ky, px, py, 3.3, role); sk.cap(px, py, px + 2.0, py, 2.4, role)
        }
        leg(th + PI, Role.FAR)
        sk.cap(rwX, wY, bbX, bbY, 1.6, Role.TORSO); sk.cap(bbX, bbY, seatX, seatY, 1.6, Role.TORSO); sk.cap(rwX, wY, seatX, seatY, 1.4, Role.TORSO)
        sk.cap(seatX, seatY, headX, headY, 1.6, Role.TORSO); sk.cap(bbX, bbY, headX, headY, 1.6, Role.TORSO); sk.cap(headX, headY, fwX, wY, 1.6, Role.TORSO)
        sk.cap(headX, headY, 29.0, 23.5, 1.4, Role.TORSO); sk.cap(27.5, 23.5, 31.0, 23.5, 1.4, Role.DARK)
        sk.cap(13.5, 25.8, 18.0, 25.8, 2.0, Role.DARK)
        sk.cap(15.8, 24.5, 22.8, 15.5, 6.6, Role.TORSO); sk.dot(15.8, 24.5, 3.2, Role.TORSO)
        sk.dot(24.8, 13.2, 1.8, Role.TORSO, false); sk.dot(25.6, 9.8, 4.5, Role.HEAD); sk.shapes += Oval(27.7f, 9.7f, 1.6f, 1.4f, Role.VISOR)
        leg(th, Role.NEAR)
        sk.cap(22.8, 15.5, 26.6, 20.4, 3.1, Role.NEAR); sk.cap(26.6, 20.4, 29.5, 23.6, 2.8, Role.NEAR)
        return sk
    }

    private fun bikeEnd(frame: Int, front: Boolean): Sketch {
        val sk = Sketch(40f, 48f)
        val th = 2 * PI * (if (frame >= 0) frame.toDouble() / Kind.BIKE.frames else 0.0)
        sk.box(18.4, 33.0, 21.6, 46.0, 1.6, Role.TIRE)
        sk.cap(20.0, 30.0, 20.0, 36.0, 1.6, Role.TORSO)
        sk.cap(12.5, 24.5, 27.5, 24.5, 1.5, Role.DARK)
        for ((i, side) in listOf(0 to -1, 1 to 1)) {
            val lift = if (frame >= 0) 3.5 * max(0.0, sin(th + i * PI)) else 1.5 * i
            val hipX = 20 + side * 2.6; val footX = 20 + side * 3.6; val footY = 39.5 - lift
            val kneeX = 20 + side * 4.4; val kneeY = (27.0 + footY) / 2 - lift * 0.2
            val role = if (front == (side < 0)) Role.NEAR else Role.FAR
            sk.cap(hipX, 27.0, kneeX, kneeY, 3.8, role); sk.cap(kneeX, kneeY, footX, footY, 3.4, role)
        }
        sk.cap(20.0, 27.0, 20.0, 17.5, 8.5, Role.TORSO); sk.cap(15.5, 16.8, 24.5, 16.8, 4.6, Role.TORSO)
        for (side in listOf(-1, 1)) {
            sk.cap(20 + side * 5.2, 17.2, 20 + side * 7.0, 21.5, 3.0, Role.NEAR); sk.cap(20 + side * 7.0, 21.5, 20 + side * 7.4, 24.5, 2.8, Role.NEAR)
        }
        sk.dot(20.0, 12.8, 1.9, Role.TORSO, false); sk.dot(20.0, 8.8, 4.7, Role.HEAD)
        if (front) { sk.box(17.0, 8.4, 23.0, 10.2, 0.9, Role.VISOR, false); sk.dot(20.0, 31.5, 1.3, Role.LIGHT, false) }
        else sk.dot(20.0, 31.8, 1.3, Role.RED, false)
        return sk
    }

    // ------------------------------------------------------------------ car (48 × 36 side, 36 × 36 front/back)

    private fun carSide(frame: Int): Sketch {
        val sk = Sketch(48f, 36f)
        val th = 2 * PI * (if (frame >= 0) frame.toDouble() / Kind.CAR.frames else 0.1) / 3
        val b = if (frame >= 0) 0.35 * sin(th * 6) else 0.0
        sk.box(3.0, 17 + b, 45.0, 29 + b, 4.5, Role.TORSO, shade = true)
        sk.poly(12.0, 18 + b, 16.5, 9 + b, 31.0, 9 + b, 37.0, 18 + b, role = Role.TORSO, shade = true)
        sk.poly(14.3, 17.6 + b, 17.8, 11 + b, 23.4, 11 + b, 23.4, 17.6 + b, role = Role.GLASS, outline = false)
        sk.poly(24.6, 17.6 + b, 24.6, 11 + b, 30.2, 11 + b, 34.6, 17.6 + b, role = Role.GLASS, outline = false)
        sk.box(4.0, 23.5 + b, 44.0, 25 + b, 0.7, Role.NEAR, outline = false)
        sk.box(41.5, 19 + b, 44.6, 21.5 + b, 0.8, Role.LIGHT, outline = false)
        sk.box(3.4, 19 + b, 5.6, 21.5 + b, 0.8, Role.RED, outline = false)
        sk.wheel(13.0, 30.0, 5.3, th); sk.wheel(35.0, 30.0, 5.3, th)
        return sk
    }

    private fun carEnd(front: Boolean): Sketch {
        val sk = Sketch(36f, 36f)
        sk.box(5.0, 28.0, 10.0, 33.5, 1.5, Role.TIRE); sk.box(26.0, 28.0, 31.0, 33.5, 1.5, Role.TIRE)
        sk.box(4.0, 17.0, 32.0, 30.0, 4.5, Role.TORSO, shade = true)
        sk.poly(8.0, 17.5, 10.5, 8.5, 25.5, 8.5, 28.0, 17.5, role = Role.TORSO, shade = true)
        sk.poly(9.8, 16.8, 12.0, 10.2, 24.0, 10.2, 26.2, 16.8, role = Role.GLASS, outline = false)
        if (front) {
            sk.box(5.5, 20.0, 10.5, 23.0, 1.0, Role.LIGHT, false); sk.box(25.5, 20.0, 30.5, 23.0, 1.0, Role.LIGHT, false); sk.box(13.0, 22.5, 23.0, 26.0, 1.0, Role.DARK, false)
        } else {
            sk.box(5.5, 19.5, 10.5, 22.5, 1.0, Role.RED, false); sk.box(25.5, 19.5, 30.5, 22.5, 1.0, Role.RED, false); sk.box(14.0, 23.5, 22.0, 26.5, 0.6, Role.RIM, false)
        }
        return sk
    }

    // ------------------------------------------------------------------ train (64 × 40 side, 36 × 44 front/back)

    private fun trainSide(frame: Int): Sketch {
        val sk = Sketch(64f, 40f)
        val th = 2 * PI * (if (frame >= 0) frame.toDouble() / Kind.TRAIN.frames else 0.1) / 3
        val s = if (frame >= 0) 0.3 * sin(th * 9) else 0.0
        sk.box(9.0, 31.0, 25.0, 34.5, 1.2, Role.DARK); sk.box(39.0, 31.0, 55.0, 34.5, 1.2, Role.DARK)
        for (x in listOf(13.0, 21.0, 43.0, 51.0)) sk.wheel(x, 35.2, 2.8, th)
        sk.poly(3.0, 12 + s, 52.0, 12 + s, 61.0, 20 + s, 61.0, 31 + s, 3.0, 31 + s, role = Role.TORSO, shade = true)
        sk.box(3.0, 10.5 + s, 50.0, 13.5 + s, 1.5, Role.FAR, false)
        sk.cap(30.0, 10.5 + s, 35.0, 7.5 + s, 0.8, Role.DARK, false); sk.cap(35.0, 7.5 + s, 40.0, 10.5 + s, 0.8, Role.DARK, false)
        for (i in 0 until 5) { val x = 6 + i * 8.5; sk.box(x, 15 + s, x + 6, 21 + s, 1.0, if (i % 2 == 0) Role.LIGHT else Role.GLASS, false) }
        sk.poly(52.0, 15 + s, 57.5, 15 + s, 60.5, 20.5 + s, 52.0, 20.5 + s, role = Role.GLASS, outline = false)
        sk.box(3.0, 24 + s, 61.0, 26 + s, 0.6, Role.NEAR, false)
        sk.box(58.5, 27 + s, 60.8, 29.2 + s, 0.6, Role.LIGHT, false)
        return sk
    }

    private fun trainEnd(front: Boolean): Sketch {
        val sk = Sketch(36f, 44f)
        sk.box(7.0, 36.0, 29.0, 40.0, 1.2, Role.DARK)
        sk.box(4.0, 8.0, 32.0, 38.0, 6.0, Role.TORSO, shade = true)
        sk.box(7.0, 11.0, 29.0, 24.0, 3.0, Role.GLASS, false)
        sk.box(4.0, 28.0, 32.0, 30.0, 0.6, Role.NEAR, false)
        val lamp = if (front) Role.LIGHT else Role.RED
        sk.dot(9.5, 33.5, 1.7, lamp, false); sk.dot(26.5, 33.5, 1.7, lamp, false)
        return sk
    }

    // ------------------------------------------------------------------ cone

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
