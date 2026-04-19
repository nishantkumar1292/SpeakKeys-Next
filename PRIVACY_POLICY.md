# Privacy Policy for SpeakKeys

**Effective date:** 19 April 2026
**Developer/Publisher name:** SpeakKeys Labs
**Contact email:** nishantkumar1292@gmail.com
**Package name:** com.speakkeys.keyboard

SpeakKeys ("the App") is an Android voice keyboard (IME). This Privacy Policy explains how the App handles user data.

## 1) What data the App handles

### a) Microphone audio
When you use voice typing, the App records audio from your microphone to transcribe speech to text.

### b) Transcription content
Audio is converted to text and inserted into the app where you are typing.

### c) API keys you provide
If you use a cloud transcription provider directly, you may enter your own API keys in **Settings > API** (for example, OpenAI and/or Sarvam AI). These keys are stored locally on your device.

### d) Account information (only if you sign in)
If you choose the **SpeakKeys Proxied** transcription backend, the App authenticates you via Google Sign-In using Firebase Authentication. This involves:
- Your Google account email and a unique user identifier issued by Firebase.
- An authentication token (Firebase ID token) sent with each transcription request to verify your account.

You are not required to sign in unless you use the Proxied backend.

### e) App settings
The App stores local preferences (selected provider, keyboard settings, voice settings) on your device.

## 2) How data is used

The App uses the above data only to provide core app functionality, including:
- voice-to-text transcription,
- keyboard behavior and preferences,
- provider configuration based on your settings,
- authenticating requests to the SpeakKeys Proxied backend (if used).

## 3) Transcription providers

SpeakKeys supports the following transcription backends. You choose which one to use.

- **SpeakKeys Proxied (default)** — Audio is sent over HTTPS to a SpeakKeys-operated proxy server, which forwards it to an upstream transcription provider. Requests are authenticated via Firebase ID token. Audio is not retained on SpeakKeys servers beyond what is needed to complete the transcription.
- **OpenAI (Whisper)** — Audio is sent directly to OpenAI using the API key you provide. See https://openai.com/policies.
- **Sarvam AI** — Audio is sent directly to Sarvam AI using the API key you provide. See https://www.sarvam.ai/privacy-policy.

When you use a third-party provider (directly or via the proxy), that provider's terms and privacy policy also apply to how they process your data.

## 4) Third-party services

- **Firebase Authentication (Google LLC)** — Used only if you sign in for the Proxied backend. Governed by Google's privacy policy: https://policies.google.com/privacy.
- **Google Sign-In / Credential Manager** — Used to sign in to Firebase. Governed by the same policy above.

## 5) Data sharing and sale

- We do **not** sell personal data.
- We do **not** share your data for advertising purposes.
- Data is transmitted only as needed to provide transcription through the provider you choose, or to authenticate Proxied-backend requests.

## 6) Data retention

- The App does not maintain a separate backend server for storing your voice recordings.
- The SpeakKeys Proxied backend does not retain transcribed audio beyond the time required to fulfill a transcription request.
- Firebase Authentication retains account metadata (email, user ID, sign-in timestamps) for as long as you keep the account active. You may request deletion by contacting us.
- API keys and settings are stored locally on your device until you change or clear them.
- Upstream transcription providers may retain/process data according to their own policies.

## 7) Security

- Data sent to cloud providers and the Proxied backend is transmitted over encrypted connections (HTTPS/TLS).
- No method of transmission or storage is 100% secure, but we take reasonable steps to protect data handled by the App.

## 8) Permissions used

SpeakKeys requests:
- **RECORD_AUDIO** — to capture voice input for speech-to-text.
- **INTERNET** — to send audio to the selected transcription provider and to authenticate Proxied-backend requests.

## 9) Children's privacy

SpeakKeys is not directed to children under 13. If you believe a child provided personal data, contact us and we will take reasonable steps to address it.

## 10) Your choices

You can:
- stop using voice typing at any time,
- revoke microphone permission in Android settings,
- sign out of your Google account in-app (if you used the Proxied backend),
- remove API keys in **Settings > API**,
- request deletion of your Firebase account data by emailing us,
- uninstall the App to remove locally stored app data.

## 11) Changes to this policy

We may update this Privacy Policy from time to time. Updates will be posted at the same policy URL with a revised effective date.

## 12) Contact

For privacy questions or requests, contact: **nishantkumar1292@gmail.com**
