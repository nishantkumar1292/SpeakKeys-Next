from __future__ import annotations

import asyncio
import json
import time
from typing import Any
from uuid import uuid4

from fastapi import WebSocket, WebSocketDisconnect
from prometheus_client import Counter, Gauge, Histogram

from .admission import (
    ConnectionAdmission,
    ConnectionLimitExceeded,
    UtteranceRateLimitExceeded,
    UtteranceRateLimiter,
)
from .auth import AuthenticationError, TokenVerifier, bearer_token
from .config import Settings
from .engine import InferenceDeadlineExceeded, InferenceQueueFull, InferenceScheduler


ACTIVE_SESSIONS = Gauge("tara_active_sessions", "Open Tara WebSocket sessions")
SESSION_COUNT = Counter(
    "tara_sessions_total",
    "Tara WebSocket sessions by terminal outcome",
    ("outcome",),
)
AUDIO_SECONDS = Histogram(
    "tara_audio_seconds",
    "Submitted utterance duration",
    buckets=(0.25, 0.5, 1, 2, 4, 8, 15, 30),
)
QUEUE_SECONDS = Histogram(
    "tara_queue_seconds",
    "Time waiting for the GPU worker",
    buckets=(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 5),
)
INFERENCE_SECONDS = Histogram(
    "tara_inference_seconds",
    "CTranslate2 inference time",
    buckets=(0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 4, 8),
)
RELEASE_TO_FINAL_SECONDS = Histogram(
    "tara_release_to_final_seconds",
    "Server time from commit event to final transcript",
    buckets=(0.05, 0.1, 0.25, 0.5, 1, 2, 4, 8),
)

# The Android recorder stops at exactly 30 seconds. Permit two 40 ms transport frames to
# arrive across that boundary, but never pass more than the advertised 30 seconds to Tara.
INGRESS_GRACE_BYTES = int(0.080 * 16_000 * 2)


class ProtocolFailure(Exception):
    def __init__(self, code: str, message: str, close_code: int = 4400) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.close_code = close_code


async def serve_websocket(
    websocket: WebSocket,
    scheduler: InferenceScheduler,
    verifier: TokenVerifier,
    admission: ConnectionAdmission,
    rate_limiter: UtteranceRateLimiter,
    settings: Settings,
) -> None:
    outcome = "server_error"
    accepted = False
    closed = False
    peer_disconnected = False
    admitted_user: str | None = None
    session_id = uuid4().hex
    try:
        protocol_header = websocket.headers.get("x-speakkeys-protocol")
        if protocol_header != str(settings.protocol_version):
            raise ProtocolFailure(
                "unsupported_protocol",
                "This SpeakKeys version is not supported",
                close_code=4400,
            )
        token = bearer_token(websocket.headers.get("authorization"))
        user_id = await verifier.verify(token)
        if not isinstance(user_id, str) or not user_id:
            raise AuthenticationError("Session has no user identity")
        try:
            admission.acquire(user_id)
        except ConnectionLimitExceeded as error:
            raise ProtocolFailure("connection_limit", str(error), 4429) from error
        admitted_user = user_id

        await websocket.accept()
        accepted = True
        ACTIVE_SESSIONS.inc()
        await websocket.send_json(
            {
                "type": "session.ready",
                "protocol_version": settings.protocol_version,
                "session_id": session_id,
                "sample_rate": 16_000,
                "max_audio_seconds": settings.max_audio_seconds,
                "final_deadline_seconds": settings.inference_deadline_seconds,
            },
        )

        pcm = bytearray()
        started = False
        accepted_at = time.monotonic()
        session_deadline = accepted_at + settings.max_session_seconds
        idle_deadline = accepted_at + settings.session_idle_seconds
        utterance_deadline: float | None = None
        max_audio_bytes = settings.max_audio_seconds * 16_000 * 2
        max_ingress_bytes = max_audio_bytes + INGRESS_GRACE_BYTES

        while True:
            message = await _receive_before_deadline(
                websocket,
                idle_deadline=idle_deadline,
                session_deadline=session_deadline,
                utterance_deadline=utterance_deadline,
            )
            idle_deadline = time.monotonic() + settings.session_idle_seconds

            message_type = message.get("type")
            if message_type == "websocket.disconnect":
                outcome = "cancelled"
                peer_disconnected = True
                return

            frame = message.get("bytes")
            if frame is not None:
                if not started:
                    raise ProtocolFailure("start_required", "Session must start before audio")
                if len(frame) == 0:
                    raise ProtocolFailure("invalid_audio", "PCM16 audio frame cannot be empty")
                if len(frame) % 2:
                    raise ProtocolFailure("invalid_audio", "PCM16 audio frame has an invalid length")
                if len(pcm) + len(frame) > max_ingress_bytes:
                    raise ProtocolFailure(
                        "audio_too_long",
                        f"Audio cannot exceed {settings.max_audio_seconds} seconds",
                        close_code=4409,
                    )
                pcm.extend(frame)
                continue

            text = message.get("text")
            if text is None:
                continue
            event = _json_object(text)
            event_type = event.get("type")

            if event_type == "session.start":
                if started:
                    raise ProtocolFailure("already_started", "Session was already started")
                _validate_start(event, settings.protocol_version)
                started = True
                utterance_deadline = time.monotonic() + settings.max_utterance_seconds
                continue
            if event_type == "input.cancel":
                outcome = "cancelled"
                return
            if event_type != "input.commit":
                raise ProtocolFailure("unknown_event", "Unknown voice session event")
            if not started:
                raise ProtocolFailure("start_required", "Session must start before commit")

            committed_at = time.monotonic()
            inference_pcm = bytes(pcm[:max_audio_bytes])
            AUDIO_SECONDS.observe(len(inference_pcm) / (16_000 * 2))
            try:
                rate_reservation = rate_limiter.reserve(user_id)
            except UtteranceRateLimitExceeded as error:
                raise ProtocolFailure("rate_limited", str(error), 4429) from error
            if not pcm:
                rate_reservation.consume()
                await websocket.send_json(
                    {
                        "type": "transcript.final",
                        "text": "",
                        "session_id": session_id,
                        "model_revision": settings.model_revision,
                    },
                )
                outcome = "success"
                return

            try:
                final_deadline = min(
                    committed_at + settings.inference_deadline_seconds,
                    session_deadline,
                )
                inference = scheduler.submit(inference_pcm, deadline_at=final_deadline)
            except InferenceQueueFull as error:
                rate_reservation.cancel()
                raise ProtocolFailure("busy", "Natural Hinglish is busy; try again", 4429) from error
            except InferenceDeadlineExceeded as error:
                rate_reservation.cancel()
                raise ProtocolFailure("final_timeout", "Voice transcription timed out", 4408) from error
            except Exception:
                rate_reservation.cancel()
                raise
            else:
                rate_reservation.consume()

            disconnect = asyncio.create_task(websocket.receive())
            deadline = asyncio.create_task(
                asyncio.sleep(max(0.0, final_deadline - time.monotonic())),
            )
            try:
                done, _ = await asyncio.wait(
                    {inference, disconnect, deadline},
                    return_when=asyncio.FIRST_COMPLETED,
                )
                if inference in done:
                    try:
                        result = inference.result()
                    except InferenceDeadlineExceeded as error:
                        raise ProtocolFailure(
                            "final_timeout",
                            "Voice transcription timed out",
                            4408,
                        ) from error
                elif disconnect in done:
                    disconnect_message = disconnect.result()
                    if disconnect_message.get("type") == "websocket.disconnect" or _is_cancel_event(
                        disconnect_message,
                    ):
                        inference.cancel()
                        outcome = "cancelled"
                        peer_disconnected = disconnect_message.get("type") == "websocket.disconnect"
                        return
                    inference.cancel()
                    raise ProtocolFailure(
                        "event_after_commit",
                        "No messages are accepted after input.commit",
                    )
                else:
                    inference.cancel()
                    raise ProtocolFailure("final_timeout", "Voice transcription timed out", 4408)
            finally:
                disconnect.cancel()
                deadline.cancel()
                await asyncio.gather(disconnect, deadline, return_exceptions=True)

            QUEUE_SECONDS.observe(result.queue_seconds)
            INFERENCE_SECONDS.observe(result.inference_seconds)
            RELEASE_TO_FINAL_SECONDS.observe(time.monotonic() - committed_at)
            await websocket.send_json(
                {
                    "type": "transcript.final",
                    "text": result.text,
                    "session_id": session_id,
                    "model_revision": settings.model_revision,
                },
            )
            outcome = "success"
            return
    except AuthenticationError as error:
        outcome = "authentication_error"
        closed = await _fail(websocket, accepted, "authentication_failed", str(error), 4401)
    except ProtocolFailure as error:
        outcome = error.code
        closed = await _fail(websocket, accepted, error.code, error.message, error.close_code)
    except WebSocketDisconnect:
        outcome = "cancelled"
        peer_disconnected = True
    except Exception:
        # Do not echo internal exception text: it can expose configuration or infrastructure.
        outcome = "server_error"
        closed = await _fail(
            websocket,
            accepted,
            "server_error",
            "Voice transcription failed",
            4500,
        )
    finally:
        if accepted:
            ACTIVE_SESSIONS.dec()
        if admitted_user is not None:
            admission.release(admitted_user)
        SESSION_COUNT.labels(outcome=outcome).inc()
        if accepted and not closed and not peer_disconnected:
            try:
                await websocket.close(code=1000)
            except Exception:
                pass


async def _receive_before_deadline(
    websocket: WebSocket,
    idle_deadline: float,
    session_deadline: float,
    utterance_deadline: float | None,
) -> dict[str, Any]:
    deadlines = [(idle_deadline, "session_timeout", "Voice session timed out")]
    deadlines.append((session_deadline, "session_too_long", "Voice session duration was exceeded"))
    if utterance_deadline is not None:
        deadlines.append(
            (utterance_deadline, "utterance_too_long", "Voice recording duration was exceeded"),
        )
    deadline_at, code, message = min(deadlines, key=lambda item: item[0])
    remaining = deadline_at - time.monotonic()
    if remaining <= 0:
        raise ProtocolFailure(code, message, 4408)
    try:
        received = await asyncio.wait_for(websocket.receive(), timeout=remaining)
    except asyncio.TimeoutError as error:
        raise ProtocolFailure(code, message, 4408) from error
    if time.monotonic() > deadline_at:
        raise ProtocolFailure(code, message, 4408)
    return received


def _validate_start(event: dict[str, Any], protocol_version: int) -> None:
    expected = {
        "protocol_version": protocol_version,
        "encoding": "pcm_s16le",
        "sample_rate": 16_000,
        "language": "hi",
        "mode": "mixed",
    }
    for key, value in expected.items():
        if event.get(key) != value:
            raise ProtocolFailure("invalid_start", f"Unsupported {key}")


def _json_object(value: str) -> dict[str, Any]:
    try:
        parsed = json.loads(value)
    except (TypeError, json.JSONDecodeError) as error:
        raise ProtocolFailure("invalid_json", "Voice session message is not valid JSON") from error
    if not isinstance(parsed, dict):
        raise ProtocolFailure("invalid_json", "Voice session message must be an object")
    return parsed


def _is_cancel_event(message: dict[str, Any]) -> bool:
    text = message.get("text")
    if not isinstance(text, str):
        return False
    try:
        return _json_object(text).get("type") == "input.cancel"
    except ProtocolFailure:
        return False


async def _fail(
    websocket: WebSocket,
    accepted: bool,
    code: str,
    message: str,
    close_code: int,
) -> bool:
    if accepted:
        try:
            await websocket.send_json({"type": "error", "code": code, "message": message})
        except Exception:
            pass
    try:
        await websocket.close(code=close_code, reason=message[:100])
    except Exception:
        pass
    # A terminal close was attempted. Never overwrite its application close code in finally.
    return True
