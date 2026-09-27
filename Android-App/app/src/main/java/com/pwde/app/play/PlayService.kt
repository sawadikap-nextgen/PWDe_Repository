package com.pwde.app.play

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pwde.app.MainActivity
import com.pwde.app.PwdeApplication
import com.pwde.app.R
import com.pwde.app.data.model.FaceOutputMode
import com.pwde.app.data.model.Game
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps head/face tracking and in-game voice running while the real game is on screen. A
 * foreground service (camera + microphone) with a "PWDe is running" notification offering
 * Pause/Resume, Recenter and Stop. Android only lets it use the camera and mic in the background
 * when it's started while PWDe is open, so it's started from the Play button or "play <game>".
 */
class PlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionJob: Job? = null
    private var session: LiveGameSession? = null
    private var notificationJob: Job? = null

    private val container get() = (application as PwdeApplication).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val game = Game.byId(intent.getStringExtra(EXTRA_GAME))
                if (game == null || !goForeground(game)) {
                    stopSelf()
                } else {
                    start(game, intent.getLongExtra(EXTRA_PROFILE, -1L).takeIf { it >= 0 })
                }
            }
            ACTION_TOGGLE_PAUSE -> session?.togglePause()
            ACTION_RECENTER -> session?.recenter()
            ACTION_STOP -> stopSession()
            // Restarted by the system without a session to resume.
            else -> if (session == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** False when neither the camera nor the mic may be used, so there's nothing to run. */
    private fun goForeground(game: Game): Boolean {
        val types = foregroundTypes(this)
        if (types == 0) return false
        ensureChannel(this)
        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(LivePlayState(game = game)), types)
        }.onFailure { Log.e(TAG, "Couldn't start the play service in the foreground", it) }.isSuccess
    }

    private fun start(game: Game, profileId: Long?) {
        val c = container
        val next = LiveGameSession(
            c.livePlay, c.faceTrackingManager, c.inGameVoiceEngine, c.profileRepository,
            c.controlsRepository, c.settingsRepository, onExit = ::exitToPwde, onPlayOtherGame = ::playOtherGame,
        )
        val previous = sessionJob
        session = next
        sessionJob = scope.launch {
            // One session at a time: the old one releases the camera and mic first.
            previous?.cancelAndJoin()
            next.run(game, profileId)
        }
        notificationJob?.cancel()
        notificationJob = scope.launch {
            c.livePlay.state
                .map { NotificationContent(it.game, it.profileName, it.paused, it.face.outputMode) }
                .distinctUntilChanged()
                .collect { content ->
                    if (content.game == null) return@collect
                    val state = LivePlayState(game = content.game, profileName = content.profileName, paused = content.paused)
                    updateNotification(notification(state, content.mode.label))
                }
        }
    }

    private fun stopSession() {
        session = null
        notificationJob?.cancel()
        val job = sessionJob
        sessionJob = null
        scope.launch {
            job?.cancelAndJoin()
            // Play may have been pressed again while this one was stopping.
            if (sessionJob != null) return@launch
            ServiceCompat.stopForeground(this@PlayService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission") // checked just below
    private fun updateNotification(notification: Notification) {
        // Without the permission the notification stays hidden but the service keeps running.
        if (Build.VERSION.SDK_INT >= 33 && !granted(this, Manifest.permission.POST_NOTIFICATIONS)) return
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
    }

    /**
     * "profile <name>" for another game's profile: the same path as "play <game>" in PWDe, which
     * restarts this service's session on that game and opens it. A refusal (not installed, no
     * camera or mic) is shown on the overlay rather than lost.
     */
    private fun playOtherGame(game: Game, profileId: Long) {
        val c = container
        scope.launch {
            val text = startPlaying(this@PlayService, c.profileRepository, game, profileId)
            c.livePlay.update { it.copy(message = text) }
        }
    }

    /** "exit" in game: stop, and bring PWDe back. */
    private fun exitToPwde() {
        stopSession()
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }.onFailure { Log.w(TAG, "Couldn't bring PWDe to the front", it) }
    }

    private fun notification(state: LivePlayState, mode: String? = null): Notification {
        val gameName = state.game?.displayName ?: "your game"
        val title = if (state.paused) "PWDe paused — $gameName" else "PWDe is controlling $gameName"
        val text = listOfNotNull(state.profileName?.let { "Profile: $it" }, mode?.let { "$it mode" }).joinToString(" · ")
            .ifEmpty { "Head, face and voice control is on" }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pwde)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (state.paused) "Resume" else "Pause", servicePending(ACTION_TOGGLE_PAUSE, 1))
            .addAction(0, "Recenter", servicePending(ACTION_RECENTER, 2))
            .addAction(0, "Stop", servicePending(ACTION_STOP, 3))
            .build()
    }

    private fun servicePending(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(this, requestCode, Intent(this, PlayService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private data class NotificationContent(
        val game: Game?,
        val profileName: String?,
        val paused: Boolean,
        val mode: FaceOutputMode,
    )

    companion object {
        private const val TAG = "PlayService"
        private const val CHANNEL_ID = "play"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.pwde.app.play.START"
        private const val ACTION_TOGGLE_PAUSE = "com.pwde.app.play.TOGGLE_PAUSE"
        private const val ACTION_RECENTER = "com.pwde.app.play.RECENTER"
        private const val ACTION_STOP = "com.pwde.app.play.STOP"
        private const val EXTRA_GAME = "game"
        private const val EXTRA_PROFILE = "profile"

        /** Starts (or switches) the live session. Call while PWDe is on screen. */
        fun start(context: Context, game: Game, profileId: Long?) {
            val intent = Intent(context, PlayService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_GAME, game.id)
                .putExtra(EXTRA_PROFILE, profileId ?: -1L)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Ends the live session, if one is running. */
        fun stop(context: Context) {
            if (!(context.applicationContext as PwdeApplication).container.livePlay.state.value.active) return
            runCatching { context.startService(Intent(context, PlayService::class.java).setAction(ACTION_STOP)) }
        }

        /** True when the camera or the mic may be used, which the session needs. */
        fun canRun(context: Context): Boolean = foregroundTypes(context) != 0

        /** Permissions still worth asking for before playing. */
        fun permissionsToAsk(context: Context): Array<String> = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { !granted(context, it) }.toTypedArray()

        private fun foregroundTypes(context: Context): Int {
            var types = 0
            if (granted(context, Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (granted(context, Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            return types
        }

        private fun granted(context: Context, permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        private fun ensureChannel(context: Context) {
            NotificationManagerCompat.from(context).createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Playing with PWDe")
                    .setDescription("Shown while PWDe controls a game, with Pause and Stop")
                    .build(),
            )
        }
    }
}
