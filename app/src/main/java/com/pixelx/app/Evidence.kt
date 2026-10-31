package com.pixelx.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Invisible evidence mark.
 *
 * Layer 1 (pixels): a faint spread-spectrum pattern is multiplied into every frame. It carries a
 * 96-bit payload (magic + device hash + time + CRC). Each bit is spread over ~150 cells of 8x8 pixels
 * with a secret chip pattern, so it is invisible to the eye but our detector can read it back.
 * Layer 2 (file): a harmless MP4 "free" box with a text tag is appended to the file.
 */
object Evidence {
    private const val CELL = 8
    private const val BITS = 96
    private const val STRENGTH = 0.055f
    private const val TAG = "PIXELX-EVIDENCE:v1:"

    class Found(val device: String, val epochSec: Long)

    fun deviceHash(ctx: Context): Int {
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val d = MessageDigest.getInstance("SHA-256").digest(("pixelx|" + id).toByteArray())
        return ((d[0].toInt() and 255) shl 24) or ((d[1].toInt() and 255) shl 16) or ((d[2].toInt() and 255) shl 8) or (d[3].toInt() and 255)
    }

    fun hex(v: Int): String = String.format(Locale.US, "%08x", v)

    private fun hash(a: Int): Int {
        var x = a * -1640531535 + 374761393
        x = x xor (x ushr 15)
        x *= -2048144789
        x = x xor (x ushr 13)
        x *= -1028477387
        x = x xor (x ushr 16)
        return x
    }

    private fun chip(ci: Int): Int = if ((hash(ci) and 1) == 0) 1 else -1

    private fun crc16(d: ByteArray, len: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor ((d[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    fun payload(device: Int, stamp: Int): ByteArray {
        val b = ByteArray(12)
        b[0] = 0x50.toByte(); b[1] = 0x58.toByte()
        for (k in 0 until 4) {
            b[2 + k] = (device ushr (24 - 8 * k)).toByte()
            b[6 + k] = (stamp ushr (24 - 8 * k)).toByte()
        }
        val c = crc16(b, 10)
        b[10] = (c shr 8).toByte(); b[11] = c.toByte()
        return b
    }

    private fun bit(p: ByteArray, k: Int): Int = (p[k / 8].toInt() shr (7 - k % 8)) and 1

    /** Full-frame multiply layer (white = no change, slightly darker cells carry the mark). W and H must be multiples of 16. */
    fun makeLayer(w: Int, h: Int, payload: ByteArray): Bitmap {
        val cols = w / CELL
        val rows = h / CELL
        val g = (255 * (1f - STRENGTH)).toInt()
        val dark = Color.rgb(g, g, g)
        val px = IntArray(w * h) { -1 }
        for (cy in 0 until rows) {
            for (cx in 0 until cols) {
                val ci = cy * cols + cx
                val s = chip(ci) * (if (bit(payload, ci % BITS) == 1) 1 else -1)
                for (yy in 0 until CELL) {
                    val base = (cy * CELL + yy) * w + cx * CELL
                    for (xx in 0 until CELL) {
                        val sgn = s * (if (xx < CELL / 2) 1 else -1)
                        if (sgn < 0) px[base + xx] = dark
                    }
                }
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    class Detector {
        private val score = DoubleArray(BITS)
        private var used = 0

        fun add(bm: Bitmap) {
            val w = bm.width
            val h = bm.height
            val cols = w / CELL
            val rows = h / CELL
            if (cols < 8 || rows < 8) return
            val px = IntArray(w * h)
            bm.getPixels(px, 0, w, 0, 0, w, h)
            for (cy in 0 until rows) {
                for (cx in 0 until cols) {
                    var hs = 0
                    for (yy in 0 until CELL) {
                        val base = (cy * CELL + yy) * w + cx * CELL
                        for (xx in 0 until CELL) {
                            val c = px[base + xx]
                            val l = (((c shr 16) and 255) * 3) + (((c shr 8) and 255) * 6) + (c and 255)
                            hs += if (xx < CELL / 2) l else -l
                        }
                    }
                    val ci = cy * cols + cx
                    score[ci % BITS] += hs.toDouble() * chip(ci)
                }
            }
            used++
        }

        fun result(): Found? {
            if (used == 0) return null
            val b = ByteArray(12)
            for (k in 0 until BITS) if (score[k] > 0) b[k / 8] = (b[k / 8].toInt() or (1 shl (7 - k % 8))).toByte()
            if (b[0] != 0x50.toByte() || b[1] != 0x58.toByte()) return null
            val c = ((b[10].toInt() and 255) shl 8) or (b[11].toInt() and 255)
            if (c != crc16(b, 10)) return null
            var dev = 0
            var st = 0L
            for (k in 0 until 4) {
                dev = (dev shl 8) or (b[2 + k].toInt() and 255)
                st = (st shl 8) or (b[6 + k].toLong() and 255L)
            }
            return Found(hex(dev), st)
        }
    }

    fun appendTag(file: File, device: Int, stamp: Int, id: String) {
        val text = (TAG + hex(device) + ":" + (stamp.toLong() and 0xFFFFFFFFL) + ":" + id).toByteArray()
        val size = 8 + text.size
        FileOutputStream(file, true).use { o ->
            o.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
            o.write("free".toByteArray())
            o.write(text)
        }
    }

    private fun readTag(ctx: Context, uri: Uri): String? {
        return try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd.use {
                FileInputStream(it.fileDescriptor).use { fis ->
                    val ch = fis.channel
                    val len = ch.size()
                    val take = minOf(len, 131072L).toInt()
                    ch.position(len - take)
                    val buf = java.nio.ByteBuffer.allocate(take)
                    while (buf.hasRemaining()) { if (ch.read(buf) < 0) break }
                    val s = String(buf.array(), 0, buf.position(), Charsets.ISO_8859_1)
                    val i = s.lastIndexOf(TAG)
                    if (i < 0) null else s.substring(i).takeWhile { it.code in 32..126 }
                }
            }
        } catch (e: Exception) { null }
    }

    /** Returns a human readable verdict for a video the user picked. Runs on a background thread. */
    fun verify(ctx: Context, uri: Uri): String {
        val tag = readTag(ctx, uri)
        var found: Found? = null
        try {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(ctx, uri)
                val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val det = Detector()
                for (k in 1..6) {
                    val bm = mmr.getFrameAtTime(dur * 1000L * k / 7, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                    det.add(bm)
                    bm.recycle()
                }
                found = det.result()
            } finally {
                try { mmr.release() } catch (e: Exception) {}
            }
        } catch (e: Exception) {}

        val sb = StringBuilder()
        val found2 = found
        if (found2 != null) {
            sb.append("PIXEL•X pixel evidence DETECTED.\nDevice ID: ").append(found2.device)
                .append("\nCreated (UTC): ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date(found2.epochSec * 1000L)))
        } else sb.append("No pixel evidence found (it can be weakened by re-compression, cropping or resizing).")
        if (tag != null) {
            val p = tag.split(":")
            sb.append("\n\nFile tag found")
            if (p.size >= 5) sb.append(": device ").append(p[2]).append(", video ").append(p[4])
        } else sb.append("\n\nNo file tag found.")
        return sb.toString()
    }
}
