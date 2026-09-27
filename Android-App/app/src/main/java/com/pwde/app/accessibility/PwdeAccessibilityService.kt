package com.pwde.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.pwde.app.PwdeApplication
import com.pwde.app.data.model.ControlConfig
import com.pwde.app.data.model.FaceOutputMode
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.data.model.NavigationMode
import com.pwde.app.data.prefs.ButtonOverlay
import com.pwde.app.data.prefs.CalibrationOverlay
import com.pwde.app.data.prefs.InputMode
import com.pwde.app.data.model.TriggerType
import com.pwde.app.play.GameCommand
import com.pwde.app.play.GameInput
import com.pwde.app.play.LivePlay
import com.pwde.app.play.LivePlayState
import com.pwde.app.play.ScrollDirection
import com.pwde.app.play.hasJoystickConfig
import com.pwde.app.play.navigationMode
import com.pwde.app.sensors.face.FaceState
import com.pwde.app.sensors.face.JoystickDirection
import com.pwde.app.sensors.face.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The "PWDe" entry in Android Settings → Accessibility (its switch reads "Use PWDe"). While a live
 * session runs over the real game ([LivePlay]), it draws the head pointer and the mode bubble over
 * any app and performs the session's screen actions: taps on mapped buttons, select, touch & hold,
 * scroll and drag at the pointer, and Back / Home / Recents / Notifications / All apps. It never
 * reads what's on screen.
 */
class PwdeAccessibilityService : AccessibilityService() {
    private var scope: CoroutineScope? = null
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var cursorView: CursorOverlayView? = null
    private var bubbleView: ModeBubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var captionView: SpeechCaptionView? = null
    private var captionParams: WindowManager.LayoutParams? = null
    private var markersView: ButtonMarkersView? = null

    /**
     * Every finger PWDe puts on the screen — the movement stick, the head drag, taps and scrolls —
     * in one gesture chain, so a button can be pressed while the stick stays held.
     */
    private val gestures by lazy { MultiTouchGestures(this) }

    /** Names the tap and scroll fingers, so each one is its own finger on screen. */
    private var tapSeq = 0
    private var scrollSeq = 0

    /**
     * Decides whether the stick's finger should press, move or lift this frame. Ported from the
     * reference implementation: the anti-jitter is that a target which has not really moved produces
     * no stroke at all, which damping can only approximate.
     */
    private val stickMachine = JoystickGestureMachine(STICK_RELEASE_GRACE_MS, STROKE_MIN_INTERVAL_MS)

    override fun onServiceConnected() {
        super.onServiceConnected()
        val container = (application as PwdeApplication).container
        val livePlay = container.livePlay
        val faceTracking = container.faceTrackingManager
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch { livePlay.actions.collect { perform(it, livePlay) } }
            s.launch { livePlay.state.collect { render(it, livePlay) } }
            s.launch {
                faceTracking.state.collect { face ->
                    if (!livePlay.state.value.active) {
                        livePlay.update { current -> if (current.active) current else current.copy(face = face) }
                    }
                }
            }
            s.launch {
                faceTracking.gestureEvents.collect { gesture ->
                    if (!livePlay.state.value.active) handleIdleGesture(gesture, livePlay, faceTracking, container.controlsRepository)
                }
            }
            val overlayPrefs = (application as PwdeApplication).container.buttonOverlayPrefs
            s.launch { combine(livePlay.state, overlayPrefs.overlay, ::Pair).collect { (state, overlay) -> renderMarkers(state, overlay) } }
            // Entering or leaving a calibration screen re-renders at once, rather than waiting for
            // the next tracking frame, so the pointer is confined (or gone) the moment the box appears.
            s.launch { container.calibrationOverlay.overlay.collect { render(livePlay.state.value, livePlay) } }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    private fun shutDown() {
        scope?.cancel()
        scope = null
        gestures.cancelAll()
        removeOverlays()
    }

    // ---- Overlays ----

    private fun render(state: LivePlayState, livePlay: LivePlay) {
        if (!state.active) {
            gestures.cancelAll()
            renderIdleCursor(
                (application as PwdeApplication).container.faceTrackingManager.state.value,
                livePlay,
            )
            return
        }
        val face = state.face
        val gameMode = state.navigationMode() == NavigationMode.GAME
        val overlayOpacity = if (gameMode) GAME_MODE_OVERLAY_OPACITY else 1f
        // Joystick mode/overlay only appears if the currently opened app has a joystick configuration
        val joystick = face.outputMode == FaceOutputMode.JOYSTICK && state.hasJoystickConfig()

        // If in Joystick mode but current app lacks joystick configuration, automatically revert to CURSOR mode
        if (face.outputMode == FaceOutputMode.JOYSTICK && !state.hasJoystickConfig()) {
            scope?.launch {
                (application as PwdeApplication).container.settingsRepository.setInputMode(InputMode.HEAD_FACE)
            }
        }

        steerStick(state)
        if (joystick) {
            removeView(cursorView)
            cursorView = null
        } else {
            renderCursor(
                face,
                active = face.hasFace && !state.paused,
                dragging = state.dragging,
                opacity = if (gameMode) 0.78f else 1f,
            )
        }
        if (state.overlayHidden) {
            removeView(bubbleView)
            bubbleView = null
            removeView(captionView)
            captionView = null
        } else {
            val view = bubbleView ?: createBubble(livePlay)
            view?.update(
                if (joystick) "Joystick" else "Cursor",
                state.paused,
                tapEnabled = !gameMode || state.paused,
                opacity = overlayOpacity,
                disabledActionHint = if (gameMode && !state.paused) "Tap disabled in game mode" else null,
            )
            val heard = state.heard
            (captionView ?: createCaption())?.update(
                state.voiceModel,
                "${state.navigationMode().label} mode",
                heard?.text,
                heard?.matched == true,
                heard?.seq ?: 0,
                overlayOpacity,
            )
        }
    }

    private fun renderIdleCursor(face: FaceState, livePlay: LivePlay) {
        removeView(captionView)
        captionView = null
        (bubbleView ?: createBubble(livePlay))?.update(
            if (face.outputMode == FaceOutputMode.JOYSTICK) "Joystick" else "Cursor",
            paused = false,
            tapEnabled = false,
            longPressEnabled = false,
            disabledActionHint = "No game session",
        )
        if (face.status == TrackingStatus.Idle || face.outputMode == FaceOutputMode.JOYSTICK) {
            removeView(cursorView)
            cursorView = null
            return
        }
        renderCursor(face, active = face.hasFace, dragging = false)
    }

    private suspend fun handleIdleGesture(
        gesture: FacialGesture,
        livePlay: LivePlay,
        faceTracking: com.pwde.app.sensors.face.FaceTrackingManager,
        controlsRepository: com.pwde.app.data.local.ControlsRepository,
    ) {
        val config = controlsRepository.config.first()
        val state = livePlay.state.value
        if (state.active) return
        val command = GameInput.fromGesture(gesture, emptyList(), config)
        GameInput.navigationRefusal(command, state.navigationMode())?.let {
            Log.i(TAG, "Ignored idle gesture ${gesture.label}: $it")
            return
        }
        when (command) {
            GameCommand.Recenter -> faceTracking.recenterCursor()
            GameCommand.Pause, GameCommand.Resume, GameCommand.TogglePause -> Unit
            else -> perform(command, livePlay)
        }
    }

    /** The pointer's dot, in display pixels, and the pad it is confined to while calibrating. */
    private fun renderCursor(face: FaceState, active: Boolean, dragging: Boolean, opacity: Float = 1f) {
        val calibration = (application as PwdeApplication).container.calibrationOverlay.overlay.value
        if (calibration is CalibrationOverlay.Hidden) {
            removeView(cursorView)
            cursorView = null
            return
        }
        val box = (calibration as? CalibrationOverlay.Confine)?.box
        val view = cursorView ?: CursorOverlayView(this).takeIf { addOverlay(it, cursorParams()) }?.also { cursorView = it }
        val point = toScreen(face.cursor.x, face.cursor.y)
        if (box == null) {
            // Normal: the pointer roams the whole display.
            view?.update(point.x, point.y, active = active, dragging = dragging, opacity = opacity)
            return
        }
        // Calibrating: the whole dot stays inside the box the user is aiming at, so the pointer
        // never wanders off to a corner of the screen the calibration is not talking about.
        val inset = CursorOverlayView.DOT_RADIUS_DP * resources.displayMetrics.density
        val (x, y) = PointerBounds.confine(point.x, point.y, box, inset)
        view?.update(x, y, active = active, dragging = dragging, opacity = opacity)
    }

    /**
     * The mapped buttons where PWDe taps them: while the Testing Station's switch is on, and for a
     * few seconds after "show controls", when each label also says what presses the button.
     */
    private fun renderMarkers(state: LivePlayState, overlay: ButtonOverlay) {
        if (!state.active || !(overlay.shown || state.controlsShown) || state.buttons.isEmpty()) {
            removeView(markersView)
            markersView = null
            return
        }
        val view = markersView ?: ButtonMarkersView(this).takeIf { addOverlay(it, cursorParams()) }?.also { markersView = it }
        val (width, height) = displaySize()
        val reach = state.face.joystick.radius * minOf(width, height)
        view?.update(
            state.buttons.map { b ->
                val p = toScreen(b.x, b.y)
                val label = if (state.controlsShown) "${b.label} · ${b.trigger?.shortLabel() ?: "not mapped"}" else b.label
                ButtonMarkersView.Marker(b.id, label, p.x, p.y, reach.takeIf { b.trigger?.type == TriggerType.MOVEMENT })
            },
            (if (state.controlsShown) maxOf(overlay.opacity, CONTROLS_OPACITY) else overlay.opacity)
                .let { if (state.navigationMode() == NavigationMode.GAME) minOf(it, GAME_MODE_OVERLAY_OPACITY) else it },
        )
    }

    private fun createBubble(livePlay: LivePlay): ModeBubbleView? {
        val params = bubbleParams ?: bubbleLayoutParams().also { bubbleParams = it }
        val view = ModeBubbleView(
            this,
            onTap = {
                val state = livePlay.state.value
                if (state.navigationMode() != NavigationMode.GAME || state.paused) livePlay.request(GameCommand.TogglePause)
            },
            onLongPress = {
                val currentState = livePlay.state.value
                val joystick = currentState.face.outputMode == FaceOutputMode.JOYSTICK
                if (!joystick && !currentState.hasJoystickConfig()) {
                    livePlay.update { it.copy(message = "Joystick mode requires a joystick configuration for this app") }
                } else {
                    livePlay.request(if (joystick) GameCommand.CursorMode else GameCommand.JoystickMode)
                }
            },
            onMove = { dx, dy ->
                params.x += dx
                params.y += dy
                bubbleView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                // The caption rides along under the bubble.
                captionParams?.let { caption ->
                    placeCaption(caption, params)
                    captionView?.let { runCatching { windowManager.updateViewLayout(it, caption) } }
                }
            },
        )
        if (!addOverlay(view, params)) return null
        bubbleView = view
        return view
    }

    private fun createCaption(): SpeechCaptionView? {
        val bubble = bubbleParams ?: return null
        val params = captionParams ?: captionLayoutParams().also { captionParams = it }
        placeCaption(params, bubble)
        val view = SpeechCaptionView(this)
        if (!addOverlay(view, params)) return null
        captionView = view
        return view
    }

    /** Just below the bubble, left edges aligned. */
    private fun placeCaption(caption: WindowManager.LayoutParams, bubble: WindowManager.LayoutParams) {
        caption.x = bubble.x
        caption.y = bubble.y + ((ModeBubbleView.SIZE_DP + CAPTION_GAP_DP) * resources.displayMetrics.density).toInt()
    }

    private fun addOverlay(view: View, params: WindowManager.LayoutParams): Boolean =
        runCatching { windowManager.addView(view, params) }
            .onFailure { Log.w(TAG, "Couldn't show the overlay", it) }
            .isSuccess

    private fun removeView(view: View?) {
        if (view != null) runCatching { windowManager.removeView(view) }
    }

    private fun removeOverlays() {
        removeView(markersView)
        markersView = null
        removeView(cursorView)
        removeView(bubbleView)
        removeView(captionView)
        cursorView = null
        bubbleView = null
        captionView = null
    }

    /** Covers the whole display, cutouts included, and never takes touches. */
    private fun cursorParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    /** Starts at the top-left, below the status bar; the user can drag it anywhere. */
    private fun bubbleLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        val density = resources.displayMetrics.density
        x = (16 * density).toInt()
        y = (96 * density).toInt()
    }

    /** Sized to its text and never takes touches, so the game under it stays tappable. */
    private fun captionLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var currentForegroundPackage: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != currentForegroundPackage) {
                currentForegroundPackage = pkg
                onForegroundPackageChanged(pkg)
            }
        }
    }

    private fun onForegroundPackageChanged(pkg: String) {
        val container = (application as PwdeApplication).container
        val liveState = container.livePlay.state.value
        val gamePkg = liveState.game?.packageName
        val appPkg = packageName

        if (gamePkg != null && pkg != gamePkg && pkg != appPkg) {
            // App was closed or switched away from:
            // 1. Hide button overlay markers so they do not persist
            removeView(markersView)
            markersView = null
            container.livePlay.update { it.copy(controlsShown = false) }

            // 2. Automatically switch navigation mode back to cursor mode
            scope?.launch {
                container.settingsRepository.setInputMode(InputMode.HEAD_FACE)
            }
        }
    }

    override fun onInterrupt() = Unit

    // ---- Movement joystick ----

    /**
     * In joystick mode, holds the game's movement joystick (the button marked "Movement") and drags
     * it the way the head or the phone points; lets go once the stick really is back in the dead zone.
     *
     * A momentary return to the dead zone must not lift the finger. Lifting it is an `ACTION_UP` the
     * game acts on and re-pressing is an `ACTION_DOWN`, so one noisy reading — hand tremor on a phone,
     * or a value crossing the engage threshold — made the stick drop back to the middle and jerk out
     * again. The finger is therefore held at its last real deflection for [STICK_RELEASE_GRACE_MS] and
     * only a stick that *stays* centered is let go.
     */
    private fun steerStick(state: LivePlayState) {
        val face = state.face
        val movement = state.buttons.firstOrNull { it.trigger?.type == TriggerType.MOVEMENT }
        val holding = movement != null && face.outputMode == FaceOutputMode.JOYSTICK && face.hasFace && !state.paused
        if (!holding) {
            releaseStick()
            return
        }
        val (width, height) = displaySize()
        val base = toScreen(movement.x, movement.y)
        // The Size setting is the stick's travel: the same radius the preview and the marker draw.
        val reach = face.joystick.radius * minOf(width, height)
        val target = PointF(
            (base.x + face.joystick.x * reach).coerceIn(1f, width - 2f),
            (base.y + face.joystick.y * reach).coerceIn(1f, height - 2f),
        )
        // Only move the finger when the machine says this frame is worth a stroke. A held stick whose
        // target has not moved by MOVE_STEP of its own travel gets nothing, so tremor never reaches
        // the game — something the chain could not do by itself, because it had to keep the finger
        // alive every segment. The aim only changes when the machine reports a real move, so a
        // momentary centered reading can no longer snap the finger back to the middle.
        val step = stickMachine.update(
            SystemClock.uptimeMillis(),
            face.joystick.direction != JoystickDirection.CENTER,
            base.x,
            base.y,
            target.x,
            target.y,
            maxOf(MIN_MOVE_PX, MOVE_STEP * reach),
        ) ?: return
        dispatchStick(step)
    }

    /**
     * One stick stroke, built the way the reference implementation builds it: a fresh
     * [GestureDescription] sent straight to the framework, with no callback and no chain.
     *
     * The machine's phase is the only record of the held finger, so a stroke the framework refuses
     * costs nothing — the next movement sends another one. Routing the stick through the shared
     * gesture chain instead put a callback and an in-flight gate in the way, and a third of those
     * strokes were refused, each refusal dropping the held finger with them.
     */
    private fun dispatchStick(step: JoystickGestureMachine.Step) {
        val release = step.kind == JoystickGestureMachine.Kind.RELEASE
        val path = Path().apply {
            moveTo(step.fromX, step.fromY)
            // The release stroke is stationary: it completes the continued gesture and lifts in place.
            if (!release) lineTo(step.toX, step.toY)
        }
        val durationMs = if (release) {
            STICK_RELEASE_DURATION_MS
        } else {
            val dx = step.toX - step.fromX
            val dy = step.toY - step.fromY
            // Longer strokes for longer moves, bounded so the finger still tracks promptly.
            (kotlin.math.sqrt(dx * dx + dy * dy) * STROKE_MS_PER_PX)
                .coerceIn(STROKE_MIN_DURATION_MS, STROKE_MAX_DURATION_MS)
                .toLong()
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs, !release))
            .build()
        if (dispatchGesture(gesture, null, null)) {
            Log.d(TAG, "stick ${step.kind} ${durationMs}ms (${step.fromX.toInt()},${step.fromY.toInt()})->(${step.toX.toInt()},${step.toY.toInt()})")
        } else {
            Log.w(TAG, "The framework refused the ${step.kind} stick stroke — the next movement resends")
        }
    }

    /** Let go of the movement stick at once: a pause, a mode change, or a tap that needs the screen. */
    private fun releaseStick() {
        stickMachine.forceRelease()?.let(::dispatchStick)
    }

    // ---- Actions ----

    private fun perform(command: GameCommand, livePlay: LivePlay) {
        val cursor = livePlay.state.value.face.cursor
        val pointer = toScreen(cursor.x, cursor.y)
        Log.i(TAG, "Perform $command")
        when (command) {
            // Button positions are fractions of a full-screen screenshot from this phone.
            is GameCommand.Press -> tap(toScreen(command.button.x, command.button.y), TAP_MS) { outcome ->
                markersView?.flash(command.button.id, outcome)
            }
            GameCommand.Select -> {
                tap(pointer, TAP_MS)
                cursorView?.flash()
            }
            GameCommand.TouchHold -> tap(pointer, HOLD_MS)
            is GameCommand.Scroll -> swipe(pointer, command.direction)
            GameCommand.StartDrag -> {
                gestures.touch(
                    name = DRAG,
                    startAt = pointer,
                    target = { livePlay.state.value.face.cursor.let { toScreen(it.x, it.y) } },
                    onCancelled = { livePlay.request(GameCommand.Drop) },
                )
            }
            GameCommand.Drop -> gestures.release(DRAG)
            GameCommand.Recents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            GameCommand.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            GameCommand.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            GameCommand.Notifications -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            GameCommand.AllApps -> performGlobalAction(
                if (Build.VERSION.SDK_INT >= 31) GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS else GLOBAL_ACTION_RECENTS,
            )
            // Everything else is handled by the session itself.
            else -> Unit
        }
    }

    /**
     * A tap, or a touch & hold, as a finger of its own. It never shares the screen with the held
     * movement stick: a game ignores a button tap that arrives as a second pointer on top of the
     * stick, but the same tap works on its own (verified on device, MLBB). The stick therefore lets
     * go for the moment the tap takes and grabs the joystick again by itself.
     * [onResult] says how it ended (for the button markers).
     */
    private fun tap(point: PointF, durationMs: Long, onResult: (ButtonMarkersView.Outcome) -> Unit = {}) {
        val sharing = gestures.isDown(STICK) || gestures.isDown(DRAG) || stickMachine.isPressed()
        // The stick's finger has to be gone before the tap's gesture goes out: a new gesture cancels
        // whatever is in flight, and a tap riding along with the stick never reaches the game.
        releaseStick()
        val name = "tap-${tapSeq++}"
        Log.i(
            TAG,
            "-> $name at (${point.x.toInt()}, ${point.y.toInt()}) for ${durationMs}ms" +
                if (sharing) " (nothing else may be on screen: the stick is let go first)" else " (on its own)",
        )
        gestures.touchAlone(
            name = name,
            startAt = point,
            endAfterMs = durationMs,
            onEnd = { completed ->
                Log.i(TAG, "$name " + if (completed) "completed" else "DID NOT complete — the system dropped it")
                onResult(
                    when {
                        !completed -> ButtonMarkersView.Outcome.FAILED
                        // Still worth showing: this press happened while the stick was in use.
                        sharing -> ButtonMarkersView.Outcome.WITH_JOYSTICK
                        else -> ButtonMarkersView.Outcome.TAPPED
                    },
                )
            },
        )
    }

    /** Moves the content under the pointer so it scrolls the way [direction] reads. */
    private fun swipe(center: PointF, direction: ScrollDirection) {
        val (width, height) = displaySize()
        val travel = ScrollSwipe.travel(center.x, center.y, direction, width, height)
        gestures.touch(
            name = "scroll-${scrollSeq++}",
            startAt = PointF(travel.fromX, travel.fromY),
            // The finger keeps travelling for the whole swipe, which is what the game reads as a fling.
            target = { elapsed ->
                val (x, y) = ScrollSwipe.pointAt(travel, elapsed, SCROLL_MS)
                PointF(x, y)
            },
            endAfterMs = SCROLL_MS,
        )
    }

    /** A 0–1 position to pixels on the whole display, as it's rotated right now. */
    private fun toScreen(x: Float, y: Float): PointF {
        val (width, height) = displaySize()
        val (px, py) = ScreenMapping.toPixels(x, y, width, height)
        return PointF(px, py)
    }

    private fun displaySize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    companion object {
        private const val TAG = "PwdeAccessibility"
        private const val TAP_MS = 60L
        private const val HOLD_MS = 700L
        private const val SCROLL_MS = 300L
        private const val CAPTION_GAP_DP = 6
        private const val GAME_MODE_OVERLAY_OPACITY = 0.58f
        /** "show controls" labels stay readable even when the debug overlay is set faint. */
        private const val CONTROLS_OPACITY = 0.9f

        /** The finger holding the game's movement joystick while the head joystick is deflected. */
        private const val STICK = "stick"

        /** How long the stick may read as centered before the finger is lifted (the machine's grace). */
        private const val STICK_RELEASE_GRACE_MS = 150f

        /** Minimum spacing between two stick strokes, so a fast move cannot flood the queue. */
        private const val STROKE_MIN_INTERVAL_MS = 30L

        /** Smallest target movement, in pixels, that justifies moving the stick's finger. */
        private const val MIN_MOVE_PX = 2f

        /**
         * ... and the same as a share of the stick's own travel, so the threshold scales with the
         * Size setting: a movement smaller than this is tremor, and moving the finger for it is what
         * made the stick jitter.
         */
        private const val MOVE_STEP = 0.10f

        /** How long the final stationary release stroke holds the finger before lifting it. */
        private const val STICK_RELEASE_DURATION_MS = 40L

        /** Drag speed: longer strokes for longer moves, bounded so the finger tracks promptly. */
        private const val STROKE_MS_PER_PX = 0.6f
        private const val STROKE_MIN_DURATION_MS = 25f
        private const val STROKE_MAX_DURATION_MS = 120f

        /** The finger held at the pointer by "drag". */
        private const val DRAG = "drag"

        /** True when the user has switched "Use PWDe" on in Android's accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val self = ComponentName(context, PwdeAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == self }
        }

        /** Opens Android's accessibility settings, where PWDe is listed. */
        fun settingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Normalized (0–1) screen positions to pixels. Pure, so it's unit-tested. */
object ScreenMapping {
    fun toPixels(x: Float, y: Float, width: Int, height: Int): Pair<Float, Float> {
        // Stay a pixel inside the screen: a gesture on the very edge is rejected.
        val px = (x.coerceIn(0f, 1f) * width).coerceIn(0f, (width - 1).toFloat())
        val py = (y.coerceIn(0f, 1f) * height).coerceIn(0f, (height - 1).toFloat())
        return px to py
    }
}

/**
 * Keeps the drawn pointer inside a calibration box. Pure, so it's unit-tested.
 *
 * The dot has a radius, so clamping its *centre* to the box would still draw half the dot outside;
 * [radiusPx] is subtracted on every edge. A box narrower than the dot (a very small feed in
 * Easy-reach layout, or a stale box for one frame) falls back to that box's centre rather than
 * inverting the range.
 */
object PointerBounds {
    fun confine(x: Float, y: Float, box: CalibrationOverlay.Box, radiusPx: Float): Pair<Float, Float> {
        val left = box.left + radiusPx
        val right = box.right - radiusPx
        val top = box.top + radiusPx
        val bottom = box.bottom - radiusPx
        val cx = if (left <= right) x.coerceIn(left, right) else box.left + box.width / 2f
        val cy = if (top <= bottom) y.coerceIn(top, bottom) else box.top + box.height / 2f
        return cx to cy
    }
}
