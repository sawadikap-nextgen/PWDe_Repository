package com.pwde.app.di

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the user is actually **in** the PWDe app right now — the signal that decides which speech
 * service should be listening.
 *
 * PWDe has exactly one Activity, so "a PWDe Activity is started" is exactly "PWDe is on screen". That
 * is the rule the voice split needs: while PWDe is in front, the app-wide `AndroidVoiceCommandManager`
 * (Android `SpeechRecognizer`, i.e. Google's service) is the one listening; the moment the user leaves
 * for the game, that manager stands down and the in-game sherpa-onnx spotter takes the microphone.
 *
 * Deliberately not `ProcessLifecycleOwner`: that would need the `lifecycle-process` artifact to answer
 * the same question for the same single Activity, and this keeps the dependency list as it is.
 *
 * **Counted, not a flag.** A configuration change destroys and recreates the Activity, so a plain
 * boolean would report "gone" in between and flip the voice engine for a frame. Every callback arrives
 * on the main thread, so the counter needs no extra synchronisation.
 */
class PwdeVisibility : Application.ActivityLifecycleCallbacks {
    private var startedCount = 0

    private val _inForeground = MutableStateFlow(false)

    /** True while the user is in the PWDe app. */
    val inForeground: StateFlow<Boolean> = _inForeground.asStateFlow()

    override fun onActivityStarted(activity: Activity) {
        startedCount++
        _inForeground.value = true
    }

    override fun onActivityStopped(activity: Activity) {
        startedCount = (startedCount - 1).coerceAtLeast(0)
        _inForeground.value = startedCount > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
