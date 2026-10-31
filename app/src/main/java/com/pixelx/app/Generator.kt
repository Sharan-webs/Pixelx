package com.pixelx.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object ImageIO {
    const val MAX_LONG_SIDE = 1280

    fun bounds(path: String): IntArray {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        return intArrayOf(o.outWidth, o.outHeight)
    }

    /** Decodes (PNG / JPG / WEBP), downsamples, and applies EXIF rotation. */
    fun load(path: String, maxSide: Int): Bitmap {
        val b = bounds(path)
        if (b[0] <= 0 || b[1] <= 0) throw IllegalArgumentException("This file is not a supported image (use PNG, JPG or WEBP).")
        var sample = 1
        while (max(b[0], b[1]) / (sample * 2) >= maxSide) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val raw = BitmapFactory.decodeFile(path, o) ?: throw IllegalArgumentException("Could not read this image.")
        val orient = try {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val m = Matrix()
        when (orient) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(-90f)
            else -> return raw
        }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    /** Output size: aspect ratio kept, long side capped, both sides multiples of 16 (encoder friendly). */
    fun outputSize(w: Int, h: Int): IntArray {
        val s = minOf(1f, MAX_LONG_SIDE.toFloat() / max(w, h))
        val ow = (((w * s).toInt() + 8) / 16 * 16).coerceAtLeast(64)
        val oh = (((h * s).toInt() + 8) / 16 * 16).coerceAtLeast(64)
        return intArrayOf(ow, oh)
    }
}

object Generator {
    const val FPS = 30
    const val BLOCK = 8

    fun estimateSeconds(videoSeconds: Int): Int = 14 + (videoSeconds * FPS * 0.05).toInt()

    suspend fun run(
        ctx: Context, spec: JobSpec,
        report: (stage: String, progress: Float, etaSec: Int, detail: String) -> Unit
    ): Project {
        val frames = spec.seconds * FPS
        val est = estimateSeconds(spec.seconds)
        report("Reading your image", 0.02f, est, "")

        val src = withContext(Dispatchers.IO) { ImageIO.load(spec.imagePath, ImageIO.MAX_LONG_SIDE * 2) }
        val size = ImageIO.outputSize(src.width, src.height)
        val frame = Bitmap.createScaledBitmap(src, size[0], size[1], true)
        if (frame !== src) src.recycle()

        report("Dividing image into pixel blocks", 0.06f, est, "")
        val grid = BlockGrid(frame, BLOCK)

        report("AI is designing your animation", 0.10f, est, "${grid.count} blocks mapped")
        val planRes = withContext(Dispatchers.IO) { Planner.make(frame, spec.prompt, spec.seconds, FPS, grid.count) }
        val plan = planRes.plan
        val detail = (plan.summary + if (planRes.note.isNotEmpty()) "\n" + planRes.note else "")
        report("Plan ready, starting render", 0.15f, est, detail)

        val device = Evidence.deviceHash(ctx)
        val stamp = (System.currentTimeMillis() / 1000L).toInt()
        val id = "px_" + System.currentTimeMillis()
        val layer = Evidence.makeLayer(size[0], size[1], Evidence.payload(device, stamp))
        val renderer = FrameRenderer(grid, plan, layer)

        val dir = File(ctx.filesDir, "videos").also { it.mkdirs() }
        val out = File(dir, "$id.mp4")
        val enc = VideoEncoder(out.absolutePath, size[0], size[1], FPS)
        val t0 = System.nanoTime()
        try {
            enc.start()
            for (f in 0 until frames) {
                currentCoroutineContext().ensureActive()
                val t = f / FPS.toFloat()
                enc.drawFrame { c -> renderer.render(c, t) }
                if (f % 6 == 0 || f == frames - 1) {
                    val el = (System.nanoTime() - t0) / 1e9
                    val per = el / (f + 1)
                    val eta = (per * (frames - f - 1)).toInt() + 2
                    report("Rendering frame ${f + 1} of $frames", 0.15f + 0.80f * (f + 1f) / frames, eta, detail)
                }
            }
            report("Encoding and sealing evidence", 0.97f, 2, detail)
            enc.finish()
        } catch (e: Throwable) {
            enc.abort()
            out.delete()
            throw e
        }
        Evidence.appendTag(out, device, stamp, id)

        val tdir = File(ctx.filesDir, "thumbs").also { it.mkdirs() }
        val thumb = File(tdir, "$id.jpg")
        val tw = 360
        val th = (360f * size[1] / size[0]).toInt().coerceAtLeast(60)
        val tb = Bitmap.createScaledBitmap(frame, tw, th, true)
        FileOutputStream(thumb).use { tb.compress(Bitmap.CompressFormat.JPEG, 82, it) }

        val p = Project(id, System.currentTimeMillis(), spec.prompt.take(400), spec.seconds, out.absolutePath, thumb.absolutePath, plan.summary, size[0], size[1])
        History.add(p)
        Credits.consume()
        File(spec.imagePath).delete()
        return p
    }
}
