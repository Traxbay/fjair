package tv.airplay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

/**
 * The idle screen: a dark "kitchen" layout of hairline cards, inset trays, mono labels, a pairing ticket and a
 * Control Center illustration. One looping animator pulses the status dots and the Screen Mirroring tile while the
 * TV waits; it stops whenever the screen is hidden (mirroring) or the state is not waiting/connecting.
 */
@SuppressLint("ViewConstructor")
class HomeView(ctx: Context, tvName: String, deviceId: String) : FrameLayout(ctx) {
    enum class State(val color: Int, val label: String, val pulse: Boolean) {
        WAITING(0xFF8A8A85.toInt(), "READY", true),
        CONNECTING(0xFFF5A524.toInt(), "CONNECTING", true),
        CONNECTED(0xFF3DD68C.toInt(), "CONNECTED", false),
        DISCONNECTED(0xFF8A8A85.toInt(), "DISCONNECTED", false),
        ERROR(ACCENT, "ERROR", false),
    }

    private val ticket = TicketView(ctx, tvName, deviceId)
    private val cc = ControlCenterView(ctx)
    private val title = text(19f, INK, medium)
    private val detail = text(13.5f, MUTED, regular).apply { setLineSpacing(0f, 1.3f); minLines = 2 }
    private val dot = PulseDot(ctx)
    private val dockDot = PulseDot(ctx)
    private val dockText = text(11f, MUTED, mono).apply { letterSpacing = 0.12f }
    private val ipText = text(11f, MUTED, mono).apply { letterSpacing = 0.08f }
    private var state = State.WAITING
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener {
            val p = it.animatedValue as Float
            dot.phase = p; dockDot.phase = p; cc.phase = p
        }
    }

    init {
        setBackgroundColor(BG)

        // wordmark, top-left: name over a muted subtitle
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(24f, INK, bold).apply { text = "AirPlay Receiver"; letterSpacing = -0.02f })
            addView(text(14f, MUTED, regular).apply { text = "Screen mirroring for this TV" })
        }, lp(-2, -2, Gravity.TOP or Gravity.START).margins(48, 40, 0, 0))

        // network chip, top-right
        addView(ipText.apply {
            background = shape(SURFACE, 999f, LINE); setPadding(dp(14), dp(7), dp(14), dp(7))
        }, lp(-2, -2, Gravity.TOP or Gravity.END).margins(0, 44, 48, 0))

        // ticket card (status) + Control Center card (how to connect), one row of equal height
        val hero = card(FrameLayout(ctx).apply {
            addView(ticket, lp(dp(372), dp(164), Gravity.CENTER))
        }) {
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(dot, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = -dp(6); marginEnd = dp(4) })
                addView(title)
            })
            addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        val howTo = card(cc) {
            step("01", "Join the same Wi-Fi as this TV")
            step("02", "Open Control Center")
            step("03", "Tap Screen Mirroring → $tvName")
        }
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            addView(hero, LinearLayout.LayoutParams(dp(468), -1))
            addView(howTo, LinearLayout.LayoutParams(dp(320), -1).apply { marginStart = dp(14) })
        }, lp(-2, -2, Gravity.CENTER).margins(0, 12, 0, 0))

        // status dock, bottom-centre
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = shape(0xFF0B0B0B.toInt(), 999f, LINE)
            setPadding(dp(10), dp(6), dp(20), dp(6))
            addView(dockDot, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) })
            addView(dockText)
        }, lp(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).margins(0, 0, 0, 32))

        // contact, bottom-right
        addView(text(11f, MUTED, mono).apply { text = "CONTACT  ·  abdullahf.si"; letterSpacing = 0.08f },
            lp(-2, -2, Gravity.BOTTOM or Gravity.END).margins(0, 0, 48, 45))

        show(State.WAITING, "Waiting for connection", "Open Screen Mirroring on your Apple device and select this TV.")
    }

    fun setAddress(ip: String?) { ipText.text = if (ip == null) "NO NETWORK" else "WI-FI  ·  $ip" }

    fun show(s: State, head: String, body: String, client: String? = null) {
        state = s
        title.text = head; detail.text = body
        dot.color = s.color; dockDot.color = s.color
        dockText.text = if (client == null) s.label else "${s.label}  ·  ${client.uppercase()}"
        syncAnim()
    }

    // run the animator only while it can be seen and the TV is actually waiting
    private fun syncAnim() {
        val on = state.pulse && visibility == VISIBLE && isAttachedToWindow
        if (on && !anim.isStarted) anim.start()
        if (!on && anim.isStarted) { anim.cancel(); dot.phase = -1f; dockDot.phase = -1f; cc.phase = -1f }
    }

    override fun setVisibility(v: Int) { super.setVisibility(v); syncAnim() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncAnim() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    // --- building blocks ---------------------------------------------------------------------------

    /** Outer hairline card holding an inset tray on top and a text footer below. */
    private fun card(tray: View, footer: LinearLayout.() -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(SURFACE, 18f, LINE)
        setPadding(dp(6), dp(6), dp(6), dp(6))
        tray.background = shape(TRAY, 13f, 0x0DFFFFFF)
        addView(tray, LinearLayout.LayoutParams(-1, dp(232)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(10))
            footer()
        })
    }

    private fun LinearLayout.step(n: String, label: String) = addView(LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        addView(text(10f, ACCENT, mono).apply {
            text = n; gravity = Gravity.CENTER; background = shape(0x14FF5A36, 7f, 0x33FF5A36)
        }, LinearLayout.LayoutParams(dp(26), dp(22)).apply { marginEnd = dp(12) })
        addView(text(14f, INK, regular).apply { text = label; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
    })

    private fun text(sp: Float, color: Int, face: Typeface) = TextView(context).apply {
        textSize = sp; setTextColor(color); typeface = face; includeFontPadding = false
    }

    private fun shape(fill: Int, radiusDp: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radiusDp * resources.displayMetrics.density
        if (stroke != 0) setStroke(maxOf(1, dp(1) / 2), stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int, g: Int) = LayoutParams(w, h, g)
    private fun LayoutParams.margins(l: Int, t: Int, r: Int, b: Int) = apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    companion object {
        const val BG = 0xFF111111.toInt()
        const val SURFACE = 0xFF1A1A1A.toInt()
        const val TRAY = 0xFF141414.toInt()
        const val LINE = 0xFF292929.toInt()
        const val INK = 0xFFF2F2F0.toInt()
        const val MUTED = 0xFF8A8A85.toInt()
        const val ACCENT = 0xFFFF5A36.toInt()
        val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
        val mono: Typeface = Typeface.MONOSPACE
    }
}

/** A status dot with an expanding ring while [phase] is in 0..1 (negative = still). */
private class PulseDot(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    var color = HomeView.MUTED; set(v) { field = v; invalidate() }
    var phase = -1f; set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = 4f * d
        paint.color = color
        if (phase >= 0f) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * d
            paint.alpha = ((1f - phase) * 200).toInt()
            c.drawCircle(cx, cy, r + (minOf(cx, cy) - r - d) * phase, paint)
            paint.alpha = 255
        }
        paint.style = Paint.Style.FILL
        c.drawCircle(cx, cy, r, paint)
    }
}

/**
 * A simplified iOS/macOS Control Center with the Screen Mirroring tile highlighted. While [phase] runs, a ring
 * pulses out of that tile and a fingertip presses it, so viewers see exactly where to tap.
 */
private class ControlCenterView(ctx: Context) : View(ctx) {
    private val u = resources.displayMetrics.density
    private val sd = resources.displayMetrics.scaledDensity
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; color = HomeView.MUTED; textSize = 9.5f * sd; letterSpacing = 0.14f
    }
    private val tileText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); color = HomeView.INK; textSize = 10.5f * sd
    }
    private val r = RectF()
    private val tileC = 0xFF2A2A2C.toInt()
    private val white = 0xCCFFFFFF.toInt()
    var phase = -1f; set(v) { field = v; invalidate() }

    private fun Canvas.tile(x: Float, y: Float, w: Float, h: Float, rad: Float, color: Int) {
        r.set(x, y, x + w, y + h); p.style = Paint.Style.FILL; p.color = color
        drawRoundRect(r, rad, rad, p)
    }

    override fun onDraw(c: Canvas) {
        val pad = 16 * u
        c.drawText("CONTROL CENTER", pad, pad + label.textSize, label)
        val dev = "iPHONE · iPAD · MAC"
        c.drawText(dev, width - pad - label.measureText(dev), pad + label.textSize, label)

        // 222 x 164 dp grid, centred under the labels
        val ox = (width - 222 * u) / 2f
        val oy = (height - 164 * u) / 2f + 12 * u

        // connectivity block: airplane, cellular, Wi-Fi, Bluetooth
        c.tile(ox, oy, 106 * u, 106 * u, 22 * u, tileC)
        val dots = intArrayOf(0xFF3A3A3C.toInt(), 0xFF30D158.toInt(), 0xFF0A84FF.toInt(), 0xFF0A84FF.toInt())
        p.style = Paint.Style.FILL
        for (i in 0..3) {
            val cx = ox + (29 + (i % 2) * 48) * u; val cy = oy + (29 + (i / 2) * 48) * u
            p.color = dots[i]; c.drawCircle(cx, cy, 17 * u, p)
            p.color = 0xE6FFFFFF.toInt(); c.drawCircle(cx, cy, 3.5f * u, p)
        }
        // now-playing block
        c.tile(ox + 116 * u, oy, 106 * u, 106 * u, 22 * u, tileC)
        c.tile(ox + 130 * u, oy + 20 * u, 56 * u, 6 * u, 3 * u, white)
        c.tile(ox + 130 * u, oy + 32 * u, 36 * u, 6 * u, 3 * u, 0x66FFFFFF)
        c.tile(ox + 158 * u, oy + 68 * u, 5 * u, 16 * u, 2 * u, white)
        c.tile(ox + 167 * u, oy + 68 * u, 5 * u, 16 * u, 2 * u, white)
        // rotation lock + focus
        val y2 = oy + 116 * u
        c.tile(ox, y2, 48 * u, 48 * u, 14 * u, tileC)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * u; p.color = white
        c.drawCircle(ox + 24 * u, y2 + 24 * u, 9 * u, p)
        c.tile(ox + 58 * u, y2, 48 * u, 48 * u, 14 * u, tileC)
        p.style = Paint.Style.FILL; p.color = white; c.drawCircle(ox + 82 * u, y2 + 24 * u, 9 * u, p)
        p.color = tileC; c.drawCircle(ox + 87 * u, y2 + 20 * u, 8 * u, p)

        // Screen Mirroring tile, highlighted
        val sx = ox + 116 * u; val sw = 106 * u; val sh = 48 * u
        val press = if (phase in 0f..0.22f) sin(phase / 0.22f * PI).toFloat() else 0f
        c.tile(sx, y2, sw, sh, 14 * u, if (press > 0.3f) 0xFF3A3A3C.toInt() else tileC)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * u; p.color = HomeView.ACCENT
        r.set(sx, y2, sx + sw, y2 + sh); c.drawRoundRect(r, 14 * u, 14 * u, p)
        p.color = HomeView.INK; p.strokeWidth = 1.6f * u
        r.set(sx + 12 * u, y2 + 14 * u, sx + 32 * u, y2 + 28 * u); c.drawRoundRect(r, 3 * u, 3 * u, p)
        r.set(sx + 18 * u, y2 + 20 * u, sx + 38 * u, y2 + 34 * u); c.drawRoundRect(r, 3 * u, 3 * u, p)
        c.drawText("Screen", sx + 46 * u, y2 + 21 * u, tileText)
        c.drawText("Mirroring", sx + 46 * u, y2 + 35 * u, tileText)

        if (phase >= 0f) {
            // ring pulsing out of the tile
            val e = 2 * u + 14 * u * phase
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * u; p.color = HomeView.ACCENT
            p.alpha = ((1f - phase) * 160).toInt()
            r.set(sx - e, y2 - e, sx + sw + e, y2 + sh + e); c.drawRoundRect(r, 14 * u + e, 14 * u + e, p)
            p.alpha = 255
            // fingertip pressing the tile's icon
            val fx = sx + 27 * u; val fy = y2 + 30 * u; val fr = 11 * u * (1f - 0.18f * press)
            p.style = Paint.Style.FILL; p.color = 0x40FFFFFF; c.drawCircle(fx, fy, fr, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * u; p.color = 0x99FFFFFF.toInt(); c.drawCircle(fx, fy, fr, p)
        }
    }
}

/** Orange perforated ticket: TV name on the main part, barcode on the stub. Drawn on a Canvas so it stays crisp on any panel. */
@SuppressLint("ViewConstructor")
private class TicketView(ctx: Context, private val name: String, private val serial: String) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val sd = resources.displayMetrics.scaledDensity
    private val ink = 0xFF1C0D08.toInt()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; color = ink; textSize = 9.5f * sd; letterSpacing = 0.14f }
    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD); color = ink; textSize = 27f * sd; letterSpacing = -0.02f }
    private val perf = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = 0x551C0D08; pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
    }
    private val bars = serial.filter { it.isLetterOrDigit() }.map { (it.digitToIntOrNull(16) ?: 7) % 4 + 1 }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = 12 * d; val pad = 18 * d
        val stub = w * 0.76f; val notch = 9 * d

        fill.color = HomeView.ACCENT
        c.drawRoundRect(RectF(0f, 0f, w, h), r, r, fill)
        fill.color = HomeView.TRAY  // punch the notches in the tray colour behind the ticket
        c.drawCircle(stub, 0f, notch, fill); c.drawCircle(stub, h, notch, fill)
        c.drawLine(stub, notch + 4 * d, stub, h - notch - 4 * d, perf)

        // dot grid, top-right of the main part
        fill.color = 0x331C0D08
        for (i in 0 until 6) for (j in 0 until 3) c.drawCircle(stub - pad - i * 7 * d, pad + 2 * d + j * 7 * d, 1.4f * d, fill)

        c.drawText("SCREEN MIRRORING", pad, pad + label.textSize, label)
        val shown = TextUtils.ellipsize(name, big, stub - 2 * pad, TextUtils.TruncateAt.END).toString()
        c.drawText(shown, pad, h * 0.56f, big)
        c.drawText("AIRPLAY RECEIVER", pad, h - pad - label.textSize - 6 * d, label)
        c.drawText("NO. ${serial.replace(":", "")}", pad, h - pad, label)

        // stub: barcode + "ADMIT 1"
        fill.color = ink
        var x = stub + 14 * d
        val right = w - 14 * d
        var i = 0
        while (bars.isNotEmpty()) {
            val bw = bars[i % bars.size] * d
            if (x + bw > right) break
            if (i % 2 == 0) c.drawRect(x, pad, x + bw, h - pad - 18 * d, fill)
            x += bw + 1.6f * d; i++
        }
        c.drawText("ADMIT 1", stub + 14 * d, h - pad, label)
    }
}
