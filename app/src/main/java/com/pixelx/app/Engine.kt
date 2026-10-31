package com.pixelx.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAU = 6.2831855f
private const val DEG = 0.017453292f
private const val RAD = 57.29578f

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun smooth(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Pixel Division Engine.
 * The image is cut into numbered rectangular blocks. Every block keeps a reference to its own
 * original pixels inside [bmp] (never replaced by an average colour for drawing).
 */
class BlockGrid(val bmp: Bitmap, requested: Int) {
    val w = bmp.width
    val h = bmp.height
    val bs: Int = pickSize(w, h, requested)
    val cols = (w + bs - 1) / bs
    val rows = (h + bs - 1) / bs
    val count = cols * rows

    val sx = IntArray(count)
    val sy = IntArray(count)
    val sw = IntArray(count)
    val sh = IntArray(count)
    val cx = FloatArray(count)
    val cy = FloatArray(count)
    val u = FloatArray(count)
    val v = FloatArray(count)
    val r1 = FloatArray(count)
    val r2 = FloatArray(count)
    /** Average colour: used ONLY to colour floating particles, never to draw the image itself. */
    val avg = IntArray(count)

    init {
        val rnd = java.util.Random(7L)
        val px = IntArray(bs * bs)
        for (j in 0 until rows) {
            for (i in 0 until cols) {
                val k = j * cols + i
                val x0 = i * bs
                val y0 = j * bs
                val bw = min(bs, w - x0)
                val bh = min(bs, h - y0)
                sx[k] = x0; sy[k] = y0; sw[k] = bw; sh[k] = bh
                cx[k] = x0 + bw / 2f
                cy[k] = y0 + bh / 2f
                u[k] = cx[k] / w
                v[k] = cy[k] / h
                r1[k] = rnd.nextFloat()
                r2[k] = rnd.nextFloat()
                bmp.getPixels(px, 0, bw, x0, y0, bw, bh)
                var r = 0L; var g = 0L; var b = 0L
                val n = bw * bh
                for (q in 0 until n) {
                    val c = px[q]
                    r += (c shr 16) and 255
                    g += (c shr 8) and 255
                    b += c and 255
                }
                avg[k] = Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
            }
        }
    }

    /** Block id as shown to the user (1-based). */
    fun id(i: Int) = i + 1

    /** The original individual pixels of one block. */
    fun blockPixels(i: Int): IntArray {
        val out = IntArray(sw[i] * sh[i])
        bmp.getPixels(out, 0, sw[i], sx[i], sy[i], sw[i], sh[i])
        return out
    }

    companion object {
        const val MAX_BLOCKS = 36000
        private fun pickSize(w: Int, h: Int, requested: Int): Int {
            var s = requested.coerceIn(2, 32)
            while (ceil(w / s.toFloat()) * ceil(h / s.toFloat()) > MAX_BLOCKS) s += 2
            return s
        }
    }
}

/** Soft 0..1 weight per block for a named target (ALL = null = weight 1 everywhere). */
class Masks(private val g: BlockGrid, private val regions: List<Region>) {
    private val cache = HashMap<String, FloatArray>()

    fun forTarget(t: String): FloatArray? {
        if (t == "ALL") return null
        return cache.getOrPut(t) { build(t) }
    }

    fun pivot(t: String): FloatArray {
        val r = regions.firstOrNull { it.name == t }
        return if (r != null) floatArrayOf((r.x + r.w / 2f) * g.w, (r.y + r.h / 2f) * g.h)
        else floatArrayOf(g.w / 2f, g.h / 2f)
    }

    fun radius(t: String): Float {
        val r = regions.firstOrNull { it.name == t }
        return if (r != null) max(0.5f * max(r.w * g.w, r.h * g.h), 24f) else 0.55f * max(g.w, g.h)
    }

    private fun regionWeight(r: Region, u: Float, v: Float): Float {
        val dx = max(0f, abs(u - (r.x + r.w / 2f)) - r.w / 2f)
        val dy = max(0f, abs(v - (r.y + r.h / 2f)) - r.h / 2f)
        val d = sqrt(dx * dx + dy * dy)
        return if (r.feather <= 0.0001f) (if (d <= 0f) 1f else 0f) else 1f - smooth(0f, r.feather, d)
    }

    private fun subject(i: Int): Float {
        if (regions.isEmpty()) {
            val dd = sqrt(((g.u[i] - 0.5f) / 0.32f) * ((g.u[i] - 0.5f) / 0.32f) + ((g.v[i] - 0.5f) / 0.42f) * ((g.v[i] - 0.5f) / 0.42f))
            return 1f - smooth(0.85f, 1.25f, dd)
        }
        var m = 0f
        for (r in regions) m = max(m, regionWeight(r, g.u[i], g.v[i]))
        return m
    }

    private fun build(t: String): FloatArray {
        val m = FloatArray(g.count)
        val reg = regions.firstOrNull { it.name == t }
        for (i in 0 until g.count) {
            val u = g.u[i]
            val v = g.v[i]
            m[i] = when {
                reg != null -> regionWeight(reg, u, v)
                t == "BACKGROUND" -> 1f - subject(i)
                t == "FOREGROUND" -> subject(i)
                t == "TOP" -> 1f - smooth(0.25f, 0.5f, v)
                t == "BOTTOM" -> smooth(0.5f, 0.75f, v)
                t == "LEFT" -> 1f - smooth(0.25f, 0.5f, u)
                t == "RIGHT" -> smooth(0.5f, 0.75f, u)
                t == "CENTER" -> subject(i).let { if (regions.isEmpty()) it else 1f - smooth(0.85f, 1.25f, sqrt((u - 0.5f) * (u - 0.5f) / 0.1f + (v - 0.5f) * (v - 0.5f) / 0.18f)) }
                else -> 1f
            }
        }
        return m
    }
}

fun buildColorMatrix(mode: String, amt: Float, color: Int): ColorMatrix {
    val cm = ColorMatrix()
    when (mode) {
        "GRAYSCALE" -> cm.setSaturation(1f - amt.coerceIn(0f, 1f))
        "SATURATION" -> cm.setSaturation(amt)
        "SEPIA" -> {
            val a = amt.coerceIn(0f, 1f)
            val s = floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
            val id = floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
            val out = FloatArray(20) { id[it] * (1f - a) + s[it] * a }
            cm.set(out)
        }
        "BRIGHTNESS" -> {
            val b = amt * 255f
            cm.set(floatArrayOf(1f, 0f, 0f, 0f, b, 0f, 1f, 0f, 0f, b, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f))
        }
        "CONTRAST" -> {
            val c = amt
            val o = 128f * (1f - c)
            cm.set(floatArrayOf(c, 0f, 0f, 0f, o, 0f, c, 0f, 0f, o, 0f, 0f, c, 0f, o, 0f, 0f, 0f, 1f, 0f))
        }
        "HUE" -> {
            val r = amt * DEG
            val c = cos(r)
            val s = sin(r)
            cm.set(floatArrayOf(
                0.213f + 0.787f * c - 0.213f * s, 0.715f - 0.715f * c - 0.715f * s, 0.072f - 0.072f * c + 0.928f * s, 0f, 0f,
                0.213f - 0.213f * c + 0.143f * s, 0.715f + 0.285f * c + 0.140f * s, 0.072f - 0.072f * c - 0.283f * s, 0f, 0f,
                0.213f - 0.213f * c - 0.787f * s, 0.715f - 0.715f * c + 0.715f * s, 0.072f + 0.928f * c + 0.072f * s, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        "TINT" -> {
            val a = amt.coerceIn(0f, 1f)
            val rr = 1f + a * (Color.red(color) / 255f - 0.5f) * 1.2f
            val gg = 1f + a * (Color.green(color) / 255f - 0.5f) * 1.2f
            val bb = 1f + a * (Color.blue(color) / 255f - 0.5f) * 1.2f
            cm.set(floatArrayOf(rr, 0f, 0f, 0f, 0f, 0f, gg, 0f, 0f, 0f, 0f, 0f, bb, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        }
    }
    return cm
}

/** Draws one frame of the plan at time t (seconds). Pure programmatic animation: no AI, no prerecorded video. */
class FrameRenderer(private val g: BlockGrid, private val plan: Plan, private val wm: Bitmap?) {
    private val masks = Masks(g, plan.regions)
    private val W = g.w.toFloat()
    private val H = g.h.toFloat()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wmPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) }

    private class Active(
        val a: Anim, val e: Float, val env: Float, val mask: FloatArray?,
        val pvx: Float, val pvy: Float, val rad: Float, val slot: Int, val ox: Float, val oy: Float
    )

    private class Overlay(val a: Anim, val env: Float, val idx: IntArray)

    private val needBlur = plan.anims.any { it.code == Act.BLUR }
    private val usePlate = plan.anims.none { it.code == Act.PARTICLES && it.mode != "FLOAT" && it.target == "ALL" } &&
        plan.anims.any { it.target != "ALL" && it.code in intArrayOf(Act.TRANSLATE, Act.ROTATE, Act.SCALE, Act.ZOOM, Act.SHAKE, Act.WAVE, Act.DISTORTION) }

    /** levels[0] = original, levels[1..4] = progressively blurred copies. */
    private val levels: Array<Bitmap>? = if (needBlur || usePlate) buildLevels() else null

    private val floatIdx = HashMap<Int, IntArray>()

    // per-block scratch (allocated once)
    private val pxA = FloatArray(g.count)
    private val pyA = FloatArray(g.count)
    private val rotA = FloatArray(g.count)
    private val scA = FloatArray(g.count)
    private val alA = FloatArray(g.count)
    private val cIdxA = IntArray(g.count)
    private val lvA = IntArray(g.count)
    private val movedList = IntArray(g.count)
    private val src = Rect()
    private val dst = RectF()

    init {
        plan.anims.forEachIndexed { k, a ->
            if (a.code == Act.PARTICLES && a.mode == "FLOAT") {
                val n = (60 + 340 * a.intensity).toInt()
                val mk = masks.forTarget(a.target)
                val rnd = java.util.Random(100L + k)
                val list = ArrayList<Int>()
                var tries = 0
                while (list.size < n && tries < n * 30) {
                    tries++
                    val b = rnd.nextInt(g.count)
                    if (mk == null || mk[b] > 0.3f) list.add(b)
                }
                floatIdx[k] = list.toIntArray()
            }
        }
    }

    private fun buildLevels(): Array<Bitmap> {
        val out = ArrayList<Bitmap>()
        out.add(g.bmp)
        var cur = g.bmp
        for (k in 1..4) {
            cur = Bitmap.createScaledBitmap(cur, max(1, cur.width / 2), max(1, cur.height / 2), true)
            out.add(Bitmap.createScaledBitmap(cur, g.w, g.h, true))
        }
        return out.toTypedArray()
    }

    private fun prog(a: Anim, t: Float): Float {
        val d = a.end - a.start
        val p = if (d <= 1e-4f) (if (t >= a.end) 1f else 0f) else ((t - a.start) / d).coerceIn(0f, 1f)
        return Ease.apply(a.easing, p)
    }

    private fun envelope(a: Anim, t: Float): Float {
        if (t < a.start || t > a.end) return 0f
        val ramp = max(0.05f, min(0.6f, (a.end - a.start) / 4f))
        return min(1f, min((t - a.start) / ramp, (a.end - t) / ramp)).coerceIn(0f, 1f)
    }

    fun render(canvas: Canvas, t: Float) {
        canvas.drawColor(Color.BLACK)
        val m = Matrix()
        var galpha = 1f
        var gcm: ColorMatrix? = null
        var gblur = 0f
        val acts = ArrayList<Active>()
        val overlays = ArrayList<Overlay>()
        val regionColors = ArrayList<ColorMatrix>()

        for ((k, a) in plan.anims.withIndex()) {
            val global = a.target == "ALL"
            val e = prog(a, t)
            val env = envelope(a, t)
            val mk = masks.forTarget(a.target)
            val pv = masks.pivot(a.target)
            val rad = masks.radius(a.target)
            when (a.code) {
                Act.TRANSLATE -> if (global) m.postTranslate(a.x * W * e, a.y * H * e) else acts.add(Active(a, e, env, mk, pv[0], pv[1], rad, 0, 0f, 0f))
                Act.ROTATE -> if (global) m.postRotate(a.degrees * e, W / 2f, H / 2f) else acts.add(Active(a, e, env, mk, pv[0], pv[1], rad, 0, 0f, 0f))
                Act.SCALE -> if (global) { val s = lerp(a.from, a.to, e); m.postScale(s, s, W / 2f, H / 2f) } else acts.add(Active(a, e, env, mk, pv[0], pv[1], rad, 0, 0f, 0f))
                Act.ZOOM -> if (global) { val s = lerp(a.from, a.to, e); m.postScale(s, s, a.fx * W, a.fy * H) } else acts.add(Active(a, e, env, mk, pv[0], pv[1], rad, 0, 0f, 0f))
                Act.SHAKE -> if (env > 0f) {
                    val tt = t - a.start
                    val amp = a.intensity * 0.02f * min(W, H) * env
                    val ox = (sin(tt * a.frequency * TAU + 1.3f) + 0.5f * sin(tt * a.frequency * 2.7f * TAU + 0.4f)) * amp
                    val oy = (cos(tt * a.frequency * 1.3f * TAU + 0.7f) + 0.5f * sin(tt * a.frequency * 3.1f * TAU + 2.1f)) * amp
                    if (global) m.postTranslate(ox, oy) else acts.add(Active(a, e, env, mk, pv[0], pv[1], rad, 0, ox, oy))
                }
                Act.FADE -> if (global) galpha *= lerp(a.from, a.to, e) else acts.add(Active(a, e, env, mk, pv[0], pv[1], rad, 0, 0f, 0f))
                Act.BLUR -> if (global) gblur = max(gblur, lerp(a.from, a.to, e)) else acts.add(Active(a, e, env, mk, pv[0], pv[1], rad, 0, 0f, 0f))
                Act.COLOR -> {
                    val cm = buildColorMatrix(a.mode, lerp(a.from, a.to, e), a.color)
                    if (global) {
                        if (gcm == null) gcm = cm else gcm.postConcat(cm)
                    } else {
                        regionColors.add(cm)
                        acts.add(Active(a, e, env, mk, pv[0], pv[1], rad, regionColors.size, 0f, 0f))
                    }
                }
                Act.WAVE -> if (env > 0f) acts.add(Active(a, e, env, mk, pv[0], pv[1], rad, 0, 0f, 0f))
                Act.DISTORTION -> acts.add(Active(a, e, env, mk, pv[0], pv[1], rad, 0, 0f, 0f))
                Act.PARTICLES -> {
                    if (a.mode == "FLOAT") {
                        if (env > 0f) overlays.add(Overlay(a, env, floatIdx[k] ?: IntArray(0)))
                    } else acts.add(Active(a, e, env, mk, pv[0], pv[1], rad, 0, 0f, 0f))
                }
            }
        }

        canvas.save()
        canvas.concat(m)
        if (acts.isEmpty()) drawWhole(canvas, gcm, galpha, gblur) else drawBlocks(canvas, acts, regionColors, gcm, galpha, gblur, t)
        drawOverlays(canvas, overlays, t)
        canvas.restore()
        if (wm != null) canvas.drawBitmap(wm, 0f, 0f, wmPaint)
    }

    private fun drawWhole(canvas: Canvas, gcm: ColorMatrix?, galpha: Float, gblur: Float) {
        paint.colorFilter = if (gcm != null) ColorMatrixColorFilter(gcm) else null
        paint.alpha = (galpha * 255f).toInt().coerceIn(0, 255)
        val lv = levels
        if (lv == null || gblur < 0.01f) {
            canvas.drawBitmap(g.bmp, 0f, 0f, paint)
        } else {
            val f = gblur.coerceIn(0f, 1f) * 4f
            val lo = floor(f).toInt().coerceIn(0, 4)
            val hi = min(4, lo + 1)
            val fr = f - lo
            canvas.drawBitmap(lv[lo], 0f, 0f, paint)
            if (fr > 0.02f && hi != lo) {
                paint.alpha = (galpha * fr * 255f).toInt().coerceIn(0, 255)
                canvas.drawBitmap(lv[hi], 0f, 0f, paint)
            }
        }
        paint.colorFilter = null
    }

    private fun drawBlocks(
        canvas: Canvas, acts: List<Active>, regionColors: List<ColorMatrix>,
        gcm: ColorMatrix?, galpha: Float, gblur: Float, t: Float
    ) {
        val n = g.count
        val na = acts.size
        val lv = levels

        val paints = Array(regionColors.size + 1) { k ->
            Paint(Paint.FILTER_BITMAP_FLAG).also { p ->
                if (k == 0) {
                    p.colorFilter = if (gcm != null) ColorMatrixColorFilter(gcm) else null
                } else {
                    val cm = if (gcm != null) ColorMatrix(gcm) else ColorMatrix()
                    cm.postConcat(regionColors[k - 1])
                    p.colorFilter = ColorMatrixColorFilter(cm)
                }
            }
        }

        if (usePlate && lv != null) {
            paints[0].alpha = (galpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(lv[4], 0f, 0f, paints[0])
        }

        var movedCount = 0
        for (i in 0 until n) {
            var px = g.cx[i]
            var py = g.cy[i]
            var rot = 0f
            var sc = 1f
            var al = 1f
            var blur = gblur
            var cIdx = 0
            var moved = false
            for (k in 0 until na) {
                val ac = acts[k]
                val mk = ac.mask
                val w = if (mk == null) 1f else mk[i]
                if (w < 0.004f) continue
                val a = ac.a
                when (a.code) {
                    Act.TRANSLATE -> { px += a.x * W * ac.e * w; py += a.y * H * ac.e * w; moved = true }
                    Act.ROTATE -> {
                        val ang = a.degrees * ac.e * w
                        val c = cos(ang * DEG); val s = sin(ang * DEG)
                        val dx = px - ac.pvx; val dy = py - ac.pvy
                        px = ac.pvx + dx * c - dy * s
                        py = ac.pvy + dx * s + dy * c
                        rot += ang; moved = true
                    }
                    Act.SCALE, Act.ZOOM -> {
                        val s = 1f + (lerp(a.from, a.to, ac.e) - 1f) * w
                        px = ac.pvx + (px - ac.pvx) * s
                        py = ac.pvy + (py - ac.pvy) * s
                        sc *= s; moved = true
                    }
                    Act.FADE -> al *= 1f + (lerp(a.from, a.to, ac.e) - 1f) * w
                    Act.BLUR -> blur = max(blur, lerp(a.from, a.to, ac.e) * w)
                    Act.COLOR -> if (w > 0.5f && cIdx == 0) cIdx = ac.slot
                    Act.SHAKE -> { px += ac.ox * w; py += ac.oy * w; moved = true }
                    Act.WAVE -> {
                        val amp = a.amplitude * min(W, H) * ac.env * w
                        val tt = t - a.start
                        when (a.mode) {
                            "VERTICAL" -> py += amp * sin(TAU * (a.frequency * g.u[i] - a.speed * tt))
                            "HORIZONTAL" -> px += amp * sin(TAU * (a.frequency * g.v[i] - a.speed * tt))
                            else -> {
                                val dx = g.cx[i] - W / 2f; val dy = g.cy[i] - H / 2f
                                val r = sqrt(dx * dx + dy * dy)
                                val nx = if (r > 0.001f) dx / r else 0f
                                val ny = if (r > 0.001f) dy / r else 0f
                                val d = amp * sin(TAU * (a.frequency * r / min(W, H) - a.speed * tt))
                                px += nx * d; py += ny * d
                            }
                        }
                        moved = true
                    }
                    Act.DISTORTION -> {
                        val s = lerp(a.from, a.to, ac.e) * w
                        val rx = px - ac.pvx; val ry = py - ac.pvy
                        val r = sqrt(rx * rx + ry * ry) / ac.rad
                        val fall = 1f - min(r, 1f)
                        val f2 = fall * fall
                        when (a.mode) {
                            "BULGE" -> { val f = 1f + s * 0.7f * f2; px = ac.pvx + rx * f; py = ac.pvy + ry * f; sc *= f }
                            "PINCH" -> { val f = 1f - s * 0.5f * f2; px = ac.pvx + rx * f; py = ac.pvy + ry * f; sc *= f }
                            "GLITCH" -> {
                                val band = floor(g.v[i] * 28f + floor(t * 10f) * 5.37f)
                                val x = sin(band * 12.9898f) * 43758.547f
                                val hsh = x - floor(x)
                                if (hsh > 0.72f) px += (hsh - 0.72f) / 0.28f * s * 0.18f * W * (if ((band.toInt() and 1) == 0) 1f else -1f)
                            }
                            else -> {
                                val ang = s * 3.2f * f2
                                val c = cos(ang); val sn = sin(ang)
                                px = ac.pvx + rx * c - ry * sn
                                py = ac.pvy + rx * sn + ry * c
                                rot += ang * RAD
                            }
                        }
                        moved = true
                    }
                    Act.PARTICLES -> {
                        val assemble = a.mode == "ASSEMBLE"
                        val dissolve = a.mode == "DISSOLVE"
                        val pe = if (assemble) 1f - ac.e else ac.e
                        val delay = if (dissolve) 0.65f * (g.u[i] * 0.7f + g.r1[i] * 0.3f) else 0.18f * g.r1[i]
                        val span = if (dissolve) 0.35f else 0.82f
                        val q = ((pe - delay) / span).coerceIn(0f, 1f) * w
                        if (q > 0f) {
                            val md = max(W, H)
                            val ang = if (dissolve) -0.6f + (g.r2[i] - 0.5f) * 1.6f
                            else atan2(g.cy[i] - ac.pvy, g.cx[i] - ac.pvx) + (g.r2[i] - 0.5f) * 1.4f
                            val dist = if (dissolve) (0.08f + 0.25f * g.r1[i]) * md * a.intensity * q
                            else (0.25f + 0.9f * g.r1[i]) * md * a.intensity * q
                            px += cos(ang) * dist
                            py += sin(ang) * dist + (if (dissolve) 0f else 0.5f * q * q * H * 0.35f * a.intensity)
                            rot += (g.r2[i] - 0.5f) * 540f * q
                            sc *= 1f - 0.6f * q
                            al *= 1f - q
                            moved = true
                        }
                    }
                }
            }
            if (al <= 0.01f) continue
            val lvl = if (lv == null) 0 else (blur.coerceIn(0f, 1f) * 4f + 0.5f).toInt().coerceIn(0, 4)
            if (moved) {
                pxA[i] = px; pyA[i] = py; rotA[i] = rot; scA[i] = sc; alA[i] = al; cIdxA[i] = cIdx; lvA[i] = lvl
                movedList[movedCount++] = i
            } else {
                drawOne(canvas, paints, lv, i, px, py, rot, sc, al, galpha, cIdx, lvl)
            }
        }
        // moved blocks go on top so a moving subject is never hidden by the static background
        for (q in 0 until movedCount) {
            val i = movedList[q]
            drawOne(canvas, paints, lv, i, pxA[i], pyA[i], rotA[i], scA[i], alA[i], galpha, cIdxA[i], lvA[i])
        }
    }

    private fun drawOne(
        canvas: Canvas, paints: Array<Paint>, lv: Array<Bitmap>?, i: Int,
        px: Float, py: Float, rot: Float, sc: Float, al: Float, galpha: Float, cIdx: Int, lvl: Int
    ) {
        val p = paints[cIdx]
        p.alpha = (al * galpha * 255f).toInt().coerceIn(0, 255)
        val bmp = if (lv == null) g.bmp else lv[lvl]
        src.set(g.sx[i], g.sy[i], g.sx[i] + g.sw[i], g.sy[i] + g.sh[i])
        val hw = g.sw[i] * 0.5f * sc + 0.4f
        val hh = g.sh[i] * 0.5f * sc + 0.4f
        dst.set(px - hw, py - hh, px + hw, py + hh)
        if (abs(rot) > 0.05f) {
            canvas.save()
            canvas.rotate(rot, px, py)
            canvas.drawBitmap(bmp, src, dst, p)
            canvas.restore()
        } else {
            canvas.drawBitmap(bmp, src, dst, p)
        }
    }

    private fun drawOverlays(canvas: Canvas, overlays: List<Overlay>, t: Float) {
        for (o in overlays) {
            val tt = t - o.a.start
            for (j in o.idx.indices) {
                val b = o.idx[j]
                val r1 = g.r1[b]
                val r2 = g.r2[b]
                val x = g.cx[b] + sin(tt * (0.6f + r1) + r2 * TAU) * 0.015f * W
                var y = g.cy[b] - tt * (0.02f + 0.05f * r1) * H
                y = ((y % H) + H) % H
                val rad = (1.2f + 2.8f * r2) * (W / 720f)
                val a = (o.env * (0.35f + 0.4f * sin(tt * 2f + r1 * TAU) * 0.5f + 0.2f) * 255f).toInt().coerceIn(0, 255)
                dotPaint.color = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(g.avg[b], Color.WHITE, 0.55f), a)
                canvas.drawCircle(x, y, rad, dotPaint)
            }
        }
    }
}
