package com.pwde.app.data.gabai

import com.google.gson.Gson
import com.pwde.app.data.model.ButtonTrigger
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.data.model.MappedButton
import com.pwde.app.data.model.TriggerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** One HUD button the model found, centered and normalized to the screenshot (0–1 on both axes). */
data class DetectedButton(val className: String, val confidence: Float, val x: Float, val y: Float)

/** Finds a game's HUD buttons on a screenshot. */
interface HudDetector {
    /** False when no backend is configured; GabAI then skips auto-detection. */
    val isAvailable: Boolean

    /** Detected buttons, or throws [IOException] if the backend can't be reached or rejects the image. */
    suspend fun detect(screenshotPath: String, gameId: String): List<DetectedButton>

    object None : HudDetector {
        override val isAvailable = false
        override suspend fun detect(screenshotPath: String, gameId: String) = emptyList<DetectedButton>()
    }
}

/** Calls calibration-backend's `POST /detect` (deployed on Cloud Run). */
class CloudHudDetector(baseUrl: String) : HudDetector {
    private val baseUrl = baseUrl.trimEnd('/')
    private val gson = Gson()

    override val isAvailable = true

    override suspend fun detect(screenshotPath: String, gameId: String): List<DetectedButton> {
        val backendGame = BACKEND_GAMES[gameId] ?: return emptyList()
        // Retry network errors and server-side failures (cold starts, 429/5xx) with backoff;
        // a 4xx rejection of the image won't change on retry, so it fails straight away.
        var attempt = 1
        while (true) {
            try {
                return detectOnce(screenshotPath, backendGame)
            } catch (e: IOException) {
                if (e is RejectedException || attempt >= MAX_ATTEMPTS) throw e
                delay(RETRY_DELAY_MS * (1L shl (attempt - 1)))
                attempt++
            }
        }
    }

    private suspend fun detectOnce(screenshotPath: String, backendGame: String): List<DetectedButton> = withContext(Dispatchers.IO) {
        val boundary = "pwde-${UUID.randomUUID()}"
        val connection = URL("$baseUrl/detect?game=$backendGame").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            // Cloud Run cold starts load both models, so allow for a slow first request.
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.outputStream.use { out ->
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"screenshot\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                File(screenshotPath).inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = connection.responseCode
            if (code in 400..499 && code != 408 && code != 429) throw RejectedException(code)
            if (code !in 200..299) throw IOException("Detection failed: HTTP $code")
            val response = connection.inputStream.bufferedReader().use { gson.fromJson(it, DetectResponse::class.java) }
            response.toButtons()
        } finally {
            connection.disconnect()
        }
    }

    private class DetectResponse(val width: Int = 0, val height: Int = 0, val detections: List<Detection>? = null)

    private class Detection(val class_name: String = "", val confidence: Float = 0f, val box: List<Float>? = null)

    private fun DetectResponse.toButtons(): List<DetectedButton> {
        if (width <= 0 || height <= 0) return emptyList()
        return detections.orEmpty().mapNotNull { d ->
            val (x1, y1, x2, y2) = d.box?.takeIf { it.size == 4 } ?: return@mapNotNull null
            DetectedButton(d.class_name, d.confidence, ((x1 + x2) / 2 / width).coerceIn(0f, 1f), ((y1 + y2) / 2 / height).coerceIn(0f, 1f))
        }
    }

    /** The backend refused the request itself; retrying won't help. */
    private class RejectedException(code: Int) : IOException("Detection rejected: HTTP $code")

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1_000L

        /** App game id → the backend's `game` query value (calibration-backend/app/constants.py GAMES). */
        val BACKEND_GAMES = mapOf("mobile_legends" to "mlbb", "clash_royale" to "clash_royale")
    }
}

/**
 * Detected HUD elements → GabAI buttons, in a fixed order so the same HUD always maps the same way:
 * grouped by class in [CLASS_ORDER] (unknown classes after, alphabetically), and within a class
 * left to right. Skills and their upgrades are paired: skill 1, upgrade 1, skill 2, upgrade 2…
 * A class that appears more than once is numbered from the left ("Skill button 1" is always the
 * leftmost skill), so numbering never depends on the arc a game lays its skills in.
 * The model's joystick becomes the game's movement joystick: named so, and already set to be held
 * and steered by the head joystick, so the user doesn't have to set it up by hand.
 */
fun detectedToButtons(detected: List<DetectedButton>, firstId: Int): List<MappedButton> {
    val rank = { name: String ->
        (if (name == SKILL_UPGRADE_CLASS) SKILL_CLASS else name).let { CLASS_ORDER.indexOf(it) }.let { if (it < 0) CLASS_ORDER.size else it }
    }
    // Each button's place, left to right, among its own class.
    val position = detected.groupBy { it.className }.values
        .flatMap { same -> same.sortedWith(compareBy({ it.x }, { it.y })).mapIndexed { i, d -> d to i } }
        .associate { (d, i) -> System.identityHashCode(d) to i }
    val sorted = detected.sortedWith(
        compareBy<DetectedButton>(
            { rank(it.className) },
            { position[System.identityHashCode(it)] ?: 0 },
            // Within a pair the skill comes before its upgrade; other classes don't share a rank.
            { if (it.className == SKILL_UPGRADE_CLASS) 1 else 0 },
            { it.className },
        ),
    )
    // The leftmost joystick is the movement joystick; any others are ordinary buttons.
    val movement = sorted.firstOrNull { it.className == JOYSTICK_CLASS }
    val totals = sorted.filter { it !== movement }.groupingBy { it.className }.eachCount()
    val counts = mutableMapOf<String, Int>()
    var id = firstId
    return sorted.map { d ->
        if (d === movement) return@map MappedButton(id++, "Joystick", d.x, d.y, ButtonTrigger.MOVEMENT)
        val base = d.className.replace('_', ' ').replaceFirstChar { it.uppercase() }
        val n = (counts[d.className] ?: 0) + 1
        counts[d.className] = n
        MappedButton(id++, if ((totals[d.className] ?: 0) > 1) "$base $n" else base, d.x, d.y)
    }
}

/**
 * The gestures a game's detected buttons start with, by the label [detectedToButtons] gives them.
 * Everything else starts unmapped; the user can change any of these in the trigger step.
 */
private val DEFAULT_GESTURES: Map<String, Map<String, FacialGesture>> = mapOf(
    "mobile_legends" to mapOf("Skill button 1" to FacialGesture.SMILE, "Skill button 3" to FacialGesture.PUCKER),
)

/** [buttons] with [gameId]'s default gestures set on the buttons that have one. */
fun withGameDefaults(gameId: String?, buttons: List<MappedButton>): List<MappedButton> {
    val defaults = DEFAULT_GESTURES[gameId] ?: return buttons
    return buttons.map { button ->
        defaults[button.label]?.let { button.copy(trigger = ButtonTrigger(TriggerType.GESTURE, it.name)) } ?: button
    }
}

/**
 * The order detected buttons are listed (and so assigned) in. Skill upgrades share their skills'
 * place (see [detectedToButtons]). Names are calibration-backend's classes (app/constants.py).
 */
private val CLASS_ORDER = listOf(
    JOYSTICK_CLASS, "recall", "regen", "spell", "basic_attack", "skill_button", "buy_item", "use_item",
    "card", "champion_ability",
)
private const val SKILL_CLASS = "skill_button"
private const val SKILL_UPGRADE_CLASS = "skill_upgrade"

/** calibration-backend's class name for a game's movement joystick (app/constants.py). */
const val JOYSTICK_CLASS = "joystick"
