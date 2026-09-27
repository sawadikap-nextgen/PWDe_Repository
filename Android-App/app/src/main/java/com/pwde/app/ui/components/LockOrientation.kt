package com.pwde.app.ui.components

import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Holds the screen in the game's own orientation while this is composed (landscape for Mobile
 * Legends, portrait for Clash Royale), so buttons land where they do in the game. Unlocks on
 * leaving, but not across the rotation it caused.
 */
@Composable
fun LockOrientation(landscape: Boolean) {
    val context = LocalContext.current
    DisposableEffect(landscape) {
        var c = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        val activity = c as? Activity
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        onDispose {
            // Back to the app's portrait layout, not the value found on entry: after the rotation this
            // lock causes, the recreated activity already reports the lock. Following the sensor
            // instead left the app sideways while the phone was still held for the game.
            if (activity != null && !activity.isChangingConfigurations) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }
}
