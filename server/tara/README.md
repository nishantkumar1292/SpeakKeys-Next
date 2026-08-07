# SpeakKeys Tara server

This module serves the Apache-2.0 `Trelis/tara` weights through the versioned
SpeakKeys voice protocol. It is deliberately separate from the Android app and from
Firebase Functions, so the GPU vendor and inference runtime can be replaced without
changing the keyboard's model identity.

## Latency-critical path

```text
Firebase sign-in (before mic press)
        |
        v
pre-opened WSS connection -----------------------------+
        |                                               |
16 kHz PCM16 binary frames while the user speaks ------>+ hot GPU worker
                                                        |  CTranslate2 FP16
mic release -> input.commit ---------------------------->+  one Tara decode
                                                        |
transcript.final <--------------------------------------+
```

Tara is a Whisper-large-v3 derivative, not a native streaming transducer. The first
release therefore uploads audio concurrently with speech but performs one deterministic
decode at release. This removes post-release upload and silence-endpoint delays without
the cost and instability of repeated speculative decodes.

The phone connects directly to the GPU ingress. Do not relay audio through Firebase
Functions. Firebase supplies identity; a future lightweight broker can exchange that
identity for a short-lived session token without changing the WebSocket contract.

## Why direct CTranslate2

Tara requires this exact decoder prefix:

```text
<|startoftranscript|>, <|hi|>, <|mixedcode|>, <|transcribe|>, <|notimestamps|>
```

The raw CTranslate2 Whisper API accepts explicit prompt token IDs and officially supports
conversion from Transformers-compatible Whisper checkpoints. Stock faster-whisper does
not place Tara's added `mixedcode` token in this position. Transformers BF16 should be
kept as the transcript-parity oracle; TensorRT is an optional later optimization only
after the custom vocabulary has an audited conversion.

- Tara usage and limits: https://huggingface.co/Trelis/tara
- CTranslate2 Whisper conversion: https://opennmt.net/CTranslate2/guides/transformers.html#whisper
- CTranslate2 Whisper API: https://opennmt.net/CTranslate2/python/ctranslate2.models.Whisper.html

## Prepare a pinned model

Never deploy the mutable model branch. Resolve and review a Hugging Face commit, then:

The integration's current deployment candidate is
`4dd4782d65ed8c9690b52478e4419d577fff40be`. It remains a candidate until the parity
and private-corpus gates below pass; changing it is an explicit model rollout.

```bash
python3 -m venv .builder-venv
. .builder-venv/bin/activate
pip install -r requirements-builder.txt
pip install --no-deps -e .
python3 scripts/download_and_convert.py \
  --revision <lowercase-40-hex-commit-sha> \
  --output-root /models
```

The script downloads the exact revision, converts it to FP16 CTranslate2, verifies the
mixed-code token, and records SHA-256 and size metadata for every processor and converted
model file. Every serving worker verifies that manifest before loading the model. Builder
dependencies, including PyTorch, are pinned separately and are not installed in the serving
image.

The serving image likewise installs the exact top-level versions in
`requirements-runtime.txt`, then installs this package without allowing its broader
metadata ranges to alter the resolved runtime.

Before rollout, compare CTranslate2 FP16 transcripts against the publisher-supported
Transformers BF16 path on the Tara evaluation sets and the private SpeakKeys corpus.

## Run locally for protocol development

This mode still accepts audio but skips authentication, so it is intentionally forbidden
when `TARA_ENVIRONMENT=production`.

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -e '.[test]'

TARA_ENVIRONMENT=development \
TARA_AUTH_MODE=disabled \
TARA_MODEL_REVISION=<immutable-commit-sha> \
TARA_MODEL_DIR=/models/tara-ct2 \
TARA_PROCESSOR_DIR=/models/tara-hf \
python -m speakkeys_tara
```

Configure a development APK with the complete WebSocket URL:

```bash
./gradlew assembleDebug \
  -Pspeakkeys.taraRealtimeEndpoint=wss://voice.example.com/v1/realtime
```

If no endpoint is supplied, the Android catalog hides Natural Hinglish; an undeployed
backend can never become a dead user choice.

## Production deployment

Start with one model process per GPU, one active decode per process, no intentional
batch delay, and one queued decode at most. Admission happens synchronously at commit,
and queued work expires at the same seven-second deadline observed by the phone. Keep at
least one replica warm. Readiness becomes true only after manifest verification, model
load, a warm-up inference, and inference-worker startup.

For an India-first deployment, use an NVIDIA GPU in Mumbai and terminate TLS at a regional
load balancer. A GCP `g2-standard-4` supplies one 24 GB L4 and is the sensible launch
shape; it is available across all three Mumbai zones. Google currently lists the faster
G4 RTX PRO 6000 Blackwell in one Mumbai zone, but that path needs a separately validated
CUDA/CTranslate2 image. Absolute minimum GPU latency is available through A3 Edge H100 in
Mumbai, but its fixed eight-GPU shape is a major capacity and cost decision. Benchmark the
identical model and request path before choosing either upgrade. Use two zonal replicas
before calling the L4 service highly available.

Required production environment:

```text
TARA_ENVIRONMENT=production
TARA_AUTH_MODE=firebase
TARA_MODEL_REVISION=<immutable-commit-sha>
TARA_MODEL_DIR=/models/tara-ct2
TARA_PROCESSOR_DIR=/models/tara-hf
TARA_ARTIFACT_MANIFEST=/models/tara-manifest.json
GOOGLE_CLOUD_PROJECT=<firebase-project-id>
```

Important capacity controls default to `TARA_MAX_QUEUE_DEPTH=1`,
`TARA_MAX_CONNECTIONS=64`, and `TARA_MAX_CONNECTIONS_PER_USER=2`. The process applies
the per-user cap to the verified Firebase UID. It also permits at most 30 committed
utterances per verified UID in a rolling 60-second window; tune this with
`TARA_MAX_UTTERANCES_PER_MINUTE` and `TARA_UTTERANCE_RATE_WINDOW_SECONDS`. Configure
equivalent regional limits at the load balancer because each replica's counters are
intentionally local. The server also enforces 30 seconds of PCM, a 32-second recording
wall clock, and a 75-second absolute connection lifetime, so trickled frames cannot hold
a worker forever.

Production Firebase mode requires an explicit `GOOGLE_CLOUD_PROJECT` or
`FIREBASE_PROJECT_ID`; startup fails instead of allowing the Admin SDK to infer a wrong
audience. Readiness and liveness also fail when an active CTranslate2 call outlives its
job deadline, causing the container supervisor to replace a wedged GPU worker.

The service exposes:

- `GET /health/live`
- `GET /health/ready`
- `GET /health/worker`
- `GET /metrics` (protect this at the load balancer)
- `WSS /v1/realtime`

The service never logs raw audio, transcripts, Firebase tokens, or user identifiers.
Enforce per-user rate and spending limits in the session broker or gateway, and enforce
connection/request limits at the load balancer.

## Wire protocol v1

Client headers:

```text
Authorization: Bearer <Firebase-ID-token-or-future-session-token>
X-SpeakKeys-Protocol: 1
```

Server readiness:

```json
{"type":"session.ready","protocol_version":1,"sample_rate":16000,"max_audio_seconds":30}
```

Client start, followed by raw little-endian PCM16 binary frames:

```json
{"type":"session.start","protocol_version":1,"encoding":"pcm_s16le","sample_rate":16000,"language":"hi","mode":"mixed"}
```

Client release:

```json
{"type":"input.commit"}
```

Server result:

```json
{"type":"transcript.final","text":"आज meeting अच्छी थी","model_revision":"<commit>"}
```

Partials are reserved by the protocol but not claimed by this first implementation.

## Release gates

- Exact prompt-token and model-revision verification.
- Transformers BF16 versus CTranslate2 FP16 WER and mixed-script parity.
- p50/p95/p99 socket setup, queue, inference, and release-to-final latency.
- Cancellation, reconnect, expired authentication, slow client, overload, and worker death.
- Private conversational Hinglish accuracy, names, numbers, noise, and accents.
- Log inspection proving no audio, transcript, token, or user identity is retained.
