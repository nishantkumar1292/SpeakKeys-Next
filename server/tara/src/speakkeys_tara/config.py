from __future__ import annotations

from dataclasses import dataclass
import os
import re


def _positive_int(name: str, default: int) -> int:
    value = int(os.environ.get(name, str(default)))
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


def _positive_float(name: str, default: float) -> float:
    value = float(os.environ.get(name, str(default)))
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


_COMMIT_SHA = re.compile(r"[0-9a-f]{40}")


@dataclass(frozen=True)
class Settings:
    environment: str = "production"
    auth_mode: str = "firebase"
    firebase_project_id: str | None = None
    model_dir: str = "/models/tara-ct2"
    processor_dir: str = "/models/tara-hf"
    artifact_manifest_path: str = "/models/tara-manifest.json"
    model_revision: str = "unconfigured"
    device: str = "cuda"
    compute_type: str = "float16"
    max_audio_seconds: int = 30
    max_queue_depth: int = 1
    inference_deadline_seconds: float = 7.0
    session_idle_seconds: int = 45
    max_utterance_seconds: float = 32.0
    max_session_seconds: float = 75.0
    max_connections: int = 64
    max_connections_per_user: int = 2
    max_utterances_per_minute: int = 30
    utterance_rate_window_seconds: float = 60.0
    protocol_version: int = 1

    @classmethod
    def from_environment(cls) -> "Settings":
        settings = cls(
            environment=os.environ.get("TARA_ENVIRONMENT", "production").strip().lower(),
            auth_mode=os.environ.get("TARA_AUTH_MODE", "firebase").strip().lower(),
            firebase_project_id=(
                (
                    os.environ.get("GOOGLE_CLOUD_PROJECT")
                    or os.environ.get("FIREBASE_PROJECT_ID")
                    or ""
                ).strip()
                or None
            ),
            model_dir=os.environ.get("TARA_MODEL_DIR", "/models/tara-ct2"),
            processor_dir=os.environ.get("TARA_PROCESSOR_DIR", "/models/tara-hf"),
            artifact_manifest_path=os.environ.get(
                "TARA_ARTIFACT_MANIFEST",
                "/models/tara-manifest.json",
            ),
            model_revision=os.environ.get("TARA_MODEL_REVISION", "unconfigured").strip(),
            device=os.environ.get("TARA_DEVICE", "cuda"),
            compute_type=os.environ.get("TARA_COMPUTE_TYPE", "float16"),
            max_audio_seconds=_positive_int("TARA_MAX_AUDIO_SECONDS", 30),
            max_queue_depth=_positive_int("TARA_MAX_QUEUE_DEPTH", 1),
            inference_deadline_seconds=_positive_float(
                "TARA_INFERENCE_DEADLINE_SECONDS",
                7.0,
            ),
            session_idle_seconds=_positive_int("TARA_SESSION_IDLE_SECONDS", 45),
            max_utterance_seconds=_positive_float("TARA_MAX_UTTERANCE_SECONDS", 32.0),
            max_session_seconds=_positive_float("TARA_MAX_SESSION_SECONDS", 75.0),
            max_connections=_positive_int("TARA_MAX_CONNECTIONS", 64),
            max_connections_per_user=_positive_int("TARA_MAX_CONNECTIONS_PER_USER", 2),
            max_utterances_per_minute=_positive_int(
                "TARA_MAX_UTTERANCES_PER_MINUTE",
                30,
            ),
            utterance_rate_window_seconds=_positive_float(
                "TARA_UTTERANCE_RATE_WINDOW_SECONDS",
                60.0,
            ),
        )
        settings.validate()
        return settings

    def validate(self) -> None:
        if self.auth_mode not in {"firebase", "disabled"}:
            raise ValueError("TARA_AUTH_MODE must be firebase or disabled")
        if self.auth_mode == "disabled" and self.environment not in {"development", "test"}:
            raise ValueError("Authentication can only be disabled in development or test")
        if (
            self.environment == "production"
            and self.auth_mode == "firebase"
            and not (self.firebase_project_id or "").strip()
        ):
            raise ValueError("GOOGLE_CLOUD_PROJECT or FIREBASE_PROJECT_ID is required")
        if self.protocol_version != 1:
            raise ValueError("Only protocol version 1 is supported")
        if not _COMMIT_SHA.fullmatch(self.model_revision):
            raise ValueError("TARA_MODEL_REVISION must be a lowercase 40-hex commit SHA")
        if not self.artifact_manifest_path.strip():
            raise ValueError("TARA_ARTIFACT_MANIFEST cannot be empty")
        if self.max_connections_per_user > self.max_connections:
            raise ValueError("Per-user connection limit cannot exceed the global limit")
        if self.max_utterances_per_minute <= 0 or self.utterance_rate_window_seconds <= 0:
            raise ValueError("Utterance rate limits must be positive")
