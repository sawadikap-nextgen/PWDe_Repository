package com.pwde.app.sensors.face

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.pwde.app.data.local.ControlsRepository
import com.pwde.app.data.model.FaceOutputMode
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.data.model.JoystickSource
import com.pwde.app.data.model.faceOutputMode
import com.pwde.app.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Head/face tracking. The camera only runs while someone collects [state] — ViewModels expose it
 * with `WhileSubscribed`, so it stops shortly after the screen showing it leaves the foreground.
 */
interface FaceTrackingManager {
    val state: StateFlow<FaceState>

    /** Gestures that just started and may trigger an action (tilt/nod excluded in joystick mode). */
    val gestureEvents: SharedFlow<FacialGesture>

    /** Live camera preview for the UI, or null while the camera is off. */
    val surfaceRequest: StateFlow<SurfaceRequest?>

    val hasCameraPermission: Boolean

    /** Re-check camera permission and restart tracking (call after the user answers the prompt). */
    fun refreshPermissions()

    fun recenterCursor()

    /** Saves the current head pose as the joystick's (and tilt/nod's) neutral. False if no head is seen. */
    suspend fun captureJoystickCenter(persistToActiveProfile: Boolean = false): Boolean
}

/*
 * Camera → MediaPipe Face Landmarker pipeline modelled on Google Project GameFace's
 * FaceLandmarkerHelper (https://github.com/google/project-gameface, Apache License 2.0):
 * CameraX ImageAnalysis frames, mirrored for the front camera, run through the LIVE_STREAM
 * landmarker with blendshapes. Unlike GameFace there is no AccessibilityService: tracking only
 * drives PWDe's own screens.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MediaPipeFaceTrackingManager(
    context: Context,
    private val controlsRepository: ControlsRepository,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : FaceTrackingManager {
    private val appContext = context.applicationContext
    private val orientation = OrientationHeadTracker(appContext)
    private val gyro = GyroHeadTracker(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val permissionTick = MutableStateFlow(0)
    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    private val _gestureEvents = MutableSharedFlow<FacialGesture>(extraBufferCapacity = 16)

    @Volatile
    private var activeProcessor: FaceFrameProcessor? = null

    private val tuning: Flow<TrackingTuning> =
        combine(controlsRepository.config, settingsRepository.settings) { controls, settings ->
            TrackingTuning(controls, settings.inputMode.faceOutputMode(), settings.joystickSource)
        }

    /**
     * Everything that decides *which* session should be running. The permission tick is part of the
     * key on purpose: answering the camera prompt has to restart tracking even though no setting
     * changed, which a `distinctUntilChanged` over the settings alone would swallow.
     */
    private data class SessionRequest(
        val permissionTick: Int,
        val enabled: Boolean,
        val output: FaceOutputMode,
        val joystickSource: JoystickSource,
    )

    private val sessionRequest: Flow<SessionRequest> =
        combine(permissionTick, settingsRepository.settings) { tick, settings ->
            SessionRequest(
                permissionTick = tick,
                enabled = settings.pwdeEnabled,
                output = settings.inputMode.faceOutputMode(),
                joystickSource = settings.joystickSource,
            )
        }.distinctUntilChanged()

    override val state: StateFlow<FaceState> = sessionRequest
        .flatMapLatest { request ->
            when {
                !request.enabled -> offSession()
                // The one mode that wants no camera at all: a gyro joystick is the phone's own tilt,
                // so steering still works with the phone held anywhere but facing the user.
                request.output == FaceOutputMode.JOYSTICK && request.joystickSource == JoystickSource.GYRO -> gyroSession()
                else -> session()
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(0), FaceState())

    override val gestureEvents: SharedFlow<FacialGesture> = _gestureEvents.asSharedFlow()
    override val surfaceRequest: StateFlow<SurfaceRequest?> = _surfaceRequest.asStateFlow()

    override val hasCameraPermission: Boolean
        get() = ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun refreshPermissions() {
        permissionTick.value++
    }

    override fun recenterCursor() {
        activeProcessor?.recenterCursor()
    }

    override suspend fun captureJoystickCenter(persistToActiveProfile: Boolean): Boolean {
        // Gyro keeps no saved center: its neutral is how the phone is being held, so "center here"
        // re-baselines the sensor instead. A head angle and a phone angle must never share one
        // field, or switching sources would silently offset the stick.
        if (settingsRepository.settings.first().joystickSource == JoystickSource.GYRO) {
            if (!gyro.isAvailable) return false
            gyro.rebaseline()
            return true
        }
        val pose = state.value.pose ?: return false
        controlsRepository.setJoystickCenter(pose.pitch, pose.roll, persistToActiveProfile)
        return true
    }

    /** The home screen's master switch is off: report Idle and keep the camera closed. */
    private fun offSession(): Flow<FaceState> = flowOf(FaceState(status = TrackingStatus.Idle))

    /** One tracking session: the camera when possible, otherwise the labelled motion-sensor demo. */
    private fun session(): Flow<FaceState> = channelFlow {
        if (!hasCameraPermission) {
            forward(simulatedSession("Camera permission is off"))
            return@channelFlow
        }
        val provider = runCatching { ProcessCameraProvider.awaitInstance(appContext) }.getOrNull()
        val hasFront = provider != null &&
            runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
        if (provider == null || !hasFront) {
            forward(simulatedSession("No front camera found"))
            return@channelFlow
        }
        val results = Channel<Pair<FaceLandmarkerResult, Long>>(Channel.CONFLATED)
        val landmarker = createLandmarker(results)
        if (landmarker == null) {
            forward(simulatedSession("The face model couldn't load"))
            return@channelFlow
        }
        forward(cameraSession(provider, landmarker, results))
    }

    private suspend fun ProducerScope<FaceState>.forward(flow: Flow<FaceState>) {
        flow.collect { send(it) }
    }

    private fun createLandmarker(results: Channel<Pair<FaceLandmarkerResult, Long>>): FaceLandmarker? = try {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(MIN_CONFIDENCE)
            .setMinFacePresenceConfidence(MIN_CONFIDENCE)
            .setMinTrackingConfidence(MIN_CONFIDENCE)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener { result, _ -> results.trySend(result to result.timestampMs()) }
            .setErrorListener { e -> Log.w(TAG, "Face landmarker error", e) }
            .build()
        FaceLandmarker.createFromOptions(appContext, options)
    } catch (e: Exception) {
        Log.e(TAG, "Couldn't create the face landmarker", e)
        null
    }

    private fun cameraSession(
        provider: ProcessCameraProvider,
        landmarker: FaceLandmarker,
        results: Channel<Pair<FaceLandmarkerResult, Long>>,
    ): Flow<FaceState> = channelFlow {
        val processor = FaceFrameProcessor().also { activeProcessor = it }
        val tuningState = tuning.stateIn(this)
        val base = FaceState(source = TrackingSource.CAMERA, status = TrackingStatus.Starting)
        send(base)

        val executor = Executors.newSingleThreadExecutor()
        val owner = SessionLifecycleOwner()
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder().setResolutionSelector(resolution).build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        var lastFrameMs = 0L

        // Frames are turned upright for the display's current rotation, so a nod stays a nod when a
        // game switches to landscape. The use cases start with whatever rotation they were built in.
        val displayManager = appContext.getSystemService(DisplayManager::class.java)
        fun syncRotation() {
            val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return
            analysis.targetRotation = rotation
            preview.targetRotation = rotation
        }
        val rotationListener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) syncRotation()
            }

            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }

        // CameraX use-case wiring and binding must happen on the main thread.
        val bound = withContext(Dispatchers.Main) {
            runCatching {
                syncRotation()
                displayManager.registerDisplayListener(rotationListener, mainHandler)
                preview.setSurfaceProvider { request -> _surfaceRequest.value = request }
                analysis.setAnalyzer(executor) { image ->
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFrameMs < FRAME_INTERVAL_MS) {
                        image.close()
                    } else {
                        lastFrameMs = now
                        runCatching { landmarker.detectAsync(BitmapImageBuilder(image.toMirroredBitmap()).build(), now) }
                            .onFailure { Log.w(TAG, "Frame dropped", it) }
                    }
                }
                owner.start()
                // Tracking needs only the analysis stream. The preview is added while a screen shows it:
                // CameraX delivers no frames at all while a bound preview has no surface, which is the
                // case whenever PWDe runs in the background over a game.
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            }.onFailure { Log.e(TAG, "Couldn't open the front camera", it) }.isSuccess
        }

        if (bound) {
            launch(Dispatchers.Main) {
                _surfaceRequest.subscriptionCount
                    .map { it > 0 }
                    .distinctUntilChanged()
                    // Screen changes briefly drop the count to zero; don't restart the preview for that.
                    .debounce { shown -> if (shown) 0L else PREVIEW_RELEASE_DELAY_MS }
                    .collect { shown ->
                        runCatching {
                            provider.unbind(preview)
                            _surfaceRequest.value = null
                            if (shown) provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
                        }.onFailure { Log.w(TAG, "Couldn't ${if (shown) "show" else "hide"} the camera preview", it) }
                    }
            }
            launch(Dispatchers.Default) {
                for ((result, timestamp) in results) {
                    val frameTuning = tuningState.value
                    val next = processor.process(
                        pose = result.headPose(),
                        blendshapes = result.blendshapeScores(),
                        timestampMs = timestamp,
                        tuning = frameTuning,
                        base = base.copy(landmarks = result.landmarkArray(), confidence = result.presence()),
                    )
                    processor.actionableStarts(next, frameTuning.controls).forEach { _gestureEvents.tryEmit(it) }
                    send(next)
                }
            }
        } else {
            launch { simulatedSession("The front camera couldn't open").collect { send(it) } }
        }

        awaitClose {
            results.close()
            if (activeProcessor === processor) activeProcessor = null
            mainHandler.post {
                displayManager.unregisterDisplayListener(rotationListener)
                analysis.clearAnalyzer()
                owner.destroy()
                runCatching { provider.unbind(preview, analysis) }
                _surfaceRequest.value = null
                // Close the landmarker on the analysis thread, after any frame still in flight.
                executor.execute { landmarker.close() }
                executor.shutdown()
            }
        }
    }

    /**
     * The gyro joystick: the phone's own tilt, with the camera left closed. A real control the user
     * chose, so unlike [simulatedSession] it is not labelled as a demo and has no fallback reason.
     */
    private fun gyroSession(): Flow<FaceState> = channelFlow {
        val base = FaceState(source = TrackingSource.GYRO, status = TrackingStatus.Starting)
        if (!gyro.isAvailable) {
            send(base.copy(status = TrackingStatus.Unavailable("This phone has no motion sensor to tilt with")))
            awaitClose()
            return@channelFlow
        }
        val processor = FaceFrameProcessor().also { activeProcessor = it }
        val tuningState = tuning.stateIn(this)
        send(base)
        gyro.poses().collect { (pose, timestamp) ->
            val frameTuning = tuningState.value
            // No blendshapes at all: there is no face in this pipeline, so no face gesture can be
            // read out of tilting the phone.
            val next = processor.process(pose, emptyMap(), timestamp, frameTuning, base)
            processor.actionableStarts(next, frameTuning.controls).forEach { _gestureEvents.tryEmit(it) }
            send(next)
        }
    }

    private fun simulatedSession(reason: String): Flow<FaceState> = channelFlow {
        val base = FaceState(source = TrackingSource.SIMULATED, status = TrackingStatus.Starting, fallbackReason = reason)
        if (!orientation.isAvailable) {
            send(base.copy(status = TrackingStatus.Unavailable("$reason, and this phone has no motion sensor")))
            awaitClose()
            return@channelFlow
        }
        val processor = FaceFrameProcessor().also { activeProcessor = it }
        val tuningState = tuning.stateIn(this)
        send(base)
        orientation.poses().collect { (pose, timestamp) ->
            val frameTuning = tuningState.value
            val next = processor.process(pose, emptyMap(), timestamp, frameTuning, base)
            processor.actionableStarts(next, frameTuning.controls).forEach { _gestureEvents.tryEmit(it) }
            send(next)
        }
    }

    /** Upright, mirrored (selfie) frame, as MediaPipe's own front-camera samples do. */
    private fun ImageProxy.toMirroredBitmap() = use { image ->
        val bitmap = image.toBitmap()
        val matrix = Matrix().apply {
            postRotate(image.imageInfo.rotationDegrees.toFloat())
            postScale(-1f, 1f)
        }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
    }

    private class SessionLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun start() {
            registry.currentState = Lifecycle.State.RESUMED
        }
        fun destroy() {
            if (registry.currentState != Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    companion object {
        private const val TAG = "FaceTracking"
        const val MODEL_ASSET = "face_landmarker.task"
        private const val MIN_CONFIDENCE = 0.5f

        /** ~30 FPS. */
        private const val FRAME_INTERVAL_MS = 33L

        /** How long no screen may show the preview before it's detached. */
        private const val PREVIEW_RELEASE_DELAY_MS = 500L
    }
}

private fun FaceLandmarkerResult.headPose(): HeadPose? =
    facialTransformationMatrixes().orElse(null)?.firstOrNull()?.let(HeadPoseMath::fromTransformationMatrix)

private fun FaceLandmarkerResult.blendshapeScores(): Map<String, Float> =
    faceBlendshapes().orElse(null)?.firstOrNull()?.associate { it.categoryName() to it.score() }.orEmpty()

private fun FaceLandmarkerResult.landmarkArray(): FloatArray? {
    val face = faceLandmarks().firstOrNull() ?: return null
    val out = FloatArray(face.size * 2)
    face.forEachIndexed { i, landmark ->
        out[i * 2] = landmark.x()
        out[i * 2 + 1] = landmark.y()
    }
    return out
}

/** Mean landmark presence, if this model version reports it. */
private fun FaceLandmarkerResult.presence(): Float? {
    val values = faceLandmarks().firstOrNull()?.mapNotNull { it.presence().orElse(null) }.orEmpty()
    return if (values.isEmpty()) null else values.average().toFloat()
}
