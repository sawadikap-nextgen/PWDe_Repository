package com.pwde.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.sensors.face.CursorPosition
import com.pwde.app.sensors.face.GestureMeasure
import com.pwde.app.sensors.face.JoystickState
import com.pwde.app.ui.theme.PwdeShapes
import com.pwde.app.ui.theme.PwdeTheme

/** A pad showing where the head pointer is. */
@Composable
fun CursorPad(position: CursorPosition, active: Boolean, modifier: Modifier = Modifier) {
    val colors = PwdeTheme.colors
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(PwdeShapes.button)
            .background(colors.surfaceMuted)
            .semantics {
                contentDescription = "Pointer at ${(position.x * 100).toInt()}% across, ${(position.y * 100).toInt()}% down"
            },
    ) {
        val dot = 28.dp
        Canvas(Modifier.matchParentSize()) {
            drawLine(colors.textMuted.copy(alpha = 0.3f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height))
            drawLine(colors.textMuted.copy(alpha = 0.3f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        }
        Box(
            Modifier
                .offset(x = (maxWidth - dot) * position.x, y = (maxHeight - dot) * position.y)
                .size(dot)
                .clip(CircleShape)
                .background(if (active) colors.primary else colors.textMuted),
        )
    }
}

/**
 * The calibration target pad: a ring where the user should look, and — unless the one overlay is
 * already drawing the pointer — a dot for where it actually is. [showPointer] is false when PWDe's
 * accessibility overlay is on, so the pad never doubles a pointer the user can see twice.
 */
@Composable
fun BoxScope.CursorCalibrationOverlay(x: Float, y: Float, active: Boolean, target: Offset, showPointer: Boolean = true) {
    val colors = PwdeTheme.colors
    val onTarget = kotlin.math.hypot(x - target.x, y - target.y) < 0.1f
    Canvas(
        Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = if (onTarget) "Pointer is on the target" else "Pointer at ${(x * 100).toInt()}% across, ${(y * 100).toInt()}% down"
            },
    ) {
        val targetCenter = Offset(target.x * size.width, target.y * size.height)
        drawCircle(colors.primary.copy(alpha = if (onTarget) 0.5f else 0.2f), radius = 26.dp.toPx(), center = targetCenter)
        drawCircle(colors.primary, radius = 26.dp.toPx(), center = targetCenter, style = Stroke(3.dp.toPx()))
        if (showPointer) drawCircle(if (active) colors.secondary else colors.textMuted, radius = 12.dp.toPx(), center = Offset(x * size.width, y * size.height))
    }
    if (onTarget) {
        StatusPill(
            "On target!",
            icon = Icons.Outlined.CheckCircle,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        )
    }
}

/** Joystick: outer ring = full deflection, inner circle = dead zone, knob = current direction. */
@Composable
fun JoystickView(state: JoystickState, modifier: Modifier = Modifier, active: Boolean = true) {
    val colors = PwdeTheme.colors
    Canvas(
        modifier
            .aspectRatio(1f)
            .semantics {
                contentDescription = "Joystick ${state.direction.label}, x ${fmt(state.x)}, y ${fmt(state.y)}"
            },
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 - 6.dp.toPx()
        drawCircle(colors.surfaceMuted, radius, center)
        drawCircle(colors.primary.copy(alpha = 0.7f), radius, center, style = Stroke(3.dp.toPx()))
        drawCircle(colors.warning.copy(alpha = 0.25f), radius * state.deadZone, center)
        val knob = Offset(center.x + state.x * radius, center.y + state.y * radius)
        drawLine(colors.primary.copy(alpha = 0.5f), center, knob, strokeWidth = 4.dp.toPx())
        drawCircle(if (active) colors.secondary else colors.textMuted, radius * 0.22f, knob)
    }
}

/** One gesture's live score against its threshold (the white tick). */
@Composable
fun GestureMeter(gesture: FacialGesture, measure: GestureMeasure?, active: Boolean, modifier: Modifier = Modifier) {
    val colors = PwdeTheme.colors
    val fill = ((measure?.ratio ?: 0f) / METER_SPAN).coerceIn(0f, 1f)
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "${gesture.label}: " + when {
                measure == null -> "not measured"
                active -> "detected"
                else -> "${(measure.ratio * 100).toInt()} percent of the way to firing"
            }
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(gesture.label, style = MaterialTheme.typography.labelMedium, color = colors.text, modifier = Modifier.weight(1f))
            Text(
                when {
                    measure == null -> "—"
                    active -> "DETECTED"
                    else -> "${fmt(measure.score)} / ${fmt(measure.threshold)}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (active) colors.primary else colors.textMuted,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp).clip(PwdeShapes.pill).background(colors.surface)) {
            Box(
                Modifier
                    .width(maxWidth * fill)
                    .height(10.dp)
                    .background(if (active) colors.primary else colors.secondary.copy(alpha = 0.7f)),
            )
            // Threshold tick sits at 1 / METER_SPAN of the bar.
            Box(Modifier.offset(x = maxWidth / METER_SPAN - 1.dp).width(2.dp).height(10.dp).background(colors.text))
        }
    }
}

/** The meter shows up to 1.5× the threshold so there's visible headroom past the tick. */
private const val METER_SPAN = 1.5f

fun fmt(value: Float): String = if (kotlin.math.abs(value) >= 10f) "%.0f".format(value) else "%.2f".format(value)
