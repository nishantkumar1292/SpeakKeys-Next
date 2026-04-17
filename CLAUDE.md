# CLAUDE.md — SpeakKeys-Next (HeliBoard Fork)

## What This Project Is

This is a fork of [HeliBoard](https://github.com/Helium314/HeliBoard) (an open-source AOSP-based Android keyboard) being transformed into **SpeakKeys** — a voice-first keyboard with full typing capabilities. The goal is to combine HeliBoard's mature typing engine (autocorrect, word suggestions, swipe/gesture typing, clipboard, multilingual support) with SpeakKeys' cloud-based voice recognition pipeline.

## The Original SpeakKeys

The original SpeakKeys repo lives at `/Users/nishantkumar/Documents/personal/SpeakKeys`. It's a voice-first Android keyboard (IME) with cloud-based speech recognition. Its voice pipeline is the part we're porting into this HeliBoard fork.

## Key Decisions (Resolved)

- **License**: GPL-3.0 for the app module (matches HeliBoard). The `shared/` KMP module stays Apache 2.0.
- **Default mode**: Voice mode is the default. Typing keyboard must be accessible with a **single tap**.
- **Theming**: Use HeliBoard's theming system but ensure visual consistency between voice panel and keyboard.
- **Gesture/swipe typing**: Include it (comes free with the HeliBoard fork).
- **applicationId**: `com.speakkeys.keyboard` (fresh identity for the new project).

## Build Commands

```bash
# Build debug APK (minified, smaller)
./gradlew assembleDebug

# Build debug APK (no minify, faster builds in IDE)
./gradlew assembleDebugNoMinify

# Build release APK
./gradlew assembleRelease
```

## HeliBoard Architecture (What You're Working With)

```
app/src/main/java/helium314/keyboard/
  latin/
    LatinIME.java             — Main InputMethodService (1845 lines)
    App.kt                    — Application class
    InputView.java            — Root view container (FrameLayout)
    RichInputConnection.java  — Enhanced InputConnection wrapper
    WordComposer.java         — Tracks current composing word
    Suggest.kt                — Suggestion engine (queries dictionaries)
    SuggestedWords.java       — Suggestion data model
    DictionaryFacilitator*.kt — Dictionary management
    ClipboardHistoryManager.kt
    inputlogic/
      InputLogic.java         — Core input processing (2781 lines)
      InputLogicHandler.java  — Async suggestion updates
    suggestions/
      SuggestionStripView.kt  — Suggestion bar UI
    dictionary/               — Dictionary interface + implementations
    settings/
      Settings.java           — SharedPreferences-based settings
      SettingsValues.java     — Computed settings snapshot
  keyboard/
    Keyboard.java             — Keyboard data model
    Key.java                  — Individual key data
    KeyboardView.java         — Base keyboard rendering (Canvas)
    MainKeyboardView.java     — Touch handling, key preview, gesture trail
    KeyboardSwitcher.java     — Manages keyboard state transitions
    KeyboardActionListener.java — Interface for key events
    PointerTracker.java       — Multi-touch tracking
    internal/
      BatchInputArbiter.java  — Gesture typing arbitration
      GestureStroke*.java     — Gesture recognition
    clipboard/                — Clipboard keyboard view
    emoji/                    — Emoji keyboard
  settings/                   — Compose-based settings (newer)
app/src/main/jni/             — Native C++ library (dictionary, proximity)
app/src/main/assets/
  dicts/                      — Built-in dictionary files
  layouts/                    — Keyboard layout definitions
```

Key patterns:
- XML-based layouts with **custom Canvas rendering** (NOT Compose for the keyboard itself)
- Native C++ JNI library for dictionary lookup and proximity correction
- `KeyboardSwitcher` manages modes: alphabet, symbols, emoji, clipboard — voice will be another mode
- `InputLogic` is the brain — handles composing regions, autocorrect, undo, recapitalize
- HeliBoard already uses Compose for its settings screens, so Compose dependencies exist
- SharedPreferences for settings (not DataStore)

## What Needs to Be Done (Integration Plan)

### Phase 1: Project Setup

**1.1 Change applicationId and branding**
- In `app/build.gradle.kts`: change `applicationId` from `helium314.keyboard` to `com.speakkeys.keyboard`
- Update `versionCode` to `100` and `versionName` to `v0.1.0` (fresh start)
- Change `base.archivesBaseName` to `SpeakKeys_`
- Update `applicationIdSuffix` for debug builds as appropriate
- Update app name strings in `res/values/strings.xml` (`english_ime_name`)
- Update app icons later (can keep HeliBoard icons for prototype)

**1.2 Add shared KMP module**
- Copy the entire `shared/` directory from `/Users/nishantkumar/Documents/personal/SpeakKeys/shared/` into this repo root
- Add `include ':shared'` to `settings.gradle`
- Add `implementation(project(":shared"))` to `app/build.gradle.kts` dependencies
- The shared module's `build.gradle.kts` already defines its own dependencies (Ktor, kotlinx-serialization, coroutines). You may need to ensure the Kotlin version is compatible — HeliBoard uses Kotlin 2.2.21, SpeakKeys shared module uses what's defined in the root build.gradle. Align if needed.
- The shared module requires the `com.android.kotlin.multiplatform.library` and `org.jetbrains.kotlin.multiplatform` plugins. Add them to the root `build.gradle.kts` plugins block (with `apply false`).

**1.3 Add permissions to AndroidManifest.xml**
HeliBoard has no internet or audio recording permission. Add:
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
```

**1.4 Add Firebase and other SpeakKeys dependencies to `app/build.gradle.kts`**
```kotlin
// Firebase
implementation(platform("com.google.firebase:firebase-bom:34.9.0"))
implementation("com.google.firebase:firebase-auth")

// Credential Manager (for Google Sign-In)
implementation("androidx.credentials:credentials:1.3.0")
implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

// JetPref DataStore (for voice-specific preferences)
implementation("dev.patrickgold.jetpref:jetpref-datastore-model:0.1.0-beta14")
implementation("dev.patrickgold.jetpref:jetpref-datastore-ui:0.1.0-beta14")
implementation("dev.patrickgold.jetpref:jetpref-material-ui:0.1.0-beta14")

// LiveData (for voice UI state)
implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

// Compose Material (HeliBoard uses Material3; voice UI uses Material 1)
implementation("androidx.compose.material:material")
implementation("androidx.compose.material:material-icons-extended")
implementation("androidx.compose.runtime:runtime-livedata")
```

Also add `com.google.gms.google-services` plugin to root `build.gradle.kts` and apply it in `app/build.gradle.kts`.

**1.5 Copy google-services.json**
Copy `/Users/nishantkumar/Documents/personal/SpeakKeys/app/google-services.json` to `app/google-services.json` in this repo.

**1.6 Bump minSdk to 24**
In `app/build.gradle.kts`, change `minSdk = 21` to `minSdk = 24` (required for SpeakKeys' cloud APIs and Compose Material).

### Phase 2: Port Voice Recognition Pipeline

**2.1 Copy these files from original SpeakKeys `app/src/main/java/com/elishaazaria/sayboard/` into this repo under `app/src/main/java/helium314/keyboard/voice/`** (adapt package declarations):

| Original file | New location | Purpose |
|---|---|---|
| `recognition/ModelManager.kt` | `voice/ModelManager.kt` | Orchestrates recognizer lifecycle |
| `recognition/MySpeechService.kt` | `voice/MySpeechService.kt` | Audio capture + recognizer thread |
| `recognition/auth/AndroidAuthTokenProvider.kt` | `voice/auth/AndroidAuthTokenProvider.kt` | Firebase ID token provider |
| `recognition/preferences/AndroidPreferencesRepository.kt` | `voice/preferences/AndroidPreferencesRepository.kt` | Prefs adapter for shared module |
| `auth/AuthManager.kt` | `voice/auth/AuthManager.kt` | Firebase/Credential Manager sign-in |
| `AppPrefs.kt` | `voice/VoicePrefs.kt` | Voice-specific preferences (JetPref) |
| `AppCtx.kt` | `voice/AppCtx.kt` | Application context holder |
| `Constants.kt` | `voice/Constants.kt` | Backspace repeat delays, etc. |
| `data/SimpleEnums.kt` | `voice/data/SimpleEnums.kt` | KeepScreenAwakeMode, HapticIntensity |
| `ime/HapticHelper.kt` | `voice/HapticHelper.kt` | Vibration feedback |
| `ime/IMELifecycleOwner.kt` | `voice/IMELifecycleOwner.kt` | Lifecycle bridge for Compose in IME |
| `utils/AudioDevices.kt` | `voice/utils/AudioDevices.kt` | Audio device enumeration |
| `utils/ModelListPreference.kt` | `voice/utils/ModelListSerializer.kt` | JetPref serializer for model list |

Update all package declarations and imports when copying. The `shared` module's types (`InstalledModelReference`, `RecognizerSource`, `Recognizer`, `RecognitionListener`, `TextProcessor`, etc.) are imported from `com.speakkeys.keyboard.*` — these stay as-is since the shared module retains its original package name.

**2.2 Create VoiceInputManager.kt**
Create `app/src/main/java/helium314/keyboard/voice/VoiceInputManager.kt` — this bridges HeliBoard's `LatinIME` with SpeakKeys' `ModelManager`.

Responsibilities:
- Initialize `ModelManager` with recognizer sources
- Implement `ModelManager.Listener` to receive recognition results
- On voice result: reset HeliBoard's composing state, then commit text via `LatinIME.mInputLogic.mConnection.commitText()`
- After committing, call `LatinIME.mHandler.postUpdateSuggestionStrip()` to resync suggestions
- Manage push-to-talk lifecycle (start/stop recording, hold timers, deferred results)
- The core logic for this already exists in `IME.kt` — port the `viewManagerListener`, hold timers, `onResult`/`onFinalResult`/`onStateChanged`/`onError` callbacks

**2.3 Initialize voice in LatinIME**
In `LatinIME.java`:
- Add a `VoiceInputManager` field
- In `onCreate()`: initialize `VoiceInputManager`
- In `onStartInputView()`: notify voice manager
- In `onFinishInputView()`: stop voice recording
- In `onDestroy()`: destroy voice manager

### Phase 3: Voice UI Integration

**3.1 Create VoiceKeyboardView**
Create a view that hosts the SpeakKeys voice UI using `ComposeView`. The voice panel shows:
- A large mic button (push-to-talk)
- Status text (hold to talk / release to send / processing)
- A symbols bar (?, !, comma, period, etc.)
- Bottom bar with: keyboard toggle, space bar, backspace, settings, enter/action button

Port the Compose UI from these original files:
- `ime/ViewManager.kt` — the main voice layout (`MicArea`, `SymbolsBar`, `BottomBar`, `DragHandle`, `AudioDevicePopup`)
- `theme/SpaceTheme.kt` — all the Space-themed Compose components (`SpaceBackdrop`, `SpacePanel`, `SpaceMicVisual`, `SpaceControlIcon`, `SpaceWordmark`)
- `theme/Color.kt`, `theme/Shape.kt` — theme primitives

The voice UI should be visually consistent with HeliBoard's keyboard theme. For the prototype, keep the Space theme as-is — it already looks good. Theme unification can come later.

**3.2 Register voice mode in KeyboardSwitcher**
`KeyboardSwitcher.java` manages which view is visible. It already handles:
- Alphabet keyboard
- Emoji (`EmojiPalettesView`)
- Clipboard (`ClipboardHistoryView`)

Add VOICE as another mode. Follow the same pattern as emoji/clipboard:
- Add the `VoiceKeyboardView` to `InputView`'s layout
- In `KeyboardSwitcher`, add methods to show/hide the voice view
- Wire the voice toggle button to switch between typing and voice modes

**3.3 Add voice toggle button**
HeliBoard's toolbar (above the suggestion strip) supports custom buttons via `ToolbarKey`. Add a microphone button that toggles to voice mode. Also ensure the voice panel has a keyboard button that returns to typing mode (already exists in SpeakKeys' `BottomBar`).

**Critical UX requirement**: Voice mode is the **default** when the keyboard opens. The user must be able to switch to typing with a **single tap**. Either:
- Open in voice mode with a keyboard icon in the bottom bar (current SpeakKeys behavior), OR
- Open in a split view where mic is prominent but keyboard is visible

### Phase 4: Firebase & Auth

**4.1 Initialize Firebase in App.kt**
In HeliBoard's `App.kt` (`helium314.keyboard.latin.App`), add:
```kotlin
import com.google.firebase.FirebaseApp
// In onCreate():
FirebaseApp.initializeApp(this)
```

Also initialize JetPref DataStore for voice preferences here.

**4.2 Port auth flow**
The `AuthManager.kt` handles Google Sign-In via Credential Manager → Firebase Auth. It needs to be callable from a settings screen. Add a voice settings screen accessible from HeliBoard's settings navigation.

### Phase 5: Settings

Port the voice-specific settings screens from original SpeakKeys:
- `ui/ApiSettingsUi.kt` — API key configuration
- `ui/AccountSettingsUi.kt` — Google Sign-In for proxied backend
- `ui/ModelsSettingsUi.kt` / `ModelsTabUi.kt` — Model ordering
- `ui/LogicSettingsUi.kt` — Voice behavior settings

These should be added as a "Voice Input" section in HeliBoard's settings. HeliBoard's newer settings use Compose Navigation (`SettingsNavHost.kt` in `helium314/keyboard/settings/`).

---

## Files to Copy From Original SpeakKeys

The original SpeakKeys repo is at: `/Users/nishantkumar/Documents/personal/SpeakKeys`

### Shared KMP Module (copy entire directory)
```
shared/                          → shared/
```
This is a self-contained Kotlin Multiplatform module with zero modifications needed. It contains:
- `recognition/recognizers/` — Recognizer/RecognizerSource interfaces
- `recognition/recognizers/sources/` — WhisperCloud, SarvamCloud, ProxiedCloud implementations
- `recognition/recognizers/providers/` — Provider pattern for model discovery
- `recognition/text/TextProcessor.kt` — Capitalization, spacing for voice input
- `recognition/audio/WavEncoder.kt` — WAV encoding for cloud APIs
- `recognition/auth/AuthTokenProvider.kt` — Auth interface (implemented by Android-specific code)
- `data/` — InstalledModelReference, ModelType, SpeakKeysLocale

### Android-specific files (adapt package names)
All files from `app/src/main/java/com/elishaazaria/sayboard/`:
- `recognition/ModelManager.kt` — orchestrates recognizer lifecycle
- `recognition/MySpeechService.kt` — audio capture thread
- `recognition/auth/AndroidAuthTokenProvider.kt` — Firebase token provider
- `recognition/preferences/AndroidPreferencesRepository.kt` — prefs adapter
- `auth/AuthManager.kt` — Firebase + Credential Manager sign-in
- `AppPrefs.kt` — JetPref preference model (voice settings)
- `AppCtx.kt` — static application context holder
- `Constants.kt` — backspace repeat delays
- `data/SimpleEnums.kt` — KeepScreenAwakeMode, HapticIntensity
- `ime/HapticHelper.kt` — vibration helper
- `ime/IMELifecycleOwner.kt` — lifecycle bridge for Compose in IME
- `ime/ViewManager.kt` — voice UI Compose components (extract MicArea, SymbolsBar, BottomBar, etc.)
- `ime/QwertyLayout.kt` — NOT needed (HeliBoard has its own keyboard)
- `theme/SpaceTheme.kt` — space-themed Compose components
- `theme/Color.kt` — theme color definitions
- `theme/Shape.kt` — shape definitions
- `utils/AudioDevices.kt` — audio device enumeration
- `utils/ModelListPreference.kt` — JetPref serializer

### Config files
- `app/google-services.json` → `app/google-services.json`

### String resources
Copy voice-related strings from `/Users/nishantkumar/Documents/personal/SpeakKeys/app/src/main/res/values/strings.xml` — specifically:
- `mic_info_*` strings (hold to talk, release to send, processing, etc.)
- `mic_error_*` strings
- `mic_audio_device_title`
- `ime_action_*` strings (enter, go, search, send, etc.)
- `p_keep_screen_awake_mode_*`
- `haptic_intensity_*`
- Any other strings referenced by the ported voice code

### XML resources
- `app/src/main/res/xml/method.xml` — the IME method descriptor (may need merging with HeliBoard's)
- Check for any other XML resources referenced by voice code

---

## Important Technical Notes

### Text Insertion from Voice
SpeakKeys' `TextManager` uses `InputConnection.commitText()` for final voice results. In HeliBoard, use `RichInputConnection.commitText()` instead, accessed via `LatinIME.mInputLogic.mConnection`. Before inserting voice text:
1. Reset any active composing region: `mInputLogic.mConnection.finishComposingText()`
2. Run voice text through `TextProcessor.processText()` for capitalization/spacing
3. Commit via `mInputLogic.mConnection.commitText(processedText, 1)`
4. Update suggestions: `mHandler.postUpdateSuggestionStrip()`

### Lifecycle
HeliBoard's `LatinIME` extends `InputMethodService` directly. SpeakKeys wraps a custom `IMELifecycleOwner` for Compose support. You'll need to attach `IMELifecycleOwner` to the decor view in `LatinIME.onCreateInputView()` for the Compose-based voice panel to work.

### Audio Recording
`MySpeechService` creates an `AudioRecord` at 16kHz mono PCM 16-bit. It runs on a background thread, feeding audio to the `Recognizer` interface. Results come back on the main thread via `RecognitionListener`.

### Push-to-Talk Flow
1. User presses mic → `ModelManager.start()` → `MySpeechService` begins recording
2. Audio feeds into recognizer → partial results arrive
3. User releases mic → `ModelManager.stop()` → final result committed to text field
4. Hold timers: warning at 27s, auto-stop at 30s

### Recognizer Backends (in shared module)
- **WhisperCloud** — OpenAI Whisper API (requires user's OpenAI API key)
- **SarvamCloud** — Sarvam AI API (requires Sarvam API key)
- **ProxiedCloud** — SpeakKeys proxy backend authenticated via Firebase (default, free for users)

Each backend implements `RecognizerSource` → creates `Recognizer` instances. `ModelManager` manages switching between them.

---

## Build Config Alignment

| Setting | HeliBoard (current) | SpeakKeys (original) | Target |
|---|---|---|---|
| applicationId | `helium314.keyboard` | `com.speakkeys.keyboard` | `com.speakkeys.keyboard` |
| minSdk | 21 | 24 | **24** |
| targetSdk | 35 | 35 | 35 |
| compileSdk | 35 | 35 | 35 |
| Kotlin | 2.2.21 | 2.2.10 | 2.2.21 (HeliBoard's) |
| AGP | plugins block | 9.0.0 | Keep HeliBoard's |
| NDK | 28 | none | 28 (HeliBoard's native code) |
| Compose BOM | 2025.11.01 | 2023.10.00 | 2025.11.01 (HeliBoard's) |
| Java | 17 | 17 | 17 |

---

## Definition of Done (Prototype)

A working prototype means:
1. App builds and installs as `com.speakkeys.keyboard`
2. Opens in **voice mode** by default with the Space-themed voice panel
3. Push-to-talk works: hold mic → speak → release → text appears in the input field
4. Single tap switches to HeliBoard's full keyboard (with autocorrect, suggestions, swipe)
5. Single tap switches back to voice mode
6. All three voice backends work (Whisper, Sarvam, Proxied)
7. Firebase auth works for the Proxied backend
8. HeliBoard's existing features still work (typing, autocorrect, suggestions, emoji, clipboard)
