# SpeakKeys for Mac

A standalone macOS **push-to-talk dictation** app: hold a key, speak, release — and
your words are inserted as text **wherever your cursor is**, in any app (Notes,
Slack, browser, terminal, IDE, anywhere).

It lives in a menu-bar item (a microphone icon); there is no Dock icon and no
keyboard UI — you keep using your physical keyboard and reach for the mic only when
you want to dictate.

> This folder is **completely independent** of the Android/Gradle project in the repo
> root. It's a plain Swift Package — no Gradle, no shared build. (The cloud engine
> endpoints intentionally match the Android app's `shared/` recognizers so behavior
> is consistent across platforms.)

---

## How it works

1. **Hold the right Option (⌥) key** anywhere in macOS → recording starts (icon turns
   red, menu says "Listening…").
2. **Speak.**
3. **Release ⌥** → audio is transcribed and the text is inserted at your cursor.

The trigger is a global, listen-only event tap, so holding ⌥ never interferes with
normal use of the Option key.

## Pluggable speech engines

Engines are swappable from the menu bar. Three ship today:

| Engine | Network | Setup | Notes |
|---|---|---|---|
| **Apple (on-device)** *(default)* | none | just grant Speech permission | Free, private, offline on Apple Silicon. |
| **OpenAI Whisper (cloud)** | yes | paste OpenAI API key | `whisper-1`. High accuracy, many languages. |
| **Sarvam AI (cloud)** | yes | paste Sarvam API key | `saaras:v3`. Strong for Indic languages. |

**Adding another engine** (e.g. a local whisper.cpp server, Deepgram, Google) is
deliberately a one-file change:

1. Create a class conforming to [`SpeechEngine`](Sources/SpeakKeysMac/SpeechEngine.swift)
   — implement `beginUtterance()`, `append(_:)` (receives 16 kHz mono Int16 buffers),
   and `finishUtterance() async`.
2. Add it to the `engines` array in
   [`EngineManager`](Sources/SpeakKeysMac/EngineManager.swift).

That's it — it shows up in the menu, gets the audio stream, and its transcript is
inserted automatically. Cloud engines can reuse the WAV encoder, audio accumulator,
and multipart HTTP helpers in [`CloudSupport.swift`](Sources/SpeakKeysMac/CloudSupport.swift).

## Text insertion modes (menu → "Insert text by")

- **Pasting** *(default)* — copies the text, synthesizes ⌘V, then restores your
  previous clipboard. Fast and works in essentially every text field.
- **Typing keystrokes** — synthesizes Unicode key events directly; never touches the
  clipboard, but slower for long text and a few apps ignore it.

---

## Build & run

Requires Xcode / the Swift toolchain (this was built with Swift 6.3, macOS 26).

```bash
cd mac
./setup_signing.sh       # ONCE: create a stable self-signed signing identity
./make_app.sh            # builds, assembles SpeakKeys.app, signs it
open build/SpeakKeys.app # launches the menu-bar app (no Dock icon)
```

**Run `./setup_signing.sh` once before your first build.** It creates a self-signed
code-signing certificate and trusts it. This matters a lot: macOS TCC (Accessibility,
Input Monitoring, …) ties permission grants to the app's code identity. With ad-hoc
signing the identity changes every rebuild, so macOS keeps "forgetting" your grants
(and often won't honor Accessibility for an ad-hoc app at all). A stable, *trusted*
self-signed identity makes grants persist across every rebuild. `make_app.sh` signs
with it automatically when present.

For quick iteration you can also just `swift build` and run, but **always launch the
`.app` bundle** for real use — macOS attaches permissions to the signed bundle, and the
bare binary has no `Info.plist`.

## Permissions (first run)

The menu's "Permissions" section shows live ✓ / ✗ status (re-checked every time you
open the menu); clicking a row jumps to the right Settings pane. Grant all of:

1. **Accessibility** *(required)* — to **insert text** (post the paste/keystrokes) and
   to create the event tap. Privacy & Security → **Accessibility** → enable SpeakKeys.
2. **Input Monitoring** *(required)* — to **receive** the ⌥ key events in the tap. This
   is a *separate* pane from Accessibility, and without it the tap is created but gets
   no key events (the #1 "hotkey does nothing" gotcha). Privacy & Security →
   **Input Monitoring** → enable SpeakKeys.
3. **Microphone** — to record audio.
4. **Speech Recognition** — for the Apple engine.

Once Accessibility + Input Monitoring are on, the hotkey activates within ~1 second
(the app polls and self-activates — no relaunch needed).

### Enable Dictation (required for the Apple engine)

The Apple engine uses `SFSpeechRecognizer`, which is gated behind macOS Dictation. If
Dictation is off it fails with *"Siri and Dictation are disabled"* (`kLSRErrorDomain`
201) and nothing is transcribed. Turn it on once:

**System Settings → Keyboard → Dictation → On** (confirm the model download if asked).

The engine prefers on-device recognition (offline) and falls back to server-based if
the on-device model isn't ready. Cloud engines (Whisper/Sarvam) don't need Dictation.

## Cloud engine setup

Menu → **Set OpenAI API Key…** or **Set Sarvam API Key…**, paste the key, then select
that engine from the menu. Keys are stored in `UserDefaults` for now (not encrypted) —
move them to the Keychain before distributing.

---

## Known limitations / next steps

- **Signing**: uses a trusted self-signed identity from `setup_signing.sh` so grants
  persist across rebuilds. The cert lives in your login keychain; if you ever delete
  it, re-run `setup_signing.sh` and re-grant permissions once. For distribution to
  other machines, sign with a Developer ID instead.
- **Trigger key** is fixed to right Option. Making it user-configurable is a small
  change in `GlobalHotkeyMonitor` + a menu picker.
- **Diagnostics**: the app writes a log to `/tmp/speakkeys.log` (one line per
  dictation cycle). `tail -f /tmp/speakkeys.log` while testing.
- **No live partial-text overlay** yet — text appears on release. Apple's engine does
  produce partials (`shouldReportPartialResults = true`) if you want to add a HUD.
- API keys belong in the Keychain (see above).

## Source layout

```
mac/
  Package.swift                  Swift package (executable, Swift 5 language mode)
  Info.plist                     bundle metadata + TCC usage strings
  setup_signing.sh               ONCE: create + trust a self-signed signing identity
  make_app.sh                    build → .app bundle → sign with that identity
  Sources/SpeakKeysMac/
    main.swift                   NSApplication bootstrap (.accessory)
    AppDelegate.swift            wiring + permission bootstrap
    GlobalHotkeyMonitor.swift    right-Option event tap (push-to-talk)
    AudioRecorder.swift          AVAudioEngine capture → 16 kHz mono Int16
    AudioFormats.swift           canonical capture format
    SpeechEngine.swift           the pluggable engine protocol
    EngineManager.swift          engine registry + active selection
    Engines/
      AppleSpeechEngine.swift    SFSpeechRecognizer (default)
      WhisperCloudEngine.swift   OpenAI Whisper
      SarvamCloudEngine.swift    Sarvam AI
    CloudSupport.swift           WAV encoder, audio accumulator, multipart HTTP
    TextInserter.swift           paste / keystroke insertion at the cursor
    DictationController.swift    orchestrates one push-to-talk cycle
    MenuBarController.swift      status item + menu (engines, keys, perms)
    Permissions.swift            mic / speech / accessibility helpers
    Settings.swift               UserDefaults-backed preferences
```
