package tv.airplay

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.nio.ByteBuffer

class MainActivity : Activity(), Native.Listener {
    private lateinit var status: TextView
    private lateinit var info: View
    private lateinit var video: SurfaceView
    private lateinit var root: FrameLayout
    private lateinit var adv: Advertiser
    @Volatile private var decoder: VideoDecoder? = null
    @Volatile private var audio: AudioDecoder? = null
    @Volatile private var volume = 0f
    @Volatile private var hasSurface = false
    private var vw = 0
    private var vh = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        fun tv(t: String, sp: Float) = TextView(this).apply {
            text = t; textSize = sp; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        status = tv("Waiting for connection", 28f)
        video = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) { hasSurface = true }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) { hasSurface = false; stopVideo() }
            })
        }
        info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(16, 16, 16))
            addView(tv("AirPlay Receiver", 40f))
            addView(tv(Build.MODEL, 24f))
            addView(status)
            addView(tv("Open Screen Mirroring on your Apple device and select this TV.", 20f))
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(info, FrameLayout.LayoutParams(-1, -1))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
        }
        setContentView(root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        adv = Advertiser(this, Build.MODEL)
        val port = Native.start(Build.MODEL, adv.deviceId, filesDir.resolve("ed25519.key").path)
        if (port < 0) { status.text = "Receiver failed to start ($port)"; return }
        Native.listener = this
        adv.start(port) { Log.i("AirPlay", it) }
    }

    /** Letterbox the surface to the video's aspect ratio. */
    private fun fit() {
        if (vw == 0 || vh == 0 || root.width == 0) return
        val s = minOf(root.width.toFloat() / vw, root.height.toFloat() / vh)
        val lp = video.layoutParams as FrameLayout.LayoutParams
        val nw = (vw * s).toInt(); val nh = (vh * s).toInt()
        if (lp.width != nw || lp.height != nh) { lp.width = nw; lp.height = nh; video.layoutParams = lp }
    }

    private fun stopVideo() { decoder?.release(); decoder = null }
    private fun stopAudio() { audio?.release(); audio = null }

    override fun onConn(open: Boolean) = runOnUiThread {
        if (open) status.text = "Connected"
        else { stopVideo(); stopAudio(); status.text = "Waiting for connection"; info.visibility = View.VISIBLE }
    }

    override fun onVideoFormat(hevc: Boolean, w: Int, h: Int) {
        stopVideo()
        runOnUiThread { vw = w; vh = h; fit(); info.visibility = View.GONE }
        if (!hasSurface) { Log.w("AirPlay", "no surface for video"); return }
        decoder = try {
            VideoDecoder(video.holder.surface, hevc, w, h,
                { cw, ch -> runOnUiThread { vw = cw; vh = ch; fit() } },
                { m -> Log.e("AirPlay", "decoder: $m"); runOnUiThread { status.text = "Video decoder error"; info.visibility = View.VISIBLE } })
        } catch (e: Exception) {
            Log.e("AirPlay", "decoder init failed", e)
            runOnUiThread { status.text = "Hardware decoder unavailable"; info.visibility = View.VISIBLE }
            null
        }
    }

    override fun onVideo(buf: ByteBuffer, ptsUs: Long) { decoder?.feed(buf, ptsUs) }
    override fun onAudioFormat(ct: Int, spf: Int) {
        stopAudio()
        Log.i("AirPlay", "audio ct=$ct spf=$spf")
        if (ct != 4 && ct != 8) { Log.e("AirPlay", "unsupported audio codec ct=$ct"); return }
        audio = try {
            AudioDecoder(ct) { m -> Log.e("AirPlay", "audio: $m"); stopAudio() }.also { it.setVolume(volume) }
        } catch (e: Exception) { Log.e("AirPlay", "audio init failed", e); null }
    }

    override fun onAudio(buf: ByteBuffer, ct: Int, ptsUs: Long) { audio?.feed(buf, ptsUs) }
    override fun onFlush() { audio?.flush() }
    override fun onVolume(db: Float) { volume = db; audio?.setVolume(db) }

    override fun onDestroy() {
        Native.listener = null; Native.stop(); adv.stop(); stopVideo(); stopAudio()
        super.onDestroy()
    }
}
