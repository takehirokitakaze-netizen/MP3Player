package jp.kitakaze.player

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * どの画面の上にも浮かぶ丸ボタン（iPhoneのAssistiveTouch風）。
 * 押すと操作メニューを表示し、メニューの外をタップするまで開いたまま。
 * 形は「パネル（ウィジェットと同じ並び）」と「サークル（ボタンの周りに半円）」。
 */
class FloatingControls(private val service: PlaybackService) {

    companion object {
        const val STYLE_PANEL = "panel"
        const val STYLE_CIRCLE = "circle"

        fun formatTime(ms: Long): String {
            if (ms == C.TIME_UNSET || ms < 0) return "--:--"
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var menu: FrameLayout? = null
    private var menuTitle: TextView? = null
    private var menuTime: TextView? = null
    private var menuPlay: TextView? = null

    private val bubbleSize get() = dp(56)

    private val ticker = object : Runnable {
        override fun run() {
            updateMenuTexts()
            handler.postDelayed(this, 500)
        }
    }

    // ---- 外から呼ぶもの ----

    /** 設定・権限・曲の有無に合わせて、丸ボタンを出す／消す */
    fun refresh() {
        val prefs = Prefs.get(service)
        val want = prefs.getBoolean(Prefs.KEY_FLOAT_ON, false) &&
            Settings.canDrawOverlays(service) &&
            service.player.mediaItemCount > 0
        if (want) showBubble() else hideAll()
    }

    fun onStateChanged() {
        updateBubble()
        updateMenuTexts()
    }

    fun hideAll() {
        closeMenu()
        bubble?.let { safeRemove(it) }
        bubble = null
        bubbleParams = null
    }

    // ---- 丸ボタン ----

    private fun showBubble() {
        if (bubble != null) {
            updateBubble()
            return
        }
        val prefs = Prefs.get(service)
        val (sw, sh) = screenSize()
        val view = TextView(service).apply {
            text = "♪"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 24f
            alpha = 0.85f
        }
        val lp = WindowManager.LayoutParams(
            bubbleSize, bubbleSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(Prefs.KEY_FLOAT_X, sw - bubbleSize)
            y = prefs.getInt(Prefs.KEY_FLOAT_Y, sh / 3)
        }
        view.setOnTouchListener(BubbleTouch(lp))
        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            return
        }
        bubble = view
        bubbleParams = lp
        updateBubble()
    }

    private fun updateBubble() {
        val b = bubble ?: return
        val playing = service.player.isPlaying
        b.background = circle(if (playing) 0xFF1E88E5.toInt() else 0xFF546E7A.toInt(), stroke = true)
    }

    private inner class BubbleTouch(private val lp: WindowManager.LayoutParams) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private val slop = ViewConfiguration.get(service).scaledTouchSlop

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        safeUpdate(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        snapToEdge(v, lp)
                    } else {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        openMenu()
                    }
                }
            }
            return true
        }
    }

    private fun snapToEdge(v: View, lp: WindowManager.LayoutParams) {
        val (sw, sh) = screenSize()
        lp.x = if (lp.x + bubbleSize / 2 < sw / 2) 0 else sw - bubbleSize
        lp.y = lp.y.coerceIn(0, maxOf(0, sh - bubbleSize - dp(48)))
        safeUpdate(v, lp)
        Prefs.get(service).edit()
            .putInt(Prefs.KEY_FLOAT_X, lp.x)
            .putInt(Prefs.KEY_FLOAT_Y, lp.y)
            .apply()
    }

    // ---- メニュー ----

    private fun openMenu() {
        if (menu != null) return
        val root = FrameLayout(service).apply {
            setBackgroundColor(0x40000000)
            setOnClickListener { closeMenu() } // メニューの外をタップしたら閉じる
        }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val circleStyle = Prefs.get(service).getString(Prefs.KEY_FLOAT_STYLE, STYLE_PANEL) == STYLE_CIRCLE
        val layouter: (FrameLayout) -> Unit = if (circleStyle) buildCircle(root) else buildPanel(root)

        for (i in 0 until root.childCount) root.getChildAt(i).visibility = View.INVISIBLE
        try {
            wm.addView(root, lp)
        } catch (e: Exception) {
            return
        }
        menu = root
        root.post {
            layouter(root)
            for (i in 0 until root.childCount) root.getChildAt(i).visibility = View.VISIBLE
        }
        updateMenuTexts()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun closeMenu() {
        handler.removeCallbacks(ticker)
        menu?.let { safeRemove(it) }
        menu = null
        menuTitle = null
        menuTime = null
        menuPlay = null
    }

    /** ウィジェットと同じ並びの操作 */
    private fun actions(): List<Pair<String, () -> Unit>> = listOf(
        "-30" to { service.seekBy(-30_000) },
        "-10" to { service.seekBy(-10_000) },
        "▶" to { service.togglePlay() },
        "+10" to { service.seekBy(10_000) },
        "+30" to { service.seekBy(30_000) },
        "+1分" to { service.seekBy(60_000) }
    )

    private fun actionButton(label: String, round: Boolean, onTap: () -> Unit): TextView =
        TextView(service).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = if (label == "▶") 20f else 16f
            typeface = Typeface.DEFAULT_BOLD
            background = if (round) circle(0xF0263238.toInt(), stroke = true) else rounded(0xFF37474F.toInt(), 10)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onTap()
                updateMenuTexts()
            }
        }

    private fun infoViews(container: LinearLayout) {
        val title = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val time = TextView(service).apply {
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 13f
            setPadding(0, dp(2), 0, 0)
        }
        container.addView(title)
        container.addView(time)
        menuTitle = title
        menuTime = time
    }

    /** パネル型：曲名・時間・ボタン6個を1枚のカードに */
    private fun buildPanel(root: FrameLayout): (FrameLayout) -> Unit {
        val (sw, _) = screenSize()
        val panelWidth = minOf(sw - dp(16), dp(380))
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF01E2A33.toInt(), 16)
            setPadding(dp(10), dp(8), dp(10), dp(10))
            isClickable = true // パネル上のタップでは閉じない
        }
        infoViews(panel)
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        actions().forEachIndexed { i, (label, onTap) ->
            val b = actionButton(label, round = false, onTap = onTap)
            if (i == 2) menuPlay = b
            row.addView(b, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginStart = dp(2); marginEnd = dp(2)
            })
        }
        panel.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        root.addView(panel, FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT))

        return { r ->
            val (cx, cy) = bubbleCenterIn(r)
            val w = r.width.toFloat()
            val h = r.height.toFloat()
            val pw = panel.width.toFloat()
            val ph = panel.height.toFloat()
            val m = dp(8).toFloat()
            val x = (cx - pw / 2).coerceIn(m, maxOf(m, w - pw - m))
            val yRaw = if (cy > h / 2) cy - bubbleSize / 2f - m - ph else cy + bubbleSize / 2f + m
            panel.translationX = x
            panel.translationY = yRaw.coerceIn(m, maxOf(m, h - ph - m))
        }
    }

    /** サークル型：丸ボタンの周りに半円状にボタンを並べる */
    private fun buildCircle(root: FrameLayout): (FrameLayout) -> Unit {
        val size = dp(58)
        val buttons = actions().mapIndexed { i, (label, onTap) ->
            actionButton(label, round = true, onTap = onTap).also { if (i == 2) menuPlay = it }
        }
        buttons.forEach { root.addView(it, FrameLayout.LayoutParams(size, size)) }
        val close = actionButton("✕", round = true) { closeMenu() }
        root.addView(close, FrameLayout.LayoutParams(size, size))

        val (sw, _) = screenSize()
        val cardWidth = minOf(sw - dp(32), dp(320))
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF01E2A33.toInt(), 14)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
        }
        infoViews(card)
        root.addView(card, FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT))

        return { r ->
            val (cx, cyRaw) = bubbleCenterIn(r)
            val w = r.width.toFloat()
            val h = r.height.toFloat()
            val half = size / 2f
            val radius = dp(108).toFloat()
            val m = dp(6).toFloat()
            // 上下の端でも円が収まるように中心を少しずらす
            val minC = radius + half + m
            val cy = if (h > 2 * minC) cyRaw.coerceIn(minC, h - minC) else h / 2
            val cx2 = cx.coerceIn(half + m, maxOf(half + m, w - half - m))
            val leftSide = cx < w / 2
            // 左端なら右向き、右端なら左向きの半円。上から順に -30 → +1分
            var angles = (0 until 6).map { -90.0 + it * 36.0 }
            if (!leftSide) angles = angles.map { 180.0 - it }
            buttons.forEachIndexed { i, b ->
                val rad = Math.toRadians(angles[i])
                val x = (cx2 + radius * cos(rad).toFloat()).coerceIn(half + m, w - half - m)
                val y = (cy + radius * sin(rad).toFloat()).coerceIn(half + m, h - half - m)
                b.translationX = x - half
                b.translationY = y - half
            }
            close.translationX = cx2 - half
            close.translationY = cy - half

            val cw = card.width.toFloat()
            val ch = card.height.toFloat()
            val cardX = (w - cw) / 2
            val cardY = if (cy > h / 2) cy - radius - half - dp(16) - ch else cy + radius + half + dp(16)
            card.translationX = cardX
            card.translationY = cardY.coerceIn(m, maxOf(m, h - ch - m))
        }
    }

    private fun updateMenuTexts() {
        if (menu == null) return
        val p = service.player
        menuTitle?.text = service.currentTitle() ?: ""
        menuTime?.text = "${formatTime(p.currentPosition)} / ${formatTime(p.duration)}"
        menuPlay?.text = if (p.isPlaying) "❚❚" else "▶"
    }

    // ---- 補助 ----

    private fun bubbleCenterIn(root: View): Pair<Float, Float> {
        val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        val b = bubble
        return if (b != null) {
            val bLoc = IntArray(2)
            b.getLocationOnScreen(bLoc)
            Pair(
                (bLoc[0] - rootLoc[0] + bubbleSize / 2).toFloat(),
                (bLoc[1] - rootLoc[1] + bubbleSize / 2).toFloat()
            )
        } else {
            Pair(root.width / 2f, root.height / 2f)
        }
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = service.resources.displayMetrics
            Pair(dm.widthPixels, dm.heightPixels)
        }

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).roundToInt()

    private fun circle(color: Int, stroke: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (stroke) setStroke(dp(2), 0x88FFFFFF.toInt())
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun safeUpdate(v: View, lp: WindowManager.LayoutParams) {
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) { }
    }

    private fun safeRemove(v: View) {
        try { wm.removeView(v) } catch (_: Exception) { }
    }
}
