<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="112" height="112" alt="SpeakKeys app icon">
</p>

<h1 align="center">SpeakKeys</h1>

<p align="center"><strong>A voice-first Android keyboard with full typing support.</strong></p>

SpeakKeys is an open-source Android keyboard that combines cloud speech recognition with a mature, customizable typing experience. Its microphone lives in the suggestion strip, so speaking, typing, autocorrect, emoji, and clipboard tools stay available in one keyboard.

> [!NOTE]
> SpeakKeys is under active development. Voice transcription requires an internet connection and at least one configured cloud engine; ordinary typing remains available without a voice engine.

## How voice typing works

1. Tap the microphone at the right side of the suggestion strip to start recording.
2. Speak while the strip displays an animated waveform.
3. Tap the microphone again to stop. Recording also stops automatically after 30 seconds.
4. SpeakKeys displays a transcription indicator, inserts the result into the active text field, and refreshes suggestions.

Voice results use the surrounding text for spacing and capitalization. Transcription currently runs after recording stops rather than appearing live.

## Voice engines

SpeakKeys supports three configuration paths. Their priority can be reordered in **Settings > Voice Input**.

| Engine | Setup | Audio route |
| --- | --- | --- |
| SpeakKeys Auto | Sign in with Google | Through the authenticated SpeakKeys proxy to Sarvam |
| OpenAI Whisper | Add your own OpenAI API key | Directly to OpenAI |
| Sarvam Cloud | Add your own Sarvam API key | Directly to Sarvam |

Voice settings also include Whisper language and prompt controls, Hindi-to-Roman transliteration, Sarvam native-script or Roman output, and automatic capitalization.

## Full keyboard features

- Suggestions, autocorrection, spell checking, and personal dictionaries
- Multilingual typing and customizable layouts
- Themes, colors, sizing, number row, and configurable toolbar
- Emoji keyboard and search, clipboard history, and text-editing tools
- One-handed, split-keyboard, and number-pad layouts
- Backup and restore for settings and learned data
- Optional glide typing with a separately supplied compatible library; the library is not bundled

The typing engine is inherited from HeliBoard and continues to run on-device. SpeakKeys adds its voice pipeline without replacing the full keyboard.

## Get started

SpeakKeys requires Android 7.0 or newer.

1. Install and launch SpeakKeys.
2. Follow onboarding to grant microphone access, enable the keyboard, and select it as the current input method.
3. Open **Voice Input** from SpeakKeys settings.
4. Sign in for SpeakKeys Auto, or add an OpenAI or Sarvam API key.
5. Open any text field and use the microphone in the suggestion strip.

You can return to the SpeakKeys app at any time to check whether the keyboard is enabled, see the selected voice engine, change settings, or use the built-in test field.

## Voice privacy and network access

Voice transcription relies on:

- `RECORD_AUDIO` to capture speech after you start voice input.
- `INTERNET` to authenticate when needed and send recorded audio to the selected transcription service.

Direct-provider API keys and app preferences are stored on the device. Voice audio leaves the device for cloud transcription, and the selected provider's terms and data-handling policies apply. Google/Firebase account information is used only when you choose SpeakKeys Auto.

The inherited keyboard also declares Android permissions for features such as haptic feedback, user dictionaries, initialization after reboot, and optional contact-name suggestions.

See the [SpeakKeys Privacy Policy](PRIVACY_POLICY.md) for details.

## Build from source

### Requirements

- Android Studio with Android SDK Platform 36
- Android NDK `28.0.13004108`
- JDK 21 (used by CI and by the Android 16 unit-test environment)
- A Firebase Android configuration registered for `com.speakkeys.keyboard`

The Gradle wrapper is included; a separate Gradle installation is not needed.

### Firebase configuration

Normal app builds require a real `google-services.json`:

1. Register the Android app `com.speakkeys.keyboard` in your Firebase project.
2. Enable Google authentication and configure its OAuth client if you need SpeakKeys Auto.
3. Download the configuration file to `app/google-services.json`.

That file is intentionally ignored by Git. The tracked file under `app/src/runTests/` contains dummy values for the CI-only test variant and must not be used for a production build.

### Commands

```bash
# Fast development APK
./gradlew :app:assembleDebugNoMinify

# Unit tests used by pull-request CI
./gradlew :app:testRunTestsUnitTest

# Android lint
./gradlew :app:lintDebug

# Play Store bundle
./gradlew :app:bundleRelease
```

An upload-ready release also needs the SpeakKeys release keystore values described in `app/build.gradle.kts`.

## Project structure

- `app/` — Android IME, typing engine, onboarding, settings, voice UI, and the bridge that commits transcripts into text fields
- `shared/` — Kotlin Multiplatform audio encoding, text processing, and cloud-recognizer implementations
- `tools/make-emoji-keys/` — utility for generating emoji-key data

## Contributing

Issues and focused pull requests are welcome. Before submitting a change, run the relevant unit tests and lint checks, preserve existing license notices, and call out any change that affects microphone, network, authentication, or text-handling behavior.

## License and upstream acknowledgements

The SpeakKeys Android app is licensed under the [GNU General Public License v3.0](LICENSE). The shared Kotlin Multiplatform module remains under Apache 2.0 terms, and inherited AOSP components retain their [Apache 2.0](LICENSE-Apache-2.0) notices. Some inherited artwork is covered by [CC BY-SA 4.0](LICENSE-CC-BY-SA-4.0).

SpeakKeys builds on HeliBoard's keyboard engine. HeliBoard itself descends from [OpenBoard](https://github.com/openboard-team/openboard) and the [AOSP LatinIME](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/) project. SpeakKeys retains the copyright and license notices of those projects and their contributors.
