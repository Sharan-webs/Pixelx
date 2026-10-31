package com.pixelx.app

import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.view.Surface

/**
 * Frame-by-frame H.264 MP4 encoder. Frames are drawn straight into the encoder's input surface,
 * so only ONE frame ever exists in memory at a time.
 */
class VideoEncoder(private val path: String, private val w: Int, private val h: Int, private val fps: Int) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var surface: Surface? = null
    private var track = -1
    private var started = false
    private var frames = 0L
    private val info = MediaCodec.BufferInfo()

    fun start() {
        val bitrate = (w.toLong() * h * fps * 0.3).toInt().coerceIn(2_000_000, 25_000_000)
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        if (Build.VERSION.SDK_INT >= 29) fmt.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = c
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = c.createInputSurface()
        c.start()
        muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun drawFrame(block: (Canvas) -> Unit) {
        val s = surface ?: error("Encoder not started")
        val canvas = s.lockCanvas(null)
        try {
            block(canvas)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
        drain(false)
    }

    fun finish() {
        codec?.signalEndOfInputStream()
        drain(true)
        release(true)
    }

    fun abort() = release(false)

    private fun release(clean: Boolean) {
        try { codec?.stop() } catch (e: Exception) {}
        try { codec?.release() } catch (e: Exception) {}
        try { surface?.release() } catch (e: Exception) {}
        try { if (started && clean) muxer?.stop() } catch (e: Exception) {}
        try { muxer?.release() } catch (e: Exception) {}
        codec = null; muxer = null; surface = null
    }

    private fun drain(untilEnd: Boolean) {
        val c = codec ?: return
        var idle = 0
        while (true) {
            val idx = c.dequeueOutputBuffer(info, if (untilEnd) 10_000L else 0L)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEnd) return
                idle++
                if (idle > 1500) throw IllegalStateException("Video encoder did not finish in time.")
                continue
            }
            idle = 0
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer!!.addTrack(c.outputFormat)
                muxer!!.start()
                started = true
                continue
            }
            if (idx < 0) continue
            val buf = c.getOutputBuffer(idx)
            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
            if (info.size > 0 && started && buf != null) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                // frames are rendered faster than real time, so we stamp exact, evenly spaced times
                info.presentationTimeUs = frames * 1_000_000L / fps
                frames++
                muxer!!.writeSampleData(track, buf, info)
            }
            c.releaseOutputBuffer(idx, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
        }
    }
}
