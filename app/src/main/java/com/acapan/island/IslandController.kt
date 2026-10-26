package com.acapan.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

/**
 * Floating pill with three states:
 *  IDLE     small black pill sitting over the camera (always-on mode)
 *  COMPACT  icon + title, shown when a notification arrives
 *  EXPANDED app name + title + body (tap the compact pill)
 *
 * Tap idle pill     -> replays the last notification
 * Tap compact pill  -> expand
 * Tap expanded pill -> open the app
 * Swipe up          -> back to idle
 */
object IslandController {

    private enum class State { IDLE, COMPACT, EXPANDED }

    private const val COMPACT_H = 40 // dp
    private const val IDLE_H = 32 // dp
    private const val COMPACT_MS = 3500L
    private const val EXPANDED_MS = 7000L

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var wlp: WindowManager.LayoutParams? = null
    private var root: View? = null
    private var pill: View? = null
    private var bg: GradientDrawable? = null
    private var anim: ValueAnimator? = null
    private var state = State.IDLE
    private var current: IslandData? = null
    private var last: IslandData? = null
    private val autoHide = Runnable { settle() }

    // true while the system island is busy (music, call, alarm...). We stay out of its way.
    private var yielding = false

    /** Why we are yielding, shown in the app's status text. Set by the notification service. */
    var yieldReason: String = ""

    fun describe(): String {
        val where = if (root == null) "Pill: not on screen" else "Pill: on screen (${state.name.lowercase()})"
        return if (yielding) "$where\nHidden because: ${yieldReason.ifBlank { "unknown" }}" else where
    }

    // ---------------------------------------------------------------- public API

    /** Show the idle pill (always-on). Safe to call repeatedly, it also re-applies size/position. */
    fun start(context: Context) {
        val app = context.applicationContext
        main.post {
            if (yielding) return@post
            if (!ensureWindow(app)) return@post
            if (state == State.IDLE) goIdle(app)
        }
    }

    /** Remove the pill completely. */
    fun stop() {
        main.post { removeNow() }
    }

    /**
     * Called by the notification service. While the system island is showing something
     * (music playing, call, alarm), the idle pill is removed so it doesn't cover it.
     * Notifications can still pop up briefly and then the pill disappears again.
     */
    fun setYield(context: Context, value: Boolean) {
        val app = context.applicationContext
        main.post {
            if (yielding == value) return@post
            yielding = value
            if (value) {
                if (state == State.IDLE) removeNow()
            } else if (Prefs.alwaysOn(app)) {
                if (ensureWindow(app) && state == State.IDLE) goIdle(app)
            }
        }
    }

    fun show(context: Context, data: IslandData) {
        val app = context.applicationContext
        main.post { showNow(app, data) }
    }

    // ---------------------------------------------------------------- window

    private fun ensureWindow(ctx: Context): Boolean {
        if (!Settings.canDrawOverlays(ctx)) return false

        val existing = root
        if (existing != null) {
            wlp?.let {
                it.y = ctx.dp(Prefs.yOffset(ctx))
                try {
                    wm?.updateViewLayout(existing, it)
                } catch (_: Exception) {
                }
            }
            return true
        }

        val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(ctx).inflate(R.layout.island, null)
        val pillView = view.findViewById<View>(R.id.pill)

        view.findViewById<ImageView>(R.id.icon).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = ctx.dp(7).toFloat()
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }

        val idleH = ctx.dp(IDLE_H)
        val background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = idleH / 2f
        }
        pillView.background = background
        pillView.layoutParams = FrameLayout.LayoutParams(ctx.dp(Prefs.idleWidth(ctx)), idleH).apply {
            topMargin = ((ctx.dp(COMPACT_H) - idleH) / 2).coerceAtLeast(0)
        }

        pillView.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (dy < -ctx.dp(20) && abs(dy) > abs(dx)) {
                            if (state != State.IDLE) settle()
                        } else if (abs(dx) < ctx.dp(10) && abs(dy) < ctx.dp(10)) {
                            onTap(ctx)
                        }
                    }
                }
                return true
            }
        })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = ctx.dp(Prefs.yOffset(ctx))
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        windowManager.addView(view, lp)
        wm = windowManager
        wlp = lp
        root = view
        pill = pillView
        bg = background
        state = State.IDLE
        hideContent()
        return true
    }

    // ---------------------------------------------------------------- states

    private fun showNow(ctx: Context, data: IslandData) {
        if (!ensureWindow(ctx)) return
        val p = pill ?: return
        val b = bg ?: return
        val v = root ?: return

        b.setColor(Color.BLACK)
        main.removeCallbacks(autoHide)
        current = data
        last = data

        v.findViewById<ImageView>(R.id.icon).apply {
            setImageDrawable(data.icon)
            visibility = View.VISIBLE
        }
        v.findViewById<TextView>(R.id.app).apply { text = data.appName; visibility = View.GONE }
        v.findViewById<TextView>(R.id.title).text = data.title
        v.findViewById<TextView>(R.id.body).apply { text = data.text; visibility = View.GONE }
        v.findViewById<View>(R.id.texts).visibility = View.VISIBLE
        p.setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))

        state = State.COMPACT
        val h = ctx.dp(COMPACT_H)
        animate(p, b, ctx.dp(Prefs.width(ctx)), h, h / 2f, 320)
        main.postDelayed(autoHide, COMPACT_MS)
    }

    private fun expand(ctx: Context) {
        val p = pill ?: return
        val b = bg ?: return
        val v = root ?: return
        state = State.EXPANDED

        v.findViewById<View>(R.id.app).visibility = View.VISIBLE
        v.findViewById<View>(R.id.body).visibility =
            if (current?.text.isNullOrBlank()) View.GONE else View.VISIBLE
        p.setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))

        val w = min(ctx.resources.displayMetrics.widthPixels - ctx.dp(24), ctx.dp(360))
        p.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        animate(p, b, w, p.measuredHeight, ctx.dp(28).toFloat(), 300)

        main.removeCallbacks(autoHide)
        main.postDelayed(autoHide, EXPANDED_MS)
    }

    private fun goIdle(ctx: Context) {
        val p = pill ?: return
        val b = bg ?: return
        main.removeCallbacks(autoHide)
        b.setColor(if (Prefs.debugColor(ctx)) Color.RED else Color.BLACK)
        state = State.IDLE
        current = null
        hideContent()
        val h = ctx.dp(IDLE_H)
        animate(p, b, ctx.dp(Prefs.idleWidth(ctx)), h, h / 2f, 260)
    }

    /** Auto-hide / swipe: back to idle in always-on mode, otherwise shrink and remove. */
    private fun settle() {
        val ctx = root?.context ?: return
        if (Prefs.alwaysOn(ctx) && !yielding) {
            goIdle(ctx)
        } else {
            val p = pill ?: return
            val b = bg ?: return
            main.removeCallbacks(autoHide)
            hideContent()
            val h = ctx.dp(IDLE_H)
            animate(p, b, ctx.dp(Prefs.idleWidth(ctx)), h, h / 2f, 200, AccelerateInterpolator()) {
                removeNow()
            }
        }
    }

    private fun onTap(ctx: Context) {
        when (state) {
            State.IDLE -> last?.let { showNow(ctx, it) }
            State.COMPACT -> expand(ctx)
            State.EXPANDED -> {
                current?.onOpen?.invoke()
                settle()
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun hideContent() {
        root?.findViewById<View>(R.id.icon)?.visibility = View.GONE
        root?.findViewById<View>(R.id.texts)?.visibility = View.GONE
    }

    private fun animate(
        p: View,
        b: GradientDrawable,
        w: Int,
        h: Int,
        radius: Float,
        ms: Long,
        interp: TimeInterpolator = OvershootInterpolator(0.8f),
        onEnd: (() -> Unit)? = null
    ) {
        anim?.removeAllListeners()
        anim?.cancel()

        val lp = p.layoutParams as FrameLayout.LayoutParams
        val w0 = lp.width
        val h0 = lp.height
        val r0 = b.cornerRadius
        val compactH = p.context.dp(COMPACT_H)

        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = interp
            addUpdateListener {
                val t = it.animatedValue as Float
                lp.width = (w0 + (w - w0) * t).toInt().coerceAtLeast(1)
                lp.height = (h0 + (h - h0) * t).toInt().coerceAtLeast(1)
                // keep small shapes vertically centred on the same line as the compact pill
                lp.topMargin = ((compactH - lp.height) / 2).coerceAtLeast(0)
                p.layoutParams = lp
                b.cornerRadius = (r0 + (radius - r0) * t).coerceAtLeast(0f)
            }
            if (onEnd != null) {
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) = onEnd()
                })
            }
            start()
        }
    }

    private fun removeNow() {
        anim?.removeAllListeners()
        anim?.cancel()
        anim = null
        main.removeCallbacks(autoHide)
        root?.let {
            try {
                wm?.removeViewImmediate(it)
            } catch (_: Exception) {
            }
        }
        root = null
        pill = null
        bg = null
        wlp = null
        current = null
        state = State.IDLE
    }
}
