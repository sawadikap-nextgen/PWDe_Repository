package com.pwde.app.accessibility

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import kotlin.math.abs

private const val PRIMARY = 0xFF9BEEE2.toInt()
private const val WARNING = 0xFFFFC764.toInt()
private const val DARK = 0xCC0E1A18.toInt()
private const val MUTED = 0xFFB8C9C6.toInt()

/**
 * Full-screen, untouchable layer that draws the head pointer over any app. Taps pass straight
 * through it to the app underneath.
 */
class CursorOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = Color.WHITE }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f * density }
    private val location = IntArray(2)

    /** Pointer position in display pixels. */
    private var px = 0f
    private var py = 0f
    private var active = true
    private var dragging = false
    private var ripple = 1f
    private val rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 450
        addUpdateListener { ripple = it.animatedValue as Float; invalidate() }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun update(x: Float, y: Float, active: Boolean, dragging: Boolean, opacity: Float = 1f) {
        px = x
        py = y
        this.active = active
        this.dragging = dragging
        alpha = opacity
        invalidate()
    }

    /** A ring where "select" just tapped. */
    fun flash() = rippleAnimator.start()

    override fun onDraw(canvas: Canvas) {
        // The window may not start at the display's corner (cutouts, bars); draw in display coordinates.
        getLocationOnScreen(location)
        val cx = px - location[0]
        val cy = py - location[1]
        val color = when {
            dragging -> WARNING
            active -> PRIMARY
            else -> Color.GRAY
        }
        if (ripple < 1f) {
            ripplePaint.color = color
            ripplePaint.alpha = ((1f - ripple) * 255).toInt()
            canvas.drawCircle(cx, cy, (24f + 50f * ripple) * density, ripplePaint)
        }
        canvas.drawCircle(cx, cy, 16f * density, shadow)
        fill.color = color
        canvas.drawCircle(cx, cy, 12f * density, fill)
        canvas.drawCircle(cx, cy, 12f * density, ring)
    }
}

/**
 * The small floating bubble: shows cursor or joystick mode and whether PWDe is paused. Drag to
 * move it; active game sessions can tap to pause or resume and long-press to switch mode.
 */
@SuppressLint("ViewConstructor")
class ModeBubbleView(
    context: Context,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onMove: (dx: Int, dy: Int) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val size = (SIZE_DP * density).toInt()
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DARK }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        isFakeBoldText = true
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var label = "Cursor"
    private var paused = false
    private var locked = false
    private var tapEnabled = true
    private var longPressEnabled = true
    private var disabledActionHint: String? = null

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var moved = false

    init {
        isClickable = true
        isLongClickable = true
        updateDescription()
    }

    fun update(
        label: String,
        paused: Boolean,
        tapEnabled: Boolean = true,
        opacity: Float = 1f,
        longPressEnabled: Boolean = true,
        disabledActionHint: String? = null,
        locked: Boolean = false,
    ) {
        if (this.label == label && this.paused == paused && this.locked == locked && this.tapEnabled == tapEnabled &&
            this.longPressEnabled == longPressEnabled && this.disabledActionHint == disabledActionHint && alpha == opacity
        ) return
        this.label = label
        this.paused = paused
        this.locked = locked
        this.tapEnabled = tapEnabled
        this.longPressEnabled = longPressEnabled
        this.disabledActionHint = disabledActionHint
        alpha = opacity
        updateDescription()
        invalidate()
    }

    private fun updateDescription() {
        val tapHint = when {
            !tapEnabled -> disabledActionHint ?: "Tap disabled"
            paused -> "Tap to resume"
            else -> "Tap to pause"
        }
        val longPressHint = if (longPressEnabled) "long-press to switch mode" else "mode switching unavailable"
        contentDescription = "PWDe, $label mode" + (if (paused) ", paused" else "") +
            (if (locked) ", centre locked" else "") + ". $tapHint, $longPressHint."
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(size, size)

    override fun onDraw(canvas: Canvas) {
        val r = size / 2f
        canvas.drawCircle(r, r, r - 2 * density, background)
        border.color = if (paused || locked) WARNING else PRIMARY
        canvas.drawCircle(r, r, r - 3 * density, border)
        val baseline = r - (text.descent() + text.ascent()) / 2
        canvas.drawText(if (paused) "Paused" else if (locked) "Locked" else label, r, baseline, text)
    }

    // Tap and long-press also come through performClick/performLongClick for TalkBack and switch users.
    override fun performClick(): Boolean {
        super.performClick()
        if (tapEnabled) onTap()
        return true
    }

    override fun performLongClick(): Boolean {
        super.performLongClick()
        if (longPressEnabled) onLongPress()
        return true
    }

    @SuppressLint("ClickableViewAccessibility") // performClick/performLongClick are called below
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                lastX = downX; lastY = downY
                downAt = SystemClock.uptimeMillis()
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop)) moved = true
                if (moved) {
                    onMove((event.rawX - lastX).toInt(), (event.rawY - lastY).toInt())
                    lastX = event.rawX; lastY = event.rawY
                }
            }
            MotionEvent.ACTION_UP -> if (!moved) {
                if (SystemClock.uptimeMillis() - downAt >= ViewConfiguration.getLongPressTimeout()) performLongClick() else performClick()
            }
        }
        return true
    }

    companion object {
        const val SIZE_DP = 64
    }
}

/**
 * The caption under the floating bubble: what the in-game speech engine last heard, which engine it
 * is, and which mode the session is in — so it's plain whether buttons go through sherpa-onnx or the
 * platform recognizer, and whether phone navigation is on. Touches pass straight through it.
 */
class SpeechCaptionView(context: Context) : TextView(context) {
    private val density = resources.displayMetrics.density
    private val frame = GradientDrawable().apply {
        setColor(DARK)
        cornerRadius = 12 * density
    }
    private var lastSeq = -1
    private val settle = Runnable { frame.setStroke((1.5f * density).toInt(), MUTED) }

    init {
        background = frame
        frame.setStroke((1.5f * density).toInt(), MUTED)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        val pad = (8 * density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
        maxWidth = (220 * density).toInt()
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        // The session's own feedback already reaches TalkBack; don't read this a second time.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * [heard] is null until the first result. [mode] is the session's game/navigation mode. A new
     * [seq] briefly lights the border: teal for a matched command, amber for speech that matched
     * nothing.
     */
    fun update(model: String?, mode: String, heard: String?, matched: Boolean, seq: Int, opacity: Float = 1f) {
        alpha = opacity
        val said = if (heard == null) "Listening…" else "“$heard”" + if (matched) "" else "  (no match)"
        text = SpannableStringBuilder(said).append("\n")
            .append(listOfNotNull(model ?: "Unknown engine", mode).joinToString(" · "), ForegroundColorSpan(MUTED), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (heard != null && seq != lastSeq) {
            lastSeq = seq
            frame.setStroke((2.5f * density).toInt(), if (matched) PRIMARY else WARNING)
            removeCallbacks(settle)
            postDelayed(settle, HIGHLIGHT_MS)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(settle)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val HIGHLIGHT_MS = 800L
    }
}

/**
 * Debug layer over the real game: every mapped button drawn where PWDe taps it (circle, crosshair
 * on the exact tap point, label), and the movement joystick as a ring at its drag reach. A press
 * flashes its marker so offsets and dropped taps are visible. Never takes touches.
 */
class ButtonMarkersView(context: Context) : View(context) {
    /** One marker, in display pixels. [reach] is set for the movement joystick. */
    data class Marker(val id: Int, val label: String, val x: Float, val y: Float, val reach: Float? = null)

    enum class Outcome(val color: Int) { TAPPED(PRIMARY), WITH_JOYSTICK(WARNING), FAILED(0xFFFF5A5F.toInt()) }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * density }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        isFakeBoldText = true
    }
    private val labelBackground = Paint(Paint.ANTI_ALIAS_FLAG)
    private val location = IntArray(2)
    private var markers: List<Marker> = emptyList()
    private var opacity = 0.6f

    /** Button id → (outcome, when it was pressed). */
    private val flashes = mutableMapOf<Int, Pair<Outcome, Long>>()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun update(markers: List<Marker>, opacity: Float) {
        if (this.markers == markers && this.opacity == opacity) return
        this.markers = markers
        this.opacity = opacity
        invalidate()
    }

    fun flash(buttonId: Int, outcome: Outcome) {
        flashes[buttonId] = outcome to SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(location)
        val now = SystemClock.uptimeMillis()
        val alpha = (opacity * 255).toInt()
        val radius = 26f * density
        for (m in markers) {
            val cx = m.x - location[0]
            val cy = m.y - location[1]
            // A press shows at full strength whatever the opacity, fading out over FLASH_MS.
            val flash = flashes[m.id]?.takeIf { now - it.second < FLASH_MS }
            val flashAlpha = flash?.let { 1f - (now - it.second).toFloat() / FLASH_MS } ?: 0f
            val color = flash?.first?.color ?: Color.WHITE
            m.reach?.let { reach ->
                stroke.color = PRIMARY
                stroke.alpha = alpha
                canvas.drawCircle(cx, cy, reach, stroke)
            }
            fill.color = flash?.first?.color ?: PRIMARY
            fill.alpha = (alpha * 0.35f + 255 * 0.5f * flashAlpha).toInt().coerceAtMost(255)
            canvas.drawCircle(cx, cy, radius, fill)
            stroke.color = color
            stroke.alpha = maxOf(alpha, (255 * flashAlpha).toInt())
            canvas.drawCircle(cx, cy, radius, stroke)
            cross.color = Color.WHITE
            cross.alpha = maxOf(alpha, (255 * flashAlpha).toInt())
            val arm = 8f * density
            canvas.drawLine(cx - arm, cy, cx + arm, cy, cross)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, cross)
            val baseline = cy + radius + label.textSize + 2 * density
            val width = label.measureText(m.label) / 2 + 4 * density
            labelBackground.color = Color.BLACK
            labelBackground.alpha = (alpha * 0.6f).toInt()
            canvas.drawRect(cx - width, baseline - label.textSize, cx + width, baseline + 4 * density, labelBackground)
            label.color = Color.WHITE
            label.alpha = maxOf(alpha, (255 * flashAlpha).toInt())
            canvas.drawText(m.label, cx, baseline, label)
        }
        flashes.entries.removeAll { now - it.value.second >= FLASH_MS }
        if (flashes.isNotEmpty()) postInvalidateOnAnimation()
    }

    private companion object {
        const val FLASH_MS = 700L
    }
}
