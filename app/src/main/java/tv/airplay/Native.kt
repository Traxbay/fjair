package tv.airplay

import java.nio.ByteBuffer

/** Bridge to libairplay (UxPlay protocol core). Callbacks arrive on native threads; buffers are valid only during the call. */
object Native {
    interface Listener {
        fun onConn(open: Boolean)
        fun onVideoFormat(hevc: Boolean, w: Int, h: Int)
        fun onVideo(buf: ByteBuffer, ptsUs: Long)
        fun onAudioFormat(ct: Int, spf: Int)
        fun onAudio(buf: ByteBuffer, ct: Int, ptsUs: Long)
        fun onFlush()
        fun onVolume(db: Float)
        fun onClient(name: String, model: String)
    }

    @Volatile var listener: Listener? = null

    init { System.loadLibrary("airplay") }

    /** Returns the RTSP port, or a negative error. */
    @JvmStatic external fun start(name: String, mac: String, keyFile: String): Int
    @JvmStatic external fun publicKey(): String
    @JvmStatic external fun stop()
    @JvmStatic external fun txt(which: Int): ByteArray?

    /** Parses a DNS-SD TXT blob (1 = _airplay, 0 = _raop) into key/value pairs. */
    fun txtMap(which: Int): Map<String, String> {
        val b = txt(which) ?: return emptyMap()
        val m = LinkedHashMap<String, String>()
        var i = 0
        while (i < b.size) {
            val n = b[i].toInt() and 0xFF
            val kv = String(b, i + 1, minOf(n, b.size - i - 1))
            kv.indexOf('=').let { if (it > 0) m[kv.substring(0, it)] = kv.substring(it + 1) }
            i += 1 + n
        }
        return m
    }

    @JvmStatic fun onConn(open: Boolean) { listener?.onConn(open) }
    @JvmStatic fun onVideoFormat(hevc: Boolean, w: Int, h: Int) { listener?.onVideoFormat(hevc, w, h) }
    @JvmStatic fun onVideo(b: ByteBuffer, ptsUs: Long) { listener?.onVideo(b, ptsUs) }
    @JvmStatic fun onAudioFormat(ct: Int, spf: Int) { listener?.onAudioFormat(ct, spf) }
    @JvmStatic fun onAudio(b: ByteBuffer, ct: Int, ptsUs: Long) { listener?.onAudio(b, ct, ptsUs) }
    @JvmStatic fun onFlush() { listener?.onFlush() }
    @JvmStatic fun onVolume(db: Float) { listener?.onVolume(db) }
    @JvmStatic fun onClient(name: ByteArray, model: ByteArray) { listener?.onClient(String(name), String(model)) }
}
