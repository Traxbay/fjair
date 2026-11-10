package si.abdullahf.fjair

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
import java.nio.ByteBuffer

class MainActivity : Activity(), Native.Listener {
    private lateinit var home: HomeView
    private lateinit var video: SurfaceView
    private lateinit var root: FrameLayout
    private lateinit var adv: Advertiser
    @Volatile private var decoder: VideoDecoder? = null
    @Volatile private var audio: AudioDecoder? = null
    @Volatile private var volume = 0f
    @Volatile private var hasSurface = false
    @Volatile private var client: String? = null
    private var vw = 0
    private var vh = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        video = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) { hasSurface = true }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) { hasSurface = false; stopVideo() }
            })
        }
        adv = Advertiser(this, Build.MODEL)
        home = HomeView(this, Build.MODEL, adv.deviceId).apply { setAddress(localIp()) }
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(home, FrameLayout.LayoutParams(-1, -1))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
        }
        setContentView(root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val port = Native.start(Build.MODEL, adv.deviceId, filesDir.resolve("ed25519.key").path)
        if (port < 0) { home.show(HomeView.State.ERROR, "Receiver failed to start", "Restart the app. If it keeps failing, restart the TV. (code $port)"); return }
        Native.listener = this
        adv.start(port) { Log.i("FJAir", it) }
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

    /** First non-loopback IPv4 address (Wi-Fi or Ethernet). */
    private fun localIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address }?.hostAddress
    } catch (_: Exception) { null }

    private fun showHome(s: HomeView.State, head: String, body: String) = runOnUiThread {
        home.show(s, head, body, client); home.visibility = View.VISIBLE
    }

    override fun onClient(name: String, model: String) {
        client = name
        if (decoder == null) showHome(HomeView.State.CONNECTING, "Connecting…", "$name is setting up screen mirroring.")
    }

    override fun onConn(open: Boolean) {
        if (open) return
        stopVideo(); stopAudio()
        val who = client ?: "Your device"
        client = null
        showHome(HomeView.State.DISCONNECTED, "Disconnected", "$who stopped mirroring.")
        root.postDelayed({
            if (decoder == null && client == null) {
                home.setAddress(localIp())
                home.show(HomeView.State.WAITING, "Waiting for connection", "Open Screen Mirroring on your Apple device and select this TV.")
            }
        }, 4000)
    }

    override fun onVideoFormat(hevc: Boolean, w: Int, h: Int) {
        stopVideo()
        runOnUiThread { vw = w; vh = h; fit(); home.show(HomeView.State.CONNECTED, "Mirroring", "", client); home.visibility = View.GONE }
        if (!hasSurface) { Log.w("FJAir", "no surface for video"); return }
        decoder = try {
            VideoDecoder(video.holder.surface, hevc, w, h,
                { cw, ch -> runOnUiThread { vw = cw; vh = ch; fit() } },
                { m -> Log.e("FJAir", "decoder: $m"); showHome(HomeView.State.ERROR, "Video stopped", "The TV's video decoder failed. Stop and start Screen Mirroring again.") })
        } catch (e: Exception) {
            Log.e("FJAir", "decoder init failed", e)
            showHome(HomeView.State.ERROR, "Video not supported", "This TV has no hardware decoder for the stream (${if (hevc) "HEVC" else "H.264"} ${w}x$h).")
            null
        }
    }

    override fun onVideo(buf: ByteBuffer, ptsUs: Long) { decoder?.feed(buf, ptsUs) }
    override fun onAudioFormat(ct: Int, spf: Int) {
        stopAudio()
        Log.i("FJAir", "audio ct=$ct spf=$spf")
        if (ct != 4 && ct != 8) { Log.e("FJAir", "unsupported audio codec ct=$ct"); return }
        audio = try {
            AudioDecoder(ct) { m -> Log.e("FJAir", "audio: $m"); stopAudio() }.also { it.setVolume(volume) }
        } catch (e: Exception) { Log.e("FJAir", "audio init failed", e); null }
    }

    override fun onAudio(buf: ByteBuffer, ct: Int, ptsUs: Long) { audio?.feed(buf, ptsUs) }
    override fun onFlush() { audio?.flush() }
    override fun onVolume(db: Float) { volume = db; audio?.setVolume(db) }

    override fun onDestroy() {
        Native.listener = null; Native.stop(); adv.stop(); stopVideo(); stopAudio()
        super.onDestroy()
    }
}
