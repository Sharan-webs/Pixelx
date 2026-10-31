package com.pixelx.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Numeric codes for every animation the engine understands. */
object Act {
    const val TRANSLATE = 1
    const val ROTATE = 2
    const val SCALE = 3
    const val ZOOM = 4
    const val FADE = 5
    const val BLUR = 6
    const val COLOR = 7
    const val WAVE = 8
    const val PARTICLES = 9
    const val SHAKE = 10
    const val DISTORTION = 11

    val byName = mapOf(
        "TRANSLATE" to TRANSLATE, "ROTATE" to ROTATE, "SCALE" to SCALE, "ZOOM" to ZOOM,
        "FADE" to FADE, "BLUR" to BLUR, "COLOR" to COLOR, "WAVE" to WAVE,
        "PARTICLES" to PARTICLES, "SHAKE" to SHAKE, "DISTORTION" to DISTORTION
    )
}

data class Region(val name: String, val x: Float, val y: Float, val w: Float, val h: Float, val feather: Float)

data class Anim(
    val target: String,
    val code: Int,
    val start: Float,
    val end: Float,
    val easing: String = "easeInOut",
    val from: Float = 0f,
    val to: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
    val fx: Float = 0.5f,
    val fy: Float = 0.5f,
    val degrees: Float = 0f,
    val amplitude: Float = 0.01f,
    val frequency: Float = 6f,
    val speed: Float = 1f,
    val mode: String = "",
    val color: Int = -1,
    val intensity: Float = 0.7f
)

data class Plan(
    val summary: String,
    val seconds: Int,
    val fps: Int,
    val regions: List<Region>,
    val anims: List<Anim>,
    val source: String
)

object Ease {
    val names = setOf(
        "linear", "easeIn", "easeOut", "easeInOut",
        "easeInCubic", "easeOutCubic", "easeInOutCubic", "bounce"
    )

    fun apply(name: String, t: Float): Float = when (name) {
        "linear" -> t
        "easeIn" -> t * t
        "easeOut" -> 1f - (1f - t) * (1f - t)
        "easeInOut" -> if (t < 0.5f) 2f * t * t else 1f - ((-2f * t + 2f) * (-2f * t + 2f)) / 2f
        "easeInCubic" -> t * t * t
        "easeOutCubic" -> 1f - (1f - t) * (1f - t) * (1f - t)
        "easeInOutCubic" -> if (t < 0.5f) 4f * t * t * t else 1f - ((-2f * t + 2f) * (-2f * t + 2f) * (-2f * t + 2f)) / 2f
        "bounce" -> bounceOut(t)
        else -> t * t * (3f - 2f * t)
    }

    private fun bounceOut(x: Float): Float {
        val n1 = 7.5625f
        val d1 = 2.75f
        return when {
            x < 1f / d1 -> n1 * x * x
            x < 2f / d1 -> { val t = x - 1.5f / d1; n1 * t * t + 0.75f }
            x < 2.5f / d1 -> { val t = x - 2.25f / d1; n1 * t * t + 0.9375f }
            else -> { val t = x - 2.625f / d1; n1 * t * t + 0.984375f }
        }
    }
}

class ParseResult(val plan: Plan?, val error: String?)

/** Validates everything the AI sends. Nothing from the AI is ever executed as code. */
object PlanParser {
    private val reserved = setOf("ALL", "BACKGROUND", "FOREGROUND", "TOP", "BOTTOM", "LEFT", "RIGHT", "CENTER")
    private val colorModes = setOf("GRAYSCALE", "SEPIA", "SATURATION", "BRIGHTNESS", "CONTRAST", "HUE", "TINT")
    private val waveAxes = setOf("VERTICAL", "HORIZONTAL", "RADIAL")
    private val particleModes = setOf("EXPLODE", "DISSOLVE", "ASSEMBLE", "FLOAT")
    private val distortModes = setOf("BULGE", "PINCH", "SWIRL", "GLITCH")

    fun extractJson(raw: String): JSONObject? {
        val a = raw.indexOf('{')
        val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(raw.substring(a, b + 1)) } catch (e: Exception) { null }
    }

    private fun f(o: JSONObject, key: String, def: Float, lo: Float, hi: Float): Float {
        val v = o.optDouble(key, def.toDouble())
        val x = if (v.isNaN() || v.isInfinite()) def else v.toFloat()
        return min(hi, max(lo, x))
    }

    fun parse(raw: String, seconds: Int, fps: Int): ParseResult {
        val root = extractJson(raw) ?: return ParseResult(null, "The AI answer was not valid JSON.")
        val d = seconds.toFloat()

        val regions = ArrayList<Region>()
        val ra = root.optJSONArray("regions") ?: JSONArray()
        for (i in 0 until min(ra.length(), 6)) {
            val o = ra.optJSONObject(i) ?: continue
            val name = o.optString("name", "").uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9_]"), "_").take(24)
            if (name.isEmpty() || name in reserved) continue
            val x = f(o, "x", 0.25f, 0f, 1f)
            val y = f(o, "y", 0.25f, 0f, 1f)
            val w = f(o, "w", 0.5f, 0.02f, 1f - x).coerceAtLeast(0.02f)
            val h = f(o, "h", 0.5f, 0.02f, 1f - y).coerceAtLeast(0.02f)
            regions.add(Region(name, x, y, w, h, f(o, "feather", 0.06f, 0f, 0.3f)))
        }
        val names = regions.map { it.name }.toSet()

        val anims = ArrayList<Anim>()
        val aa = root.optJSONArray("animations") ?: return ParseResult(null, "The plan has no animations.")
        for (i in 0 until min(aa.length(), 24)) {
            val o = aa.optJSONObject(i) ?: continue
            val code = Act.byName[o.optString("action", "").uppercase(Locale.ROOT)] ?: continue
            val target = o.optString("target", "ALL").uppercase(Locale.ROOT).trim()
            if (target != "ALL" && target !in reserved && target !in names) continue
            val start = f(o, "start", 0f, 0f, d)
            var end = f(o, "end", d, 0f, d)
            if (end <= start + 0.05f) end = min(d, start + max(0.5f, d / 4f))
            if (end <= start) continue
            val ease = o.optString("easing", "easeInOut").let { e -> Ease.names.firstOrNull { it.equals(e, true) } ?: "easeInOut" }

            val a = when (code) {
                Act.TRANSLATE -> Anim(target, code, start, end, ease, x = f(o, "x", 0f, -1f, 1f), y = f(o, "y", 0f, -1f, 1f))
                Act.ROTATE -> Anim(target, code, start, end, ease, degrees = f(o, "degrees", 15f, -720f, 720f))
                Act.SCALE -> Anim(target, code, start, end, ease, from = f(o, "from", 1f, 0.1f, 5f), to = f(o, "to", 1.2f, 0.1f, 5f))
                Act.ZOOM -> Anim(target, code, start, end, ease, from = f(o, "from", 1f, 0.5f, 4f), to = f(o, "to", 1.2f, 0.5f, 4f),
                    fx = f(o, "fx", 0.5f, 0f, 1f), fy = f(o, "fy", 0.5f, 0f, 1f))
                Act.FADE -> Anim(target, code, start, end, ease, from = f(o, "from", 1f, 0f, 1f), to = f(o, "to", 0f, 0f, 1f))
                Act.BLUR -> Anim(target, code, start, end, ease, from = f(o, "from", 0f, 0f, 1f), to = f(o, "to", 1f, 0f, 1f))
                Act.COLOR -> {
                    val mode = o.optString("mode", "").uppercase(Locale.ROOT)
                    if (mode !in colorModes) continue
                    val (lo, hi, dFrom, dTo) = when (mode) {
                        "GRAYSCALE", "SEPIA" -> Quad(0f, 1f, 0f, 1f)
                        "SATURATION" -> Quad(0f, 3f, 1f, 1.4f)
                        "BRIGHTNESS" -> Quad(-0.5f, 0.5f, 0f, 0.15f)
                        "CONTRAST" -> Quad(0.3f, 2f, 1f, 1.2f)
                        "HUE" -> Quad(-180f, 180f, 0f, 60f)
                        else -> Quad(0f, 1f, 0f, 0.5f)
                    }
                    Anim(target, code, start, end, ease, from = f(o, "from", dFrom, lo, hi), to = f(o, "to", dTo, lo, hi),
                        mode = mode, color = parseColor(o.optString("color", "#FFA040")))
                }
                Act.WAVE -> {
                    val axis = o.optString("axis", "HORIZONTAL").uppercase(Locale.ROOT)
                    Anim(target, code, start, end, ease, mode = if (axis in waveAxes) axis else "HORIZONTAL",
                        amplitude = f(o, "amplitude", 0.01f, 0f, 0.1f), frequency = f(o, "frequency", 6f, 0.2f, 40f), speed = f(o, "speed", 1f, 0f, 6f))
                }
                Act.PARTICLES -> {
                    val mode = o.optString("mode", "EXPLODE").uppercase(Locale.ROOT)
                    Anim(target, code, start, end, ease, mode = if (mode in particleModes) mode else "EXPLODE", intensity = f(o, "intensity", 0.7f, 0f, 1f))
                }
                Act.SHAKE -> Anim(target, code, start, end, ease, intensity = f(o, "intensity", 0.5f, 0f, 1f), frequency = f(o, "frequency", 10f, 0.5f, 40f))
                else -> {
                    val mode = o.optString("mode", "SWIRL").uppercase(Locale.ROOT)
                    Anim(target, code, start, end, ease, mode = if (mode in distortModes) mode else "SWIRL",
                        from = f(o, "from", 0f, 0f, 1f), to = f(o, "to", 0.6f, 0f, 1f))
                }
            }
            anims.add(a)
        }
        if (anims.isEmpty()) return ParseResult(null, "None of the animations were valid for this engine.")
        val summary = root.optString("summary", "").trim().take(220).ifEmpty { "Custom animation" }
        return ParseResult(Plan(summary, seconds, fps, regions, anims, "AI"), null)
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

    private fun parseColor(s: String): Int = try {
        android.graphics.Color.parseColor(if (s.startsWith("#")) s else "#$s")
    } catch (e: Exception) { android.graphics.Color.rgb(255, 160, 64) }
}

/** Our own keyword understanding. Used (1) to enrich the brief sent to the AI, (2) as an offline fallback planner. */
object LocalPlanner {
    fun scan(text: String): List<String> {
        val s = text.lowercase(Locale.ROOT)
        fun has(vararg k: String) = k.any { s.contains(it) }
        val out = ArrayList<String>()
        if (has("zoom out", "pull back", "pull out", "wider")) out.add("ZOOM_OUT")
        else if (has("zoom", "closer", "push in", "push-in", "approach", "focus in")) out.add("ZOOM_IN")
        if (has("cinematic", "camera", "film", "movie")) out.add("CINEMATIC")
        if (has("rotate", "rotation", "spin", "turn", "twirl")) out.add("ROTATE")
        if (has("water", "wave", "ripple", "liquid", "flag", "heat", "wind", "flow")) out.add("WAVE")
        if (has("explo", "burst", "shatter", "blast", "bomb")) out.add("EXPLODE")
        if (has("dissolve", "disintegrate", "vanish", "turn to dust", "ash")) out.add("DISSOLVE")
        if (has("assemble", "materialize", "materialise", "appear", "form from")) out.add("ASSEMBLE")
        if (has("particle", "dust", "float", "snow", "sparkle", "ember", "firefl", "bubble")) out.add("FLOAT")
        if (has("shake", "earthquake", "vibrat", "tremble", "rumble")) out.add("SHAKE")
        if (has("blur", "dream", "fog", "defocus")) out.add("BLUR")
        if (has("fade", "darken", "dark", "night")) out.add("FADE_DARK")
        if (has("black and white", "grayscale", "greyscale", "monochrome", "noir")) out.add("GRAYSCALE")
        if (has("sepia", "vintage", "old photo", "retro")) out.add("SEPIA")
        if (has("warm", "golden", "sunset")) out.add("WARM")
        if (has("cold", "cool", "icy", "blue tone")) out.add("COLD")
        if (has("swirl", "twist", "vortex")) out.add("SWIRL")
        if (has("bulge", "inflate", "balloon")) out.add("BULGE")
        if (has("glitch", "digital", "hologram")) out.add("GLITCH")
        val move = has("move", "walk", "run", "slide", "pan", "drift", "travel", "toward", "go ", "fly", "glide")
        if (move) {
            if (has("right")) out.add("MOVE_RIGHT")
            if (has("left")) out.add("MOVE_LEFT")
            if (has(" up", "rise", "upward", "fly")) out.add("MOVE_UP")
            if (has("down", "sink", "fall", "descend")) out.add("MOVE_DOWN")
        }
        return out
    }

    fun build(prompt: String, seconds: Int, fps: Int): Plan {
        val d = seconds.toFloat()
        val intents = scan(prompt)
        val regions = listOf(Region("SUBJECT", 0.2f, 0.12f, 0.6f, 0.78f, 0.1f))
        val a = ArrayList<Anim>()
        fun add(x: Anim) { a.add(x) }
        var main = false
        for (i in intents) {
            when (i) {
                "ZOOM_IN" -> { add(Anim("ALL", Act.ZOOM, 0f, d, from = 1f, to = 1.3f)); main = true }
                "ZOOM_OUT" -> { add(Anim("ALL", Act.ZOOM, 0f, d, from = 1.3f, to = 1f)); main = true }
                "ROTATE" -> { add(Anim("ALL", Act.ROTATE, 0f, d, degrees = 360f)); main = true }
                "WAVE" -> { add(Anim("ALL", Act.WAVE, 0f, d, mode = "HORIZONTAL", amplitude = 0.012f, frequency = 6f, speed = 0.8f)); main = true }
                "EXPLODE" -> {
                    add(Anim("ALL", Act.SHAKE, d * 0.35f, d * 0.6f, intensity = 0.6f, frequency = 14f))
                    add(Anim("ALL", Act.PARTICLES, d * 0.6f, d, easing = "easeIn", mode = "EXPLODE", intensity = 0.8f)); main = true
                }
                "DISSOLVE" -> { add(Anim("ALL", Act.PARTICLES, d * 0.2f, d, mode = "DISSOLVE", intensity = 0.7f)); main = true }
                "ASSEMBLE" -> { add(Anim("ALL", Act.PARTICLES, 0f, d * 0.7f, easing = "easeOut", mode = "ASSEMBLE", intensity = 0.8f)); main = true }
                "FLOAT" -> { add(Anim("ALL", Act.PARTICLES, 0f, d, mode = "FLOAT", intensity = 0.7f)); main = true }
                "SHAKE" -> { add(Anim("ALL", Act.SHAKE, 0f, d, intensity = 0.6f, frequency = 12f)); main = true }
                "BLUR" -> { add(Anim("ALL", Act.BLUR, 0f, d, from = 0f, to = 0.7f)); main = true }
                "FADE_DARK" -> { add(Anim("ALL", Act.FADE, 0f, d, from = 1f, to = 0.25f)); main = true }
                "GRAYSCALE" -> { add(Anim("ALL", Act.COLOR, 0f, d, mode = "GRAYSCALE", from = 0f, to = 1f)); main = true }
                "SEPIA" -> { add(Anim("ALL", Act.COLOR, 0f, d, mode = "SEPIA", from = 0f, to = 1f)); main = true }
                "WARM" -> { add(Anim("ALL", Act.COLOR, 0f, d, mode = "TINT", from = 0f, to = 0.6f, color = android.graphics.Color.rgb(255, 160, 60))); main = true }
                "COLD" -> { add(Anim("ALL", Act.COLOR, 0f, d, mode = "TINT", from = 0f, to = 0.6f, color = android.graphics.Color.rgb(80, 150, 255))); main = true }
                "SWIRL" -> { add(Anim("ALL", Act.DISTORTION, 0f, d, mode = "SWIRL", from = 0f, to = 0.7f)); main = true }
                "BULGE" -> { add(Anim("ALL", Act.DISTORTION, 0f, d, mode = "BULGE", from = 0f, to = 0.8f)); main = true }
                "GLITCH" -> { add(Anim("ALL", Act.DISTORTION, 0f, d, mode = "GLITCH", from = 0.2f, to = 0.8f)); main = true }
                "MOVE_RIGHT" -> { add(Anim("SUBJECT", Act.TRANSLATE, 0f, d, x = 0.22f)); main = true }
                "MOVE_LEFT" -> { add(Anim("SUBJECT", Act.TRANSLATE, 0f, d, x = -0.22f)); main = true }
                "MOVE_UP" -> { add(Anim("SUBJECT", Act.TRANSLATE, 0f, d, y = -0.2f)); main = true }
                "MOVE_DOWN" -> { add(Anim("SUBJECT", Act.TRANSLATE, 0f, d, y = 0.2f)); main = true }
                "CINEMATIC" -> {
                    add(Anim("ALL", Act.ZOOM, 0f, d, from = 1f, to = 1.18f))
                    add(Anim("ALL", Act.TRANSLATE, 0f, d, x = 0.03f))
                    add(Anim("ALL", Act.COLOR, 0f, d, mode = "CONTRAST", from = 1f, to = 1.15f)); main = true
                }
            }
        }
        if (!main) {
            add(Anim("ALL", Act.ZOOM, 0f, d, from = 1f, to = 1.2f))
            add(Anim("ALL", Act.TRANSLATE, 0f, d, x = 0.025f))
        }
        val used = a.any { it.target == "SUBJECT" }
        return Plan("Built-in planner: " + (if (intents.isEmpty()) "gentle cinematic push-in" else intents.joinToString(", ").lowercase(Locale.ROOT)),
            seconds, fps, if (used) regions else emptyList(), a, "LOCAL")
    }
}
