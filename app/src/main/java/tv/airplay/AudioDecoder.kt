package tv.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

/** AAC-ELD (ct 8) / AAC-LC (ct 4) decode to a low-latency AudioTrack. 44.1 kHz stereo, as AirPlay mirroring uses. */
class AudioDecoder(ct: Int, private val onError: (String) -> Unit) {
    private val codec: MediaCodec
    private val track: AudioTrack
    @Volatile private var running = true

    init {
        // AudioSpecificConfig: ELD = AOT 39, LC = AOT 2; both 44100 Hz, 2 channels
        val asc = if (ct == 8) byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00) else byteArrayOf(0x12, 0x10)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
            setByteBuffer("csd-0", ByteBuffer.wrap(asc))
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, 0)
        val pcm = AudioFormat.Builder().setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(pcm)
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2, 16384))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        codec.start(); track.play()
        Thread(::drain, "audio-drain").start()
    }

    fun feed(buf: ByteBuffer, ptsUs: Long) {
        if (!running) return
        try {
            val i = codec.dequeueInputBuffer(10_000)
            if (i < 0) return
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
                if (o >= 0) {
                    val out = codec.getOutputBuffer(o)!!
                    out.position(info.offset); out.limit(info.offset + info.size)
                    track.write(out, info.size, AudioTrack.WRITE_BLOCKING)
                    codec.releaseOutputBuffer(o, false)
                }
            }
        } catch (e: Exception) { if (running) fail(e) }
    }

    /** AirPlay volume: -30..0 dB linear-ish, -144 = mute. */
    fun setVolume(db: Float) {
        track.setVolume(if (db <= -30f) 0f else if (db >= 0f) 1f else (30f + db) / 30f)
    }

    fun flush() {
        try { codec.flush(); track.pause(); track.flush(); track.play() } catch (_: Exception) {}
    }

    private fun fail(e: Exception) { running = false; onError(e.message ?: e.javaClass.simpleName) }

    fun release() {
        running = false
        try { track.stop() } catch (_: Exception) {}
        track.release()
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }
}
