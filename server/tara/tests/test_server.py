from __future__ import annotations

import asyncio
import json
from pathlib import Path
import struct
import threading
import time

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from speakkeys_tara.app import create_app
from speakkeys_tara.admission import UtteranceRateLimitExceeded, UtteranceRateLimiter
from speakkeys_tara.artifacts import (
    ArtifactVerificationError,
    create_artifact_manifest,
    verify_artifact_manifest,
)
from speakkeys_tara.auth import DevelopmentTokenVerifier
from speakkeys_tara.config import Settings
from speakkeys_tara.engine import (
    InferenceDeadlineExceeded,
    InferenceQueueFull,
    InferenceScheduler,
    TaraEngine,
)
from speakkeys_tara.protocol import INGRESS_GRACE_BYTES


TEST_REVISION = "a" * 40


class FakeEngine:
    def __init__(self, text: str = "आज meeting अच्छी थी") -> None:
        self.text = text
        self.loaded = False
        self.received: list[bytes] = []

    def load(self) -> None:
        self.loaded = True

    def transcribe_pcm16(self, pcm: bytes, sample_rate: int = 16_000) -> str:
        assert self.loaded
        assert sample_rate == 16_000
        self.received.append(pcm)
        return self.text


def test_production_cannot_disable_authentication() -> None:
    with pytest.raises(ValueError, match="Authentication can only be disabled"):
        Settings(
            environment="production",
            auth_mode="disabled",
            model_revision=TEST_REVISION,
        ).validate()


def test_production_requires_a_pinned_model_revision() -> None:
    with pytest.raises(ValueError, match="lowercase 40-hex"):
        Settings(
            environment="production",
            auth_mode="firebase",
            firebase_project_id="speakkeys-production",
        ).validate()


def test_production_firebase_requires_explicit_project_id() -> None:
    with pytest.raises(ValueError, match="GOOGLE_CLOUD_PROJECT"):
        Settings(
            environment="production",
            auth_mode="firebase",
            model_revision=TEST_REVISION,
        ).validate()


@pytest.mark.parametrize("revision", ["main", "a" * 39, "A" * 40, "g" * 40])
def test_model_revision_must_be_an_exact_lowercase_commit(revision: str) -> None:
    with pytest.raises(ValueError, match="lowercase 40-hex"):
        Settings(environment="test", auth_mode="disabled", model_revision=revision).validate()


def test_tara_prompt_keeps_mixedcode_immediately_after_hindi() -> None:
    assert TaraEngine.PROMPT_TOKENS == (
        "<|startoftranscript|>",
        "<|hi|>",
        "<|mixedcode|>",
        "<|transcribe|>",
        "<|notimestamps|>",
    )


def test_authenticated_binary_pcm_session_returns_one_final() -> None:
    engine = FakeEngine()
    app = create_app(make_test_settings(), engine, DevelopmentTokenVerifier("user-1"))

    with TestClient(app) as client:
        with client.websocket_connect(
            "/v1/realtime",
            headers={
                "Authorization": "Bearer firebase-token",
                "X-SpeakKeys-Protocol": "1",
            },
        ) as socket:
            ready = socket.receive_json()
            assert ready["type"] == "session.ready"
            assert ready["max_audio_seconds"] == 30

            socket.send_json(start_event())
            pcm = struct.pack("<hhhh", 1, -2, 3, -4)
            socket.send_bytes(pcm)
            socket.send_json({"type": "input.commit"})

            final = socket.receive_json()
            assert final["type"] == "transcript.final"
            assert final["text"] == "आज meeting अच्छी थी"
            assert final["model_revision"] == TEST_REVISION

    assert engine.received == [pcm]


def test_missing_bearer_token_is_rejected_before_ready() -> None:
    app = create_app(make_test_settings(), FakeEngine(), DevelopmentTokenVerifier())
    with TestClient(app) as client:
        with pytest.raises(WebSocketDisconnect) as failure:
            with client.websocket_connect(
                "/v1/realtime",
                headers={"X-SpeakKeys-Protocol": "1"},
            ) as socket:
                socket.receive_json()
    assert failure.value.code == 4401


def test_invalid_audio_contract_is_rejected_without_inference() -> None:
    engine = FakeEngine()
    app = create_app(make_test_settings(), engine, DevelopmentTokenVerifier())
    with TestClient(app) as client:
        with client.websocket_connect(
            "/v1/realtime",
            headers=auth_headers(),
        ) as socket:
            assert socket.receive_json()["type"] == "session.ready"
            invalid = start_event()
            invalid["sample_rate"] = 48_000
            socket.send_json(invalid)
            error = socket.receive_json()
            assert error == {
                "type": "error",
                "code": "invalid_start",
                "message": "Unsupported sample_rate",
            }
            with pytest.raises(WebSocketDisconnect) as closed:
                socket.receive_json()
            assert closed.value.code == 4400
    assert engine.received == []


def test_zero_byte_audio_frame_is_rejected() -> None:
    engine = FakeEngine()
    app = create_app(make_test_settings(), engine, DevelopmentTokenVerifier())
    with TestClient(app) as client:
        with client.websocket_connect("/v1/realtime", headers=auth_headers()) as socket:
            assert socket.receive_json()["type"] == "session.ready"
            socket.send_json(start_event())
            socket.send_bytes(b"")
            error = socket.receive_json()
            assert error["code"] == "invalid_audio"
    assert engine.received == []


def test_boundary_ingress_grace_is_accepted_but_inference_is_truncated_to_30_seconds() -> None:
    engine = FakeEngine()
    app = create_app(make_test_settings(), engine, DevelopmentTokenVerifier())
    exact_limit = b"\x01\x00" * (30 * 16_000)
    grace = b"\x02\x00" * (INGRESS_GRACE_BYTES // 2)

    with TestClient(app) as client:
        with client.websocket_connect("/v1/realtime", headers=auth_headers()) as socket:
            socket.receive_json()
            socket.send_json(start_event())
            socket.send_bytes(exact_limit)
            socket.send_bytes(grace)
            socket.send_json({"type": "input.commit"})
            assert socket.receive_json()["type"] == "transcript.final"

    assert engine.received == [exact_limit]


def test_audio_beyond_boundary_grace_is_rejected() -> None:
    engine = FakeEngine()
    app = create_app(make_test_settings(), engine, DevelopmentTokenVerifier())
    too_much = bytes((30 * 16_000 * 2) + INGRESS_GRACE_BYTES + 2)
    with TestClient(app) as client:
        with client.websocket_connect("/v1/realtime", headers=auth_headers()) as socket:
            socket.receive_json()
            socket.send_json(start_event())
            socket.send_bytes(too_much)
            assert socket.receive_json()["code"] == "audio_too_long"
    assert engine.received == []


def test_absolute_utterance_deadline_cannot_be_extended_by_frames() -> None:
    settings = make_test_settings(max_utterance_seconds=0.04)
    app = create_app(settings, FakeEngine(), DevelopmentTokenVerifier())
    with TestClient(app) as client:
        with client.websocket_connect("/v1/realtime", headers=auth_headers()) as socket:
            socket.receive_json()
            socket.send_json(start_event())
            time.sleep(0.06)
            assert socket.receive_json()["code"] == "utterance_too_long"


def test_absolute_session_deadline_applies_to_preopened_connection() -> None:
    settings = make_test_settings(max_session_seconds=0.04)
    app = create_app(settings, FakeEngine(), DevelopmentTokenVerifier())
    with TestClient(app) as client:
        with client.websocket_connect("/v1/realtime", headers=auth_headers()) as socket:
            socket.receive_json()
            time.sleep(0.06)
            assert socket.receive_json()["code"] == "session_too_long"


def test_per_user_connection_limit_uses_verified_identity() -> None:
    settings = make_test_settings(max_connections_per_user=1)
    app = create_app(settings, FakeEngine(), DevelopmentTokenVerifier("same-user"))
    with TestClient(app) as client:
        with client.websocket_connect("/v1/realtime", headers=auth_headers()) as first:
            first.receive_json()
            with pytest.raises(WebSocketDisconnect) as rejected:
                with client.websocket_connect("/v1/realtime", headers=auth_headers()) as second:
                    second.receive_json()
            assert rejected.value.code == 4429
        # Closing a session must release its per-UID connection admission.
        with client.websocket_connect("/v1/realtime", headers=auth_headers()) as replacement:
            assert replacement.receive_json()["type"] == "session.ready"


def test_rate_limit_is_applied_at_commit_for_verified_uid() -> None:
    settings = make_test_settings(max_utterances_per_minute=1)
    app = create_app(settings, FakeEngine(), DevelopmentTokenVerifier("rate-limited-user"))
    with TestClient(app) as client:
        with client.websocket_connect("/v1/realtime", headers=auth_headers()) as first:
            first.receive_json()
            first.send_json(start_event())
            first.send_bytes(b"\x01\x00")
            first.send_json({"type": "input.commit"})
            assert first.receive_json()["type"] == "transcript.final"

        with client.websocket_connect("/v1/realtime", headers=auth_headers()) as second:
            second.receive_json()
            second.send_json(start_event())
            second.send_bytes(b"\x01\x00")
            second.send_json({"type": "input.commit"})
            error = second.receive_json()
            assert error["code"] == "rate_limited"


def test_rate_reservation_refund_and_window_expiry_release_user_state() -> None:
    now = [10.0]
    limiter = UtteranceRateLimiter(1, 60.0, clock=lambda: now[0])

    refunded = limiter.reserve("user-1")
    refunded.cancel()
    assert limiter.tracked_users == 0

    limiter.reserve("user-1").consume()
    with pytest.raises(UtteranceRateLimitExceeded):
        limiter.reserve("user-1")

    # The limiter is per UID and a second verified identity has an independent budget.
    limiter.reserve("user-2").consume()
    assert limiter.tracked_users == 2
    now[0] += 60.0
    assert limiter.tracked_users == 0


def test_health_probes_report_loaded_worker_readiness() -> None:
    app = create_app(make_test_settings(), FakeEngine(), DevelopmentTokenVerifier())
    with TestClient(app) as client:
        assert client.get("/health/live").json() == {"status": "live"}
        assert client.get("/health/ready").json() == {"status": "ready", "queue_depth": "0"}
        assert client.get("/health/worker").json() == {"status": "ready", "queue_depth": "0"}


def test_manifest_detects_artifact_mutation(tmp_path: Path) -> None:
    processor = tmp_path / "tara-hf"
    model = tmp_path / "tara-ct2"
    processor.mkdir()
    model.mkdir()
    (processor / "tokenizer.json").write_text("tokenizer", encoding="utf-8")
    (model / "model.bin").write_bytes(b"model")
    manifest_path = tmp_path / "tara-manifest.json"
    manifest_path.write_text(
        json.dumps(create_artifact_manifest(TEST_REVISION, processor, model)),
        encoding="utf-8",
    )

    verify_artifact_manifest(manifest_path, TEST_REVISION, processor, model)
    (model / "model.bin").write_bytes(b"modEl")
    with pytest.raises(ArtifactVerificationError, match="checksum mismatch"):
        verify_artifact_manifest(manifest_path, TEST_REVISION, processor, model)


def test_scheduler_rejects_work_beyond_its_bounded_queue() -> None:
    class BlockingEngine(FakeEngine):
        def __init__(self) -> None:
            super().__init__("done")
            self.entered = threading.Event()
            self.release = threading.Event()

        def transcribe_pcm16(self, pcm: bytes, sample_rate: int = 16_000) -> str:
            self.entered.set()
            assert self.release.wait(timeout=2)
            return super().transcribe_pcm16(pcm, sample_rate)

    async def scenario() -> None:
        engine = BlockingEngine()
        scheduler = InferenceScheduler(engine, max_queue_depth=1)
        await scheduler.start()
        first = asyncio.create_task(scheduler.transcribe(b"\x00\x00"))
        assert await asyncio.to_thread(engine.entered.wait, 1)
        second = asyncio.create_task(scheduler.transcribe(b"\x01\x00"))
        await asyncio.sleep(0)
        with pytest.raises(InferenceQueueFull):
            await scheduler.transcribe(b"\x02\x00")
        engine.release.set()
        assert (await first).text == "done"
        assert (await second).text == "done"
        await scheduler.close()

    asyncio.run(scenario())


def test_scheduler_expires_queued_work_at_the_client_deadline() -> None:
    class BlockingEngine(FakeEngine):
        def __init__(self) -> None:
            super().__init__("done")
            self.entered = threading.Event()
            self.release = threading.Event()

        def transcribe_pcm16(self, pcm: bytes, sample_rate: int = 16_000) -> str:
            self.entered.set()
            assert self.release.wait(timeout=2)
            return super().transcribe_pcm16(pcm, sample_rate)

    async def scenario() -> None:
        engine = BlockingEngine()
        scheduler = InferenceScheduler(engine, max_queue_depth=1)
        await scheduler.start()
        first = scheduler.submit(b"\x00\x00", deadline_at=time.monotonic() + 1)
        assert await asyncio.to_thread(engine.entered.wait, 1)
        expired = scheduler.submit(b"\x01\x00", deadline_at=time.monotonic() + 0.02)
        await asyncio.sleep(0.04)
        engine.release.set()
        assert (await first).text == "done"
        with pytest.raises(InferenceDeadlineExceeded):
            await expired
        await scheduler.close()

    asyncio.run(scenario())


def test_scheduler_health_fails_while_active_decode_exceeds_deadline() -> None:
    class HungEngine(FakeEngine):
        def __init__(self) -> None:
            super().__init__("late")
            self.entered = threading.Event()
            self.release = threading.Event()

        def transcribe_pcm16(self, pcm: bytes, sample_rate: int = 16_000) -> str:
            self.entered.set()
            assert self.release.wait(timeout=2)
            return super().transcribe_pcm16(pcm, sample_rate)

    async def scenario() -> None:
        engine = HungEngine()
        scheduler = InferenceScheduler(engine, max_queue_depth=1)
        await scheduler.start()
        result = scheduler.submit(b"\x00\x00", deadline_at=time.monotonic() + 0.03)
        assert await asyncio.to_thread(engine.entered.wait, 1)
        await asyncio.sleep(0.05)
        assert scheduler.active_deadline_exceeded
        assert not scheduler.ready
        assert not scheduler.healthy

        engine.release.set()
        with pytest.raises(InferenceDeadlineExceeded):
            await result
        assert scheduler.ready
        assert scheduler.healthy
        await scheduler.close()

    asyncio.run(scenario())


def make_test_settings(**overrides: object) -> Settings:
    values: dict[str, object] = {
        "environment": "test",
        "auth_mode": "disabled",
        "model_revision": TEST_REVISION,
        "max_audio_seconds": 30,
        "max_queue_depth": 2,
        "session_idle_seconds": 2,
    }
    values.update(overrides)
    return Settings(
        **values,
    )


def auth_headers() -> dict[str, str]:
    return {
        "Authorization": "Bearer test-token",
        "X-SpeakKeys-Protocol": "1",
    }


def start_event() -> dict[str, object]:
    return {
        "type": "session.start",
        "protocol_version": 1,
        "encoding": "pcm_s16le",
        "sample_rate": 16_000,
        "language": "hi",
        "mode": "mixed",
    }
