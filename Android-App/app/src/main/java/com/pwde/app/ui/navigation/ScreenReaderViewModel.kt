package com.pwde.app.ui.navigation

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pwde.app.data.prefs.SettingsRepository
import com.pwde.app.data.prefs.UserSettings
import com.pwde.app.data.speech.SpeechOutput
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One thing a screen says: a label, a value or a control's name, in reading order. [checked] is set for
 * a toggle so its state is spoken with it ("Read aloud, on") rather than leaving the user to guess.
 */
data class ScreenEntry(val text: String, val checked: Boolean? = null)

/**
 * Turns the entries read off a screen into what PWDe says out loud.
 *
 * Pure, because the parts that are easy to get subtly wrong are exactly the parts a unit test can pin:
 * the reading order, the labels Compose exposes twice, and the length limit.
 */
object ScreenText {
    /**
     * Android's text-to-speech engines refuse input longer than `TextToSpeech.getMaxSpeechInputLength()`
     * (4000), so cut well under it — on a word boundary, never mid-word. This is a safety valve for a
     * pathological screen, not a feature.
     */
    const val MAX_CHARS = 1200

    private val WHITESPACE = Regex("\\s+")

    /** What to read for [entries]: each once, blanks dropped, in order. Empty when there is nothing. */
    fun describe(entries: List<ScreenEntry>, maxChars: Int = MAX_CHARS): String {
        val seen = mutableSetOf<String>()
        val parts = entries.mapNotNull { entry ->
            val text = entry.text.replace(WHITESPACE, " ").trim().trim('.', ',', ':', ';')
            // The same words twice is noise: Compose puts a label on both a control and its icon.
            if (text.isEmpty() || !seen.add(text.lowercase())) return@mapNotNull null
            if (entry.checked == null) text else "$text, ${if (entry.checked) "on" else "off"}"
        }
        if (parts.isEmpty()) return ""

        val joined = parts.joinToString(". ")
        if (joined.length <= maxChars) return "$joined."
        return joined.take(maxChars).substringBeforeLast(' ').trimEnd(' ', '.', ',', ':', ';') + "."
    }
}

/**
 * Reads the visible screen out loud from **Compose's semantics tree** of PWDe's own window — the same
 * text, labels and toggle states a screen reader is given, in reading order.
 *
 * **Not `AccessibilityNodeInfo`.** A node built in-process is *unsealed*, and `getChild()` on it throws
 * `IllegalStateException: Cannot perform this action on a not sealed instance`; nodes are sealed only
 * when the framework hands them out over an accessibility connection, which an app cannot fake. The
 * first version of this walked the view's accessibility tree and crashed the app on every screen.
 *
 * Deliberately PWDe's own window only. `PwdeAccessibilityService` is configured with
 * `canRetrieveWindowContent="false"` and the README promises it never reads what is on screen; nothing
 * here changes that, and no other app's content is reachable from this code.
 */
object AndroidScreenText {
    /**
     * Never throws. Reading the screen out loud is a courtesy on top of a working screen, so a failure
     * here must fall back to the screen's name rather than take the app down with it.
     */
    fun of(view: View): List<ScreenEntry> = runCatching {
        val owner = semanticsOwnerOf(view) ?: return emptyList()
        // The *merged* tree is what a screen reader is given: a button and its label are one node.
        val root = owner.rootSemanticsNode
        val entries = mutableListOf<ScreenEntry>()
        collect(root, entries)
        entries
    }.getOrDefault(emptyList())

    /**
     * The Compose host view publishes its semantics through [ViewRootForTest].
     *
     * `AndroidComposeView` is the class that implements it, but it is Kotlin-**internal**, so it cannot
     * be named here; the interface is public, which is the supported way to reach it.
     */
    private fun semanticsOwnerOf(view: View): SemanticsOwner? = when {
        view is ViewRootForTest -> view.semanticsOwner
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { semanticsOwnerOf(view.getChildAt(it)) }
        else -> null
    }

    private fun collect(node: SemanticsNode, out: MutableList<ScreenEntry>) {
        labelOf(node.config)?.let { out += ScreenEntry(it, checked = toggleState(node.config)) }
        node.children.forEach { collect(it, out) }
    }

    /** A control's own text and its description are joined; a label ending up in both is said once. */
    private fun labelOf(config: SemanticsConfiguration): String? {
        val text = config.textLines().joinToString(" ")
        val description = config.strings(SemanticsProperties.ContentDescription).joinToString(" ")
        return when {
            text.isEmpty() && description.isEmpty() -> null
            description.isEmpty() -> text
            text.isEmpty() -> description
            description.contains(text, ignoreCase = true) -> description
            text.contains(description, ignoreCase = true) -> text
            else -> "$description, $text"
        }
    }

    private fun toggleState(config: SemanticsConfiguration): Boolean? =
        when (config.valueOf(SemanticsProperties.ToggleableState)) {
            ToggleableState.On -> true
            ToggleableState.Off -> false
            // Indeterminate, or not a toggle at all: say nothing rather than guess a state.
            else -> null
        }

    private fun SemanticsConfiguration.textLines(): List<String> =
        valueOf(SemanticsProperties.Text).orEmpty().map { it.text.trim() }.filter { it.isNotEmpty() }

    private fun SemanticsConfiguration.strings(key: SemanticsPropertyKey<List<String>>): List<String> =
        valueOf(key).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }

    /** [get] would throw when the key is absent, so presence is checked first. */
    private fun <T> SemanticsConfiguration.valueOf(key: SemanticsPropertyKey<T>): T? =
        if (contains(key)) get(key) else null
}

/**
 * Reads PWDe's own screens out loud when the user turned on read-aloud and is not using another screen
 * reader (PWDe must never talk over TalkBack).
 *
 * This speaks the screen's **contents** — its labels, values and controls — not just its name. The name
 * is still the fallback for a screen with nothing to read, so arriving somewhere is never silent.
 */
class ScreenReaderViewModel(
    settingsRepository: SettingsRepository,
    private val speechOutput: SpeechOutput,
) : ViewModel() {
    private val settings: StateFlow<UserSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * True when PWDe is the one that should be reading: read-aloud on, and not deferring to another
     * screen reader. Callers read this to skip the work of walking the screen while it is off.
     */
    val enabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.ttsEnabled && !it.usesOtherScreenReader }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Reads [entries], or [title] when there is nothing on the screen worth saying. */
    fun onScreenShown(title: String?, entries: List<ScreenEntry>) {
        val s = settings.value ?: return
        if (!s.ttsEnabled || s.usesOtherScreenReader) return
        val text = ScreenText.describe(entries).ifEmpty { title.orEmpty() }
        if (text.isNotBlank()) speechOutput.speak(text, s.ttsSpeed.rate)
    }

    /**
     * Cuts off whatever is being read, so the next screen is never queued behind it.
     *
     * Called the moment the user changes screen, and when read-aloud is switched off mid-sentence. The
     * engine's own `QUEUE_FLUSH` alone is not enough: it only takes effect when the *next* utterance
     * arrives, so without this the screen the user just left keeps being read out loud until then.
     */
    fun stopReading() {
        speechOutput.stop()
    }
}
