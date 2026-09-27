package com.pwde.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pwde.app.accessibility.PwdeAccessibilityService
import com.pwde.app.ui.common.pwdeContainer

/**
 * Uses PWDe's **one** overlay for as long as this screen is composed.
 *
 * The overlay is the accessibility service's floating pointer, bubble and caption — the same views
 * that run over the real game, and the only ones that can carry a command out. A screen that draws its
 * own pointer instead shows the user a second cursor that behaves differently and **cannot press
 * anything**, with two of them on screen at once. Claiming means:
 *
 * - exactly one overlay exists. `PwdeAccessibilityService` is a system singleton, so this never
 *   creates an instance — it only says who is using it;
 * - the overlay mode reads IN_APP, so the overlay drives PWDe with the app-wide recognizer (Google)
 *   instead of the in-game sherpa-onnx spotter;
 * - face gestures fire the same commands over PWDe that they fire over a game, because the service
 *   carries them out;
 * - any screen that would draw its own pointer can ask [overlayDrawsPointer] and stop.
 *
 * Claimed once for the whole app by `PwdeNavHost`. [owner] is a reference count, so a screen may hold
 * its own claim as well without one releasing the other's.
 */
@Composable
fun InAppOverlay(owner: String) {
    val livePlay = pwdeContainer().livePlay
    // Only the claim: whether PWDe is actually in front is `PwdeVisibility`'s job
    // (AppContainer.pwdeVisibility), so there is one authority for it rather than two.
    DisposableEffect(livePlay, owner) {
        livePlay.acquireOverlay(owner)
        onDispose { livePlay.releaseOverlay(owner) }
    }
}

/**
 * Whether the overlay will actually draw the pointer right now.
 *
 * The accessibility service can be off, in which case nobody draws it and a screen that still needs a
 * pointer must draw its own. Re-read on resume, because the user can turn "Use PWDe" on or off in
 * Android Settings and come straight back.
 *
 * Read-only on purpose: a screen that is deciding whether to draw its own pointer must not itself
 * become a reason for the overlay to appear.
 */
@Composable
fun overlayDrawsPointer(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(PwdeAccessibilityService.isEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = PwdeAccessibilityService.isEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return enabled
}
