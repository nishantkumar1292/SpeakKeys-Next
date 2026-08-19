# SpeakKeys voice-recognition architecture

## Product goal

Voice input should feel immediate without making users understand model runtimes or
cloud vendors. The normal picker therefore describes the result a person can expect:
mixed Hindi and English, offline privacy, download size, and whether the option is a
good fit for the current phone. Provider names and benchmark details belong under an
optional technical-details section.

## Evidence behind the first catalog

There is no single public benchmark that establishes one universal "best Hinglish"
recognizer. SpeakKeys now separates two useful online choices:

- **Natural Hinglish** uses the open-weight Trelis Tara model on a SpeakKeys GPU server.
  Tara has the strongest broad, reproducible evidence among the downloadable models we
  found: 14.41 WER on conversational CoSHE-500, 8.37 on code-switched FLEURS, 12.93 on
  HiACC adult, and 10.69 on HiACC child. Its exact normalizer, evaluation code, and
  predictions are public. These are still publisher-run results, and CoSHE uses oracle
  per-clip routing, so this is a best-supported candidate rather than a universal winner.
- **Fast Hindi + English** uses Sarvam Saaras v3 Realtime. Saaras v3 has the lowest
  reported WER on Trelis' conversational CoSHE-500 evaluation (11.25 versus 12.40 for
  ElevenLabs Scribe v2 and 14.41 for Tara) and supplies native partial transcripts.
- Voice of India's larger unscripted Indian telephony evaluation also reports the
  tested Sarvam system leading 13 of 15 languages. That result is supporting Indian
  speech evidence, not an isolated public Hinglish score, and the paper does not prove
  that its system is byte-for-byte the public realtime model.
- Sarvam documents its current Saaras v3 Realtime WebSocket with manual endpointing,
  16 kHz linear PCM, partial transcripts, and a code-mixing mode. Audio is sent while
  the user speaks, removing SpeakKeys' present record-then-upload delay.

ElevenLabs Scribe v2 Realtime is the secondary online choice. Its batch model is close
on conversational Hinglish and leads some read/code-switched sets, while its realtime
API documents partial transcripts and manual commit. Accuracy of both realtime models
must still be measured on the same SpeakKeys-owned audio before release; batch scores
must not be presented as realtime guarantees.

Sources:

- https://github.com/TrelisResearch/tara#code-switching-hinglish
- https://huggingface.co/Trelis/tara
- https://arxiv.org/abs/2604.19151
- https://docs.sarvam.ai/api-reference/speech-to-text/transcribe/realtime/ws
- https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime

## Reversible engine boundary

Every recognition attempt is an utterance with an explicit lifecycle:

```text
prepare -> start -> zero or more provisional transcripts -> finish -> final
                     |                                  |
                     +-------------- cancel -----------+
```

- App-audio engines receive the same 16 kHz mono PCM frames. Sarvam, ElevenLabs, Vosk,
  and future whisper.cpp adapters use this path.
- Engine-audio recognizers own their microphone. Android's on-device speech service
  uses this path because the platform API does not accept SpeakKeys' PCM buffers.
- `finish` may return a final transcript; `cancel` never transcribes, uploads, or
  commits buffered audio.
- Provisional text replaces the prior provisional text. It is visible but never
  inserted into the target app. Only a final event may commit.
- Each utterance has an identity. Callbacks from an older or cancelled utterance are
  ignored, preventing text from appearing in a later field.

The existing KMP `Recognizer` API remains available through adapters during migration.
This avoids tying HeliBoard's composing and suggestion logic to a network transport or
native runtime.

## Hosted low-latency path

Direct, user-key Sarvam and ElevenLabs connections use their providers' native realtime
APIs. The existing signed-in "SpeakKeys online" Cloud Function still accepts a complete
WAV after release; no Android-only change can turn that HTTP endpoint into a stream. Its
UI therefore labels it as the slower convenience route.

1. Open the WebSocket at mic start (or reuse a short-lived prepared connection where
   provider terms allow it).
2. Keep at most roughly three seconds of PCM while DNS/TLS/socket setup completes.
3. Send 40 ms 16 kHz PCM frames continuously rather than creating a WAV after release.
4. Use manual endpointing for push-to-talk. Release sends `speech_end`/commit immediately,
   avoiding a server-side silence timeout.
5. Display partial text as provisional. Sarvam applies its requested code-mixing output
   mode to final transcripts, so partial script changes are expected.
6. Close or discard the session after a bounded final-result timeout.

SpeakKeys never silently races two online engines: that would duplicate cost and expose
audio to an unexpected service. Direct connections use the user's own key. A product
key must later be protected by an authenticated relay or provider-issued ephemeral
token rather than shipped in the APK. ElevenLabs socket authentication is already
supplied per connection so a future relay can mint a fresh single-use token without an
engine rewrite. User-provided keys are stored as AES-GCM authenticated ciphertext in
no-backup storage, with the encryption key held by Android Keystore; saved keys are
never shown back in the settings UI.

### Product backend needed for low-latency no-key use

The reversible production design is a small authenticated session broker, not a model
SDK embedded into the keyboard:

```text
Firebase sign-in -> short-lived SpeakKeys session -> India-region realtime relay/provider token
                                                        |
microphone PCM (40 ms frames) --------------------------+
                                                        |
partial/final transcript <------------------------------+
```

- Authenticate once when preparing the session, outside the microphone start path.
- Prefer provider-issued single-use credentials when available; otherwise proxy a
  WebSocket with bounded backpressure and no audio persistence.
- Keep the relay in the closest supported Indian region and reuse connections only for
  a short, explicit lifetime.
- Never place the product provider key in the APK, log raw audio/transcripts, or retry
  deterministic authentication and request errors.
- Roll this out behind the existing recognizer boundary. Direct BYOK and local engines
  remain independent fallbacks, so changing relay/provider is reversible.

Until that server endpoint exists, the app must not claim that signed-in/no-key voice
has received the realtime latency improvement.

### Tara: streamed upload, final decode

Tara is a Whisper-large-v3 derivative and processes windows of at most 30 seconds. It is
not a native streaming transducer. The correct first implementation is therefore:

```text
keyboard shown -> authenticate and pre-open WSS
mic down       -> send raw 16 kHz PCM16 binary frames continuously
mic release    -> send input.commit; run one hot-GPU Tara decode
server final   -> commit text into the editor
```

This overlaps network upload with speech and removes provider silence endpointing while
preserving the model's evaluated full-window behavior. Speculative rolling partials are
not enabled initially: repeatedly encoding the growing audio can consume GPU capacity,
delay the final request, and produce flickering text.

The app's stable model path is `speakkeys://tara`. Its persisted model family reuses the
existing proxied-Whisper value, which keeps older app versions able to deserialize voice
preferences during rollback. The Tara card is compiled into the usable catalog only when
`speakkeys.taraRealtimeEndpoint` (or `SPEAKKEYS_TARA_REALTIME_ENDPOINT`) contains a real
encrypted `wss://` endpoint.

The server implementation lives under `server/tara` and uses a versioned protocol:

- Firebase bearer authentication during connection preparation, not mic release;
- `X-SpeakKeys-Protocol: 1` negotiation;
- JSON session/commit/final control messages and binary PCM frames;
- a 30-second hard limit, bounded GPU queue, one process per GPU, and no batch wait;
- no logging of audio, transcripts, bearer tokens, or user identifiers;
- model revision pinning and readiness only after a warm-up decode.

Tara adds `<|mixedcode|>` between the Hindi language token and transcription task token.
Production uses the raw CTranslate2 Whisper API with explicit prompt IDs rather than the
stock faster-whisper high-level transcription wrapper. Hugging Face Transformers BF16 is
the correctness oracle; any TensorRT or quantized path must first pass transcript and WER
parity gates.

For Indian users, place always-warm replicas behind regional TLS ingress in Mumbai. One
NVIDIA L4 is the cost-conscious launch shape and is available in all three Mumbai zones.
Benchmark the same request path on G4 Blackwell before changing the pinned CUDA runtime.
An A3 Edge H100 is the absolute-latency option available in Mumbai, but it comes only as
an eight-GPU machine and should be an explicit capacity decision. Audio connects directly
to GPU ingress; Firebase Functions may broker a short-lived session credential but must
not relay media.

## Local path

The first local catalog favors options that can be exercised on ordinary Android
phones without enlarging every APK:

- The phone's on-device speech service on supported Android devices. Android 13 and
  newer can verify exact installed language support; Android 12 cannot expose that
  inventory, so SpeakKeys labels the limitation and enables it only after an explicit
  user choice. Automatic Hindi/English switching is never promised because OEM speech
  services may ignore the platform request.
- Vosk small Hindi (42 MB download) for mostly-Hindi, low-latency dictation.
- Vosk small Indian English (36 MB download) for mostly-English dictation.

Vosk is intentionally not labeled the best Hinglish model; its published Hindi and
Indian-English tests do not establish mixed-language quality. Multilingual whisper.cpp
Tiny/Base/Small remain the next local track after JNI/runtime and physical-device
profiling. The official whisper.cpp reference figures are about 75 MB on disk and
273 MB of memory for multilingual Tiny, or 142 MB and 388 MB for Base, before app and
device-specific overhead. A large Hinglish-specific Qwen/Srota model remains experimental until its
memory, thermals, unseen-speaker accuracy, and conversion parity are demonstrated.

Adding whisper.cpp is a product-size decision rather than a catalog-only change. The
lean tier (verified Android engine plus Hindi and Indian-English Vosk downloads) stays
the default implementation until the larger native runtime is explicitly approved.

Downloaded archives are resumable, size- and SHA-256-verified, extracted into a
temporary directory with zip-slip protection, then atomically installed under
`noBackupFilesDir/speech-models`. A model is discoverable by the recognizer registry
only after a complete install marker exists.

Sources:

- https://developer.android.com/reference/android/speech/SpeechRecognizer
- https://alphacephei.com/vosk/models
- https://github.com/ggml-org/whisper.cpp
- https://huggingface.co/moorlee/qwen3-asr-0.6b-hinglish

## Selection and privacy rules

- Choosing an online option is explicit and visibly labeled "Uses internet".
- Choosing a local option is visibly labeled "Works offline" and "Audio stays on this
  phone".
- "Automatic" must not be enabled until a deterministic policy exists for privacy,
  connection state, download state, and retry behavior. The old priority-order UI did
  not implement the fallback it described and is removed.
- Output script (mixed Devanagari/Latin versus all Latin) is a separate user choice from
  recognition engine selection.
- Password and `noMicrophone` fields must hide or disable the SpeakKeys mic.

## Measurement and release gates

Record these metrics per engine with no transcript or raw audio in telemetry:

- mic-to-ready, optional mic-to-first-partial, release-to-final, and end-to-end latency;
- connection setup, first audio send, provider request ID, timeout/error class;
- cold/warm local model load, real-time factor, peak PSS/native heap, battery, and
  thermal state.

Report p50 and p95, separated by device class, network class, and speech style. Accuracy
testing uses the same consented audio for every engine and measures mixed-script WER,
transliteration-normalized WER, named-entity recall, and the user-centered "usable
without editing" rate.

Before release, exercise rapid start/cancel loops, process death, corrupt/interrupted
downloads, low storage, microphone contention, Bluetooth routing, airplane mode,
password fields, and engine switching. Local-mode network capture must show zero audio
requests. Emulator tests cover UI and lifecycle; latency, memory, audio routing, and
thermals require physical low-, mid-, and high-memory phones.
