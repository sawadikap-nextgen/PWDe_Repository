package com.pwde.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Every finger the session keeps on the screen, in **one** gesture chain.
 *
 * Android cancels any gesture already in progress as soon as a new one is dispatched, so a finger can
 * only *stay* down — the game's movement joystick above all — if every finger travels in the same
 * gesture as it. Each [SEGMENT_MS] this class dispatches exactly one [GestureDescription] carrying a
 * continuation of every finger that is still down, and it is the single owner of gesture dispatch for
 * the session, so nothing can slip a gesture in between and cancel the chain.
 *
 * A finger is described by what it does rather than by a stroke:
 *  - [endAfterMs] `null` — stays down until [release] (the joystick, the head drag);
 *  - a short one — a tap, down and up inside a single stroke;
 *  - a long one — a touch & hold;
 *  - a [target] that moves with the elapsed time — a scroll.
 *
 * Which fingers go into which segment is decided by [TouchPlan]; the rules there are the ones that
 * took real device testing to get right, and they are unit-tested.
 *
 * The invariant that keeps the chain legal: a finger leaves [down] only in the segment that ends its
 * stroke, because a stroke dispatched with `willContinue` must be continued by the very next gesture.
 */
class MultiTouchGestures(
    private val service: AccessibilityService,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    /** One finger. [target] receives the milliseconds since it went down. */
    internal class Finger(
        val name: String,
        var target: (elapsedMs: Long) -> PointF,
        var endAfterMs: Long?,
        var onEnd: (completed: Boolean) -> Unit,
        var onCancelled: () -> Unit,
    ) {
        /** The stroke the next segment must continue; null once this finger is no longer down. */
        var stroke: GestureDescription.StrokeDescription? = null
        var point = PointF()
        var pressedAt = 0L
        var releasing = false
        var attempts = 0
        val transient: Boolean get() = endAfterMs != null
    }

    /** Fingers that are down, each with the open stroke the next segment has to continue. */
    private val down = LinkedHashMap<String, Finger>()

    /** Fingers asked for while a segment was in flight; they go down in the next one. */
    private val waiting = LinkedHashMap<String, Finger>()

    /** One dispatched segment: what it carried, and whether it has already been accounted for. */
    private class Segment(val ended: List<Finger>) {
        var settled = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = Runnable { onWatchdog() }
    private var segment: Segment? = null

    private var inFlight = false

    /**
     * True when a finger can go down right now. False while a segment is in flight, because a new
     * gesture would cancel it — a caller that wants to press something tries again next frame.
     */
    val ready: Boolean get() = !inFlight

    /**
     * True when no finger is down, waiting or in flight. A gesture dispatched from outside the chain
     * (the movement stick) cancels whatever is in flight, so it must wait for this.
     */
    val idle: Boolean get() = !inFlight && down.isEmpty() && waiting.isEmpty()

    /** True when [name] is down, or is about to go down in the segment being built. */
    fun isDown(name: String): Boolean = down.containsKey(name) || waiting.containsKey(name)

    /**
     * Put [name] down at [startAt]; [target] says where it should be at each segment.
     *
     * A finger that is already down is re-aimed instead of re-pressed: letting the old stroke end
     * without a continuation would break the chain.
     */
    fun touch(
        name: String,
        startAt: PointF,
        target: (elapsedMs: Long) -> PointF,
        endAfterMs: Long? = null,
        onEnd: (completed: Boolean) -> Unit = {},
        onCancelled: () -> Unit = {},
    ) {
        val existing = down[name] ?: waiting[name]
        if (existing != null) {
            existing.target = target
            existing.endAfterMs = endAfterMs
            existing.onEnd = onEnd
            existing.onCancelled = onCancelled
            existing.releasing = false
            existing.attempts = 0
            Log.d(TAG, "$name re-aimed while down")
        } else {
            waiting[name] = Finger(name, target, endAfterMs, onEnd, onCancelled).apply {
                point = PointF(startAt.x, startAt.y)
            }
        }
        pump()
    }

    /**
     * A tap that must have the screen to itself, which is the only shape a game acts on while it is
     * holding the movement joystick: the fingers that are down are let go, and this one goes down
     * alone — as pointer 0 in an otherwise empty gesture, exactly like a tap in cursor mode.
     *
     * Verified on device (MLBB, 2026-09-26): a tap riding along with a held finger never reaches the
     * game, and a gesture that lifts one finger while pressing another is refused outright. The lift
     * is a normal release, so the game sees a clean `ACTION_UP` rather than a cancel. The joystick
     * puts itself back down on the next frame.
     */
    fun touchAlone(
        name: String,
        startAt: PointF,
        endAfterMs: Long,
        onEnd: (completed: Boolean) -> Unit = {},
        onCancelled: () -> Unit = {},
    ) {
        down.values.forEach { it.releasing = true }
        touch(name, startAt, { startAt }, endAfterMs, onEnd, onCancelled)
    }

    /** Lift [name] after one last move. No-op when it isn't down. */
    fun release(name: String) {
        waiting.remove(name)
        down[name]?.releasing = true
    }

    /** Let go of everything, without waiting: the session ended, or the service is shutting down. */
    fun cancelAll() {
        waiting.clear()
        down.clear()
    }

    // ---- The chain ----

    private fun pump() {
        if (inFlight) return
        val downNow = down.values.map { finger ->
            TouchPlan.Down(
                name = finger.name,
                releasing = finger.releasing,
                expired = finger.endAfterMs?.let { clock() - finger.pressedAt >= it } == true,
            )
        }
        if (!TouchPlan.hasWork(downNow, waiting.keys.toList())) return

        val now = clock()
        val plan = TouchPlan.forSegment(downNow, waiting.keys.toList(), MAX_FINGERS)
        val strokes = mutableListOf<GestureDescription.StrokeDescription>()
        val ended = mutableListOf<Finger>()

        // 1. Move every finger that is already down, lifting the ones that are done. All of them must
        //    be in this gesture: a stroke that said willContinue has to be continued right now.
        for (name in plan.continued + plan.lifted) {
            val finger = down[name] ?: continue
            val previous = finger.stroke ?: continue
            val last = name in plan.lifted
            // A held finger (a drag) that is let go lifts where it is: the release segment standing
            // still is longer than Android's 40 ms "pointer stopped" window, so the lift carries no
            // velocity and a dragged list stays where the user put it instead of flinging on.
            val to = if (last && !finger.transient) finger.point else finger.target(now - finger.pressedAt)
            val path = Path().apply {
                moveTo(finger.point.x, finger.point.y)
                lineTo(to.x, to.y)
            }
            finger.point = to
            val stroke = previous.continueStroke(path, 0, SEGMENT_MS, !last)
            finger.stroke = if (last) null else stroke
            strokes += stroke
            if (last) {
                down.remove(name)
                ended += finger
            }
        }

        // 2. Put the new fingers down. A tap fits in one stroke, so it needs no continuation.
        for (name in plan.start) {
            val finger = waiting.remove(name) ?: continue
            finger.pressedAt = now
            finger.attempts++
            val short = finger.endAfterMs?.let { it <= SEGMENT_MS } == true
            val path = Path().apply { moveTo(finger.point.x, finger.point.y) }
            // (path, startTime, duration, willContinue) — a tap needs no continuation.
            val stroke = GestureDescription.StrokeDescription(
                path,
                0,
                if (short) finger.endAfterMs!! else SEGMENT_MS,
                !short,
            )
            strokes += stroke
            if (short) {
                ended += finger
            } else {
                finger.stroke = stroke
                down[finger.name] = finger
            }
        }

        if (strokes.isEmpty()) return
        dispatch(strokes, ended)
    }

    private fun dispatch(
        strokes: List<GestureDescription.StrokeDescription>,
        ended: List<Finger>,
    ) {
        val gesture = GestureDescription.Builder().apply { strokes.forEach { addStroke(it) } }.build()
        val current = Segment(ended)
        segment = current
        inFlight = true
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = finish(current, true)
            override fun onCancelled(gestureDescription: GestureDescription?) = finish(current, false)
        }
        if (service.dispatchGesture(gesture, callback, null)) {
            Log.d(TAG, "Segment: ${strokes.size} finger(s) down, ${ended.size} lifted, ${waiting.size} waiting")
        } else {
            Log.w(TAG, "Segment of ${strokes.size} finger(s) was rejected")
            finish(current, false)
        }
    }

    /**
     * The answer for one segment arrived — once. A held chain that never hears back would stop PWDe
     * pressing anything at all for the rest of the session, so silence [WATCHDOG_MS] in is treated as
     * a lost segment instead.
     */
    private fun finish(current: Segment, completed: Boolean) {
        if (current.settled) return
        current.settled = true
        handler.removeCallbacks(watchdog)
        if (segment === current) segment = null
        if (!completed) Log.w(TAG, "Segment cancelled by the system — every finger in it was dropped")
        settle(current.ended, completed)
    }

    private fun onWatchdog() {
        val current = segment ?: return
        Log.w(TAG, "No result for a segment within ${WATCHDOG_MS}ms — restarting the chain")
        finish(current, false)
    }

    /**
     * A segment finished. [ended] fingers have just left the screen; a cancelled segment lost every
     * finger it carried, held ones included.
     */
    private fun settle(ended: List<Finger>, completed: Boolean) {
        inFlight = false
        ended.forEach { it.onEnd(completed) }
        if (!completed) recover()
        pump()
    }

    /**
     * The system cancelled the chain, so every finger on the screen is gone: put the short ones back
     * for one more try (a tap that never landed is the same as not pressing), and tell the held ones
     * they are up — the joystick re-presses on the next frame by itself.
     */
    private fun recover() {
        val lost = down.values.toList()
        down.clear()
        for (finger in lost) {
            finger.stroke = null
            val unfinished = !finger.releasing &&
                finger.endAfterMs?.let { clock() - finger.pressedAt < it } == true
            if (unfinished && finger.attempts < MAX_ATTEMPTS) {
                Log.i(TAG, "Retrying ${finger.name} after a cancelled segment")
                waiting[finger.name] = finger
            } else {
                finger.onCancelled()
                if (finger.transient) finger.onEnd(false)
            }
        }
    }

    private companion object {
        const val TAG = "PwdeStroke"

        /** Short enough to follow the head smoothly, long enough not to flood the system. */
        const val SEGMENT_MS = 80L

        /** Stroke limit for one gesture; `GestureDescription` allows 20, this is plenty for a phone. */
        val MAX_FINGERS = GestureDescription.getMaxStrokeCount().coerceAtMost(10)

        /** A cancelled tap gets one more chance; a second failure means the screen is not ours. */
        const val MAX_ATTEMPTS = 2

        /** Every segment is at most [SEGMENT_MS] long, so silence this long means the result is lost. */
        const val WATCHDOG_MS = 500L
    }
}
