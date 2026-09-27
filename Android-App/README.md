# PWDe (SAWADIKAP) — Android

An accessibility-first gaming companion for people with disabilities: hands-free control through head/face tracking, voice, and a guided setup assistant (GabAI).


## Build

- Open this folder in Android Studio, or run `./gradlew assembleDebug`. The APK is written to `app/build/outputs/apk/debug/`.
- Run the tests with `./gradlew testDebugUnitTest` (JVM + Robolectric; no device needed).
- Toolchain: AGP 9.4 with built-in Kotlin 2.2, KSP 2.3 for Room, compileSdk 37, minSdk 24.

## Architecture (MVVM, manual DI)

```
com.pwde.app
├── di/AppContainer        singletons, reached via PwdeApplication.container
├── data/
│   ├── prefs/             SettingsRepository (DataStore → Flow<UserSettings>)
│   ├── local/             Room: CalibrationProfile, GameProfile, ControlSettings
│   │                      + ProfileRepository, ControlsRepository
│   ├── remote/            AuthRepository (Firebase or guest-only), SyncRepository (no-op)
│   ├── speech/            SpeechOutput (Android TextToSpeech)
│   ├── gabai/             GabAiState (sealed state machine), GabAiFlow (transitions),
│   │                      GabAiRepository (resumable sessions, screenshots)
│   ├── media/             TutorialPlayer (ExoPlayer)
│   └── model/             gesture catalog, control tuning, voice options, games
├── sensors/
│   ├── face/              FaceTrackingManager (CameraX + MediaPipe Face Landmarker),
│   │                      GestureClassifier, Cursor/JoystickMapper, OrientationHeadTracker
│   └── voice/             VoiceCommandManager (app-wide), InGameVoiceEngine (gameplay),
│                          ContinuousSpeechRecognizer (shared plumbing), MicArbiter, CommandMatcher,
│                          SherpaWakeWordEngine + SherpaInGameVoiceEngine (sherpa-onnx KWS), KeywordTokenizer
└── ui/
    ├── theme/             PwdeTheme + ThemeViewModel (drives the whole app from settings)
    ├── components/        design-system components (buttons, cards, steppers, mic overlay…)
    ├── navigation/        Routes, PwdeNavHost, ScreenReaderViewModel
    └── <feature>/         one screen + ViewModel per feature
```

- Composables never touch Room, DataStore, Firebase, TTS, ExoPlayer, CameraX or SpeechRecognizer directly. They observe `StateFlow` from a ViewModel, and the ViewModel talks to a repository or manager.
- **Adaptive UI is app-wide.** `MainActivity` wraps the `NavHost` in `PwdeTheme(settings)`:
  - Color scheme: Default, High contrast, Light or Color-safe.
  - Text size: scales every `sp` through `LocalDensity`, on top of the system font scale.
  - Layout mode: Standard, Compact, or Easy reach (content moves to the lower half of the screen).
- ViewModels are created with `pwdeViewModel { container -> … }`.

## Optional Firebase sign-in

The app runs fully as a **guest** with no Firebase config. When no config is found, `AuthRepository` falls back to guest-only mode. To turn on email/password sign-in, add these to `local.properties`:

```
pwde.firebase.apiKey=...
pwde.firebase.appId=...
pwde.firebase.projectId=...
```

Signing in only adds (future) cloud sync. It never gates features and never deletes local data. `SyncRepository` is a no-op stub in this build, and the Profile screen says so.

## Input pipelines

**Head & face tracking.** `FaceTrackingManager` runs the front camera through MediaPipe's Face Landmarker with blendshapes and the facial transformation matrix. This is modelled on [Google Project GameFace](https://github.com/google/project-gameface); see `NOTICE`.
- Head pose (yaw/pitch/roll) comes from the transformation matrix.
- Gestures are thresholds on blendshape scores, each with a 1–10 sensitivity. Tilt and nod come from head pose instead.
- Yaw and pitch move the pointer (relative movement, per-direction speed, smoothing). Roll and pitch drive an 8-way joystick with a dead zone and a saved center.
- The camera runs only while a screen is showing tracking. It stops a few seconds after that screen leaves the foreground.
- The model ships in `app/src/main/assets/face_landmarker.task`, so builds work offline.

**Voice commands.** `VoiceCommandManager` wraps Android `SpeechRecognizer` and only listens while PWDe is on screen; there is no system-wide listening.
- Matching is exact phrase or word-anywhere. Activation is right away (on partial results) or after you finish.
- Standard commands work everywhere: back, home, next, skip, settings, menu, close. So do your shortcuts (cursor mode, joystick mode, switch profile).
- Each screen adds its own commands, usually the names on its cards and buttons.
- Every `PwdeScreen` shows the same floating mic (`VoiceMicOverlay`). Tap it to turn voice on or off. Nothing is shown while idle; what PWDe heard and the command it matched pop up beside the mic for a few seconds and are announced to screen readers. A screen's `voiceHint` is read out with the mic button.
- Say **"read screen"** to re-read the current screen out loud (see *Read aloud* below).

**Read aloud (text to speech).** `SpeechOutput` wraps Android `TextToSpeech` — the phone's TTS engine, which is Google's on most devices. When the user turns read-aloud on, `ScreenReaderViewModel` speaks the screen's **contents**, not just its name.
- The words come from PWDe's **own** window: `AndroidScreenText` walks **Compose's semantics tree** of the visible screen — labels, values, button names and toggle states, in reading order. The host view is reached through the public `ViewRootForTest` interface (`AndroidComposeView`, which implements it, is Kotlin-`internal`), and the **merged** root is used because that is the tree a screen reader is given: a button and its label are one node.
- **Do not use `AccessibilityNodeInfo` for this.** A node built in-process is *unsealed*, and `getChild()` on it throws `IllegalStateException: Cannot perform this action on a not sealed instance` — nodes are sealed only when the framework hands them out over an accessibility connection, which an app cannot fake. The first version walked the view's accessibility tree and crashed the app on every screen change. `AndroidScreenText.of` is now wrapped in `runCatching`, so a failure falls back to the screen's name instead of taking the app down.
- No screen-content permission is involved and no other app is reachable from it. (`PwdeAccessibilityService` stays `canRetrieveWindowContent="false"`, and still never reads what is on screen.)
- `ScreenText.describe` is pure and unit-tested (`ScreenTextTest`): each entry once (Compose exposes a label on both the control and its icon), blanks dropped, whitespace collapsed, a toggle read as "Read aloud, on", and the whole thing cut on a word boundary under `getMaxSpeechInputLength()`.
- **Changing screen is not a queue.** Two things happen when the route changes, and neither is a fixed delay: what the previous screen was reading is **dismissed at once** (`ScreenReaderViewModel.stopReading` — the engine's own `QUEUE_FLUSH` only takes effect when the *next* utterance starts, so without this the old screen keeps being read out loud), and the new screen is read as soon as it is the only one in the semantics tree. Navigation keeps the outgoing destination composed for its whole transition, so reading sooner would read **both** screens; the marker that it has gone is the previous read's own opening words (`overlapsOutgoing`), with a timeout just above Navigation's ~700 ms default fade as a safety net. With animations off the loop exits on the next frame.
- It is gated on `ttsEnabled` and on **not** `usesOtherScreenReader` — PWDe must never talk over TalkBack. The switch is in Controls → Voice *and* the first-run Voice tutorial; the tutorial alone was not enough, because `Routes.VOICE_TUTORIAL` is only entered from Splash and Setup, so the setting was unreachable afterwards.
- A screen with nothing to read still announces its name (`Routes.spokenTitle`), so arriving somewhere is never silent.

**Fallbacks (never a crash, never a block).**
- No camera permission or no front camera: the phone's motion sensors stand in for your head. Every place this is active shows **"Demo Mode: Simulated Head Tracking"**. Setup asks for the camera before anything boots so this fallback doesn't surprise a new user mid-calibration; a session that already fell back is restarted by `SetupViewModel.refreshPreview()` when the calibration step resumes.
- No mic permission: tapping the floating mic asks for it. No recognition service: the mic shows as off and every screen still works by touch. There is no typed-command box on app screens (`VoiceCommandManager.submitText` remains as an API with no UI caller); gameplay keeps its own typed fallback.

**In-game voice (button activations).** Voice while playing goes through the `InGameVoiceEngine` interface. It recognizes just the active game profile's button triggers plus back/pause/menu, resume, select, recenter and "show controls" (labels every mapped button with what presses it for a few seconds). This covers both the in-app playing view and PWDe running over the real game — but with a different implementation behind it in each, see *Which voice engine runs where* below.
- The implementation is `SherpaInGameVoiceEngine`: on-device [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) keyword spotting with the English GigaSpeech 3.3M KWS model, listening for exactly those phrases. App navigation (`VoiceCommandManager`) and GabAI's "assign/use" dictation stay on Android `SpeechRecognizer`, because they need free-form speech.
- **Noise cancellation** (`WakeWordSpotterTuning.noiseCancellation`, on by default; Testing Station → Spotter defaults): the spotter records from `AudioSource.VOICE_COMMUNICATION`, which turns on the phone's call processing, and also attaches `NoiseSuppressor` / `AcousticEchoCanceler` wherever the phone offers them. Echo cancellation is what removes the game's own speaker sound from the mic, and how well it works depends on the phone. Off, it records from the near-raw `AudioSource.MIC`. The Google `SpeechRecognizer` paths capture their own audio, so this setting doesn't affect them.
- A spotter fires on the phrase itself, so the user's match and activation modes don't apply in game: every hit is an immediate, exact command.
- `SherpaSupport.isSupported` picks it only where it can run: an arm64-v8a device with the model in the APK. Anywhere else (an x86_64 emulator, or a checkout without the `.onnx` files) `di/AppContainer.kt` falls back to `SpeechRecognizerInGameVoiceEngine`. `GameplayViewModel` and everything above it depend only on the interface.
- `MicArbiter` guarantees one listener at a time: while a game (or the Testing Station's wake-word panel) holds the mic, the app-wide `VoiceCommandManager` stands down. The in-game engine holds the claim for the whole session, so a spotter restart never lets the app-wide recognizer in.
- `BaseInGameVoiceEngine` carries the contract every engine inherits: scoped matching, and the typed fallback when the mic is unavailable (a spotter that fails to load reports `SERVICE_ERROR`, which brings the typed field up).

**Which voice engine runs where (one rule).** *In the PWDe app → the app-wide recognizer (Android `SpeechRecognizer`, i.e. Google); over the real game → sherpa-onnx.*
- App screens (`VoiceCommandManager`) and the two in-app flows that press a profile's buttons — the in-app Play preview and GabAI's controls test — use `AppContainer.inAppVoiceEngine`, a `SpeechRecognizerInGameVoiceEngine`. They run with PWDe in front, where the user is already speaking to that recognizer, so a spoken button phrase behaves the same in the preview as it does in the app.
- Only a live session over the real game (`PlayService` → `LiveGameSession`) gets `inGameVoiceEngine`, which is an **`AdaptiveVoiceEngine`**: sherpa-onnx while PWDe is not in front, and **stood down** the moment PWDe comes back so the app-wide recognizer above is the one listening. The choice is made live, from `PwdeVisibility`, not once when the session starts.
  - This has to be live. A session over the real game outlives the user's visit to PWDe, so deciding at session start left the spotter holding the microphone while the user was back in the app: the app-wide recognizer stayed muted and PWDe kept listening with the *game's* engine. Proved on device — `SherpaWakeWord: Spotted "pause"` fired while `com.pwde.app.MainActivity` had focus and `PlayService` was still `isForeground=true`.
  - `InGameVoiceState.modelLabel` carries whichever engine really has the microphone, and `LiveGameSession` republishes it, so the overlay caption follows the switch instead of naming the engine chosen at startup.
- Both take the mic through `MicArbiter`, so they still never listen at the same time. Its game slot is a **reference count of per-instance holder names** (`MicArbiter.newHolder`), not a flag: with a single boolean — or a name two engines share — the first `release` cleared the slot while the other engine was still recording, and the app-wide recognizer then started on top of it. Two `AudioRecord`s fight for the microphone and the loser hears **silence**, which made the spotter go deaf.
- **Detecting "the user is in PWDe"** is `PwdeVisibility` (`di/PwdeVisibility.kt`): `Application.ActivityLifecycleCallbacks` with a **counter**, not a flag, because a configuration change destroys and recreates the Activity. PWDe has one Activity, so "started" is exactly "on screen". It is the third input to `LivePlayState.overlayMode` (so the overlay comes down when PWDe is backgrounded even though the NavHost is still composed) and it is what makes the voice split a rule rather than an accident.

**The overlay (one instance, two behaviours).** There is exactly **one** overlay: `PwdeAccessibilityService`'s floating pointer, mode bubble and speech caption. It is a system singleton, so nothing ever creates a second one — a screen that wants it *claims* it.
- `LivePlay.acquireOverlay(name)` / `releaseOverlay(name)` is a **reference count by name**. `PwdeNavHost` claims it once for the app, for as long as PWDe is actually in front (released on `ON_STOP`, so it is never left drawn over another app).
- `LivePlayState.overlayMode(inApp)` is the whole rule, and pure: a running session → `GAME`; otherwise a claim → `IN_APP`; otherwise `OFF`.
- **`GAME`** — the pointer drives the game, the movement stick is held, and the caption names the in-game sherpa-onnx engine the session publishes.
- **`IN_APP`** — the pointer drives PWDe, no movement stick is held (there is no game to steer), the caption names the app-wide Google recognizer, and face gestures still fire their commands because the service carries them out.
- **`OFF`** — nobody is using it: all the views are removed. This is the state while PWDe is not in front and no session runs, and **gesture handling is off with it** — a gesture must never fire a tap on a screen with no visible pointer to aim it. (Over the real game the session handles gestures itself, as before.)
- Because the overlay draws the pointer, screens must not draw a second one: `overlayDrawsPointer()` says whether it will, and the in-app Play preview, GabAI's button-mapping stage and both cursor-calibration pads drop theirs when it is on. With "Use PWDe" off nothing would draw it, so they fall back to their own — and the Play preview says so plainly rather than showing a pointer that cannot press anything.
- **The in-app Play preview presses for real.** Its commands go through `LivePlay.perform`, the same path a live session uses, so the overlay taps the mapped button where it really is on the screen. A *gesture* is carried out only by the overlay (`handleIdleGesture`), never twice: `GameplayViewModel.onGesture` reports it and passes `carryOut = false`.

**Keyword spotting (sherpa-onnx).** Any phrase works, with nothing to train and no account. The model decides in ~320 ms chunks and listens for many phrases at once.
- Phrases become model tokens **on the device** (`KeywordList` + `SentencePieceUnigramTokenizer` in `sensors/voice/KeywordTokenizer.kt`), reproducing upstream's Python `sherpa-onnx-cli text2token`. Every token is validated against `tokens.txt` first, because the native spotter **terminates the process** on an unknown token. A phrase the model can't spell is logged and shown instead of being silently ignored.
- The model matches *sounds*: "PWDE" becomes the pieces `P W DE`, so say it as written.
- Tuning lives in `WakeWordTuningStore`, shared by the Testing Station and gameplay (in memory; an app restart resets it). Each phrase starts at the **Max** preset (boost 6.0, threshold 0.0), and the spotter defaults match it (`keywordsScore` 6.0, `keywordsThreshold` 0.0, 3 trailing blanks, **8 active paths**). That is far past upstream's 1.5 / 0.25: a threshold of 0 accepts the weakest evidence, trading false alarms for sensitivity. Phrases are keyed normalized, so "Hey PWDE" tuned in the Testing Station is the same entry as a "hey pwde" button trigger.
- **The phrase list is a shared budget, and it is over-subscribed.** `maxActivePaths` (8) caps the keyword hypotheses alive at once *across the whole list*, and the in-game list is ~55 phrases, every one at threshold 0. So each phrase added crowds the others, and because phrases are matched by *sound*, a new phrase containing an existing word takes that word's paths outright. This is not theoretical: adding `"lock joystick"` / `"unlock joystick"` / `"lock center"` to `GameInput.STANDARD_BINDINGS` stopped `"joystick mode"`, `"gyro joystick"` and `"center joystick"` firing on device, and they were removed again. `InGameKeywordMapTest.theInGamePhraseListStaysInsideItsBudget` pins the budget (~58 phrases, at most 5 containing "joystick") so it cannot grow by accident. Before adding phrases, or if any stop firing, raise the spotter's `activePaths` (Testing Station → Tune → Spotter defaults, range 1–32) and **measure** rather than assuming.
- Inspect what the spotter is really listening for with `adb shell run-as com.pwde.app cat files/models/sherpa-kws-zipformer-gigaspeech-3.3M-2024-01-01/keywords-game.txt`.
- Cost: the prebuilt libraries are arm64-v8a only (`libonnxruntime.so` is 22 MB, plus ~10 MB of sherpa JNI) and the model is ~6 MB, all in `src/main`. `build.gradle.kts` adds `noCompress += "onnx"` and `useLegacyPackaging = true` (the libraries load with `System.loadLibrary`). The `.onnx` files are committed through a `.gitignore` exception.
- `KeywordTokenizerTest` and `GigaSpeechTokenizerTest` pin the tokenizer to upstream's reference output; `WakeWordTuningTest`, `WakeWordTuningStoreTest` and `InGameKeywordMapTest` pin tuning and the phrase-to-command map.

**GabAI** is a scripted, resumable state machine, not a chatbot. `GabAiState` is a sealed hierarchy (Welcome, then the calibration branch, then the game-profile branch), and `GabAiFlow` holds the pure transitions.
- Every step is saved to Room (`gabai_sessions`) with the form data entered so far. Backing out, or force-closing the app, resumes on the same step from **GabAI → Continue Existing**; the Dashboard card shows where you stopped.
- The calibration steps reuse the Prompt 2 camera, pointer and joystick UI and apply live. Saving creates a `CalibrationProfile`.
- For a game profile, you pick a game and a calibration, choose a screenshot, and mark its buttons: tap, drag, or say "place" to drop a button at your head pointer. You name each button by typing or by voice, then choose how to press it (voice phrase, head gesture or joystick direction). The result is saved as a `GameProfile`, editable later from the Profile or game screen.

**Testing Station** is a development tool, so only debug builds have it. Its code lives in `src/debug/`, and `src/release/` provides a no-op twin. A release build has neither the Dashboard card nor the route, and no Testing Station classes are in the APK.
- Its **Wake word** panel runs its own sherpa-onnx spotter: type any phrase, press **Start listening**, and watch detections, mic level and model load.
- **Tune** beside a phrase steps its `boost` and `threshold`; **Spotter defaults** steps the four spotter-wide values. **Apply & restart** pushes them into the panel's spotter, and gameplay's button voice picks them up the next time it starts or reloads its commands. **Reset** puts everything back on the shipped values.
- To tune a button's voice trigger, add the same phrase here.

## Back navigation

The back arrow, the system back gesture and voice "back" always do the same thing: return to the screen you actually came from.
- Every route in `PwdeNavHost` passes `::back`, which pops the real back stack. No screen navigates to a fixed "parent" on back.
- Wizards (Setup, Voice tutorial, GabAI, Testing Station pages) step back through their own steps first, then leave. Their `BackHandler` and back arrow call the same function.
- Voice "back" goes through the `OnBackPressedDispatcher`, so it follows the same rules. On a root screen it does nothing rather than close the app.
- GabAI opened mid-flow (a new or edited game profile from Game Detail, Profile or Controls) leaves for that screen when you back out of its first step. It doesn't show GabAI's Welcome, which you never came through. Finishing with **Play** replaces GabAI on the stack, so back from the preview returns to where GabAI was opened.
- `GabAiPersistenceTest` covers GabAI's back behaviour; `SetupViewModelTest` covers Setup's.

**Manual QA** (repeat each with the back arrow, the system back gesture, and saying "back"):
- [ ] Dashboard → Games tab → game → Test profile (Playing) → back → Game Detail → back → Games → back → Dashboard
- [ ] Profile → game profile Test → back → Profile
- [ ] Dashboard → Controls → Gestures → choose a gesture → back → Gestures → back → Controls → back → Dashboard
- [ ] Profile → Controls → Voice → back → Controls → back → Profile
- [ ] Dashboard → Voice (config) → back → Dashboard
- [ ] Game Detail → Set up with GabAI → back → Game Detail
- [ ] Game Detail → Edit profile (GabAI, placing buttons) → Done → back → placing buttons → back → Game Detail
- [ ] Dashboard → GabAI → start calibration → back → GabAI Welcome → back → Dashboard
- [ ] GabAI game profile → save → Play → back → the screen GabAI was opened from
- [ ] Setup: permissions step asks for the camera first, then the microphone; back exits to Welcome (camera + mic are required, so Skip is hidden until both are on); cursor calibration → back → permissions → back → Welcome; Voice tutorial likewise
- [ ] Profile → Edit appearance → back → Profile
- [ ] Profile → Redo onboarding → confirm → permissions → cursor calibration → Continue → Voice tutorial → Finish → back on Profile (a replay returns where it was opened, not the Dashboard)
- [ ] Profile → Redo onboarding → Cancel → stays on Profile
- [ ] On Dashboard, saying "back" does nothing (the app stays open)

## Real vs. placeholder in this build

| Area | Status |
|---|---|
| Splash, Welcome, Sign in / Create account / Reset password | Real (sign-in needs Firebase config) |
| Setup: permissions (camera → mic → accessibility service), cursor calibration, needs, appearance | Real. Permissions come first on one screen and the camera is asked for before the microphone, so the pointer is head-tracked rather than gyro-driven when calibration boots it. Camera + mic are required; needs and appearance save to DataStore on Continue |
| Voice tutorial, including TTS read-aloud and speed | Real (Android TextToSpeech), voice-controllable |
| Read aloud (TTS) | Real: speaks the current PWDe screen's contents from its accessibility tree, on arrival or on "read screen". The Voice tutorial toggle writes the same setting as Controls → Voice |
| Input mode (Controls) | Real; switches the tracking output (pointer / joystick) live |
| Gestures + per-gesture sensitivity | Real: 25 gestures plus all 52 MediaPipe blendshapes, saved to Room, live "try it" meter |
| Cursor speed, joystick | Real: live camera, live pointer/joystick; settings saved to Room || Voice configuration | Real: live mic level, on/off, matching and activation modes, command list |
| Testing Station (debug builds only) | Real: live face, gesture, voice, cursor and joystick readouts, plus a sherpa-onnx wake word panel whose tuning gameplay shares |
| Watch Tutorial | Real player with a placeholder video (`res/raw/tutorial_placeholder.mp4`) |
| GabAI | Real: calibration and game-profile flows, resumable after a force-close |
| Games, game detail | Real: play, edit or create profiles per game; voice-selectable |
| Playing view | Live overlay over your game screenshot (or a simulated arena). Mapped buttons are pressed by voice (sherpa-onnx keyword spotting), gesture or joystick |
| Profile | Real: profile lists with rename/delete, "Use now" for calibrations, Play/Edit for game profiles, sync status, and **Redo onboarding** to walk setup again |

## Default controls

A fresh install already maps two gestures, so the pointer is usable before the user has mapped
anything:

| Gesture | Action | Why |
|---|---|---|
| **Smile** | Select | Easiest curated gesture to make on purpose and to stop again; `mouthSmile` left/right averaged |
| **Open mouth** | Recenter | Distinct from a smile and from speech; a single `jawOpen` score |

Both read cleanly from the front camera and are unmistakable in a live preview. Setup (cursor
calibration) and GabAI both say so out loud, and every mapping stays editable in **Controls →
Gestures**. `ControlConfig.gestureAssignments` defaults to this map, so a profile with no saved
assignments — an upgraded install included — picks it up.

## The pointer during calibration

When the accessibility service is on, the live pointer is drawn by a window that covers the whole
display, so it also lands on top of PWDe's own screens. A calibration screen therefore tells the
overlay what it wants via `CalibrationOverlayState` (published from Compose with
`rememberCalibrationOverlay`, read synchronously by `PwdeAccessibilityService`):

- **Cursor calibration** (Setup's step, Controls → Cursor speed, GabAI's axis walkthrough) confines the
  pointer to the calibration box — the camera feed the pointer is drawn over — so it never wanders to a
  corner of the screen the calibration isn't talking about. The whole dot is held inside, inset by its
  radius (`PointerBounds.confine`).
- **Joystick tuning** (Controls → Joystick, GabAI's joystick steps) hides the pointer entirely: a
  joystick is steered by tilting, so a roaming pointer is just noise.

Both revert to normal the moment the screen is left or PWDe is paused, so nothing is confined or
hidden while the user is away playing.

## Known limitations

- **No real game control.** PWDe doesn't launch Clash Royale or Mobile Legends, and it can't press the phone's own hardware or system UI. The accessibility service (`PwdeAccessibilityService`, "Use PWDe") taps, scrolls and drags at the pointer over whatever app is on screen — it never reads what is on screen — but the playing view's own buttons are still mapped by hand.
- **Buttons are placed by hand.** GabAI's button mapping is manual (tap, drag, or "place" at the head pointer). There's no ML button detection in this build.
- **In-game keyword spotting is arm64-only.** On other ABIs gameplay falls back to Android `SpeechRecognizer`, where latency and restart beeps depend on the phone's speech service. Spotter tuning is not saved across app restarts.
- **Cloud sync is a stub.** Signing in never blocks or deletes anything, but profiles only live on this phone for now.
- **Direction and side checks.** Head-pose signs and MediaPipe's left/right blendshape sides were checked in theory and by unit tests. If a direction reads backwards on a device, there's one switch in each: `HeadPose.kt` and `Blendshapes.SIDES_SWAPPED`.
- **Tracking speed.** Face tracking runs on the CPU at roughly 15–30 fps depending on the phone.
- **Release signing.** No release signing config is set up. `assembleRelease` produces an unsigned APK to sign with your own key.

Gesture actions like Notifications, All apps and Touch & hold act inside PWDe's overlay only. System-level actions (Back, Home, Recents, Notifications, All apps) go through the accessibility service, so they do nothing until "Use PWDe" is switched on in Android Settings.
