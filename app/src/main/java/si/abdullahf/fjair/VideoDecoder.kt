package si.abdullahf.fjair

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/** Hardware H.264/HEVC decode straight to a Surface. Frames render as they decode (low latency, no pacing). */
class VideoDecoder(
    private val surface: Surface, hevc: Boolean, w: Int, h: Int,
    private val onSize: (Int, Int) -> Unit, private val onError: (String) -> Unit,
) {
    private val codec: MediaCodec
    @Volatile private var running = true

    init {
        val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val fmt = MediaFormat.createVideoFormat(mime, w, h).apply {
            // headroom so an orientation change (new SPS) doesn't force a codec rebuild
            setInteger(MediaFormat.KEY_MAX_WIDTH, maxOf(w, h)); setInteger(MediaFormat.KEY_MAX_HEIGHT, maxOf(w, h))
            if (android.os.Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, surface, null, 0)
        codec.start()
        Thread(::drain, "video-drain").start()
    }

    fun feed(buf: ByteBuffer, ptsUs: Long) {
        if (!running) return
        try {
            val i = codec.dequeueInputBuffer(20_000)
            if (i < 0) return  // decoder saturated: drop the frame rather than build latency
            val inb = codec.getInputBuffer(i)!!
            if (buf.remaining() > inb.capacity()) { codec.queueInputBuffer(i, 0, 0, ptsUs, 0); return }
            inb.clear(); inb.put(buf)
            codec.queueInputBuffer(i, 0, inb.position(), ptsUs, 0)
        } catch (e: Exception) { fail(e) }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val o = codec.dequeueOutputBuffer(info, 100_000)
                if (o >= 0) codec.releaseOutputBuffer(o, true)
                else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    val cw = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
                    val ch = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
                    onSize(cw, ch)
                }
            }
        } catch (e: Exception) { if (running) fail(e) }
    }

    private fun fail(e: Exception) { running = false; onError(e.message ?: e.javaClass.simpleName) }

    fun release() {
        running = false
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }
}
