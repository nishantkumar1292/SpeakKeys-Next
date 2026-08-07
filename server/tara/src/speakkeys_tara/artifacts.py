from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any


MANIFEST_SCHEMA_VERSION = 1
MODEL_REPOSITORY = "Trelis/tara"


class ArtifactVerificationError(RuntimeError):
    pass


def create_artifact_manifest(
    revision: str,
    processor_dir: Path,
    model_dir: Path,
) -> dict[str, Any]:
    return {
        "schema_version": MANIFEST_SCHEMA_VERSION,
        "repository": MODEL_REPOSITORY,
        "revision": revision,
        "artifacts": {
            "processor": _describe_tree(processor_dir),
            "model": _describe_tree(model_dir),
        },
    }


def verify_artifact_manifest(
    manifest_path: Path,
    revision: str,
    processor_dir: Path,
    model_dir: Path,
) -> None:
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ArtifactVerificationError(f"Cannot read artifact manifest: {manifest_path}") from error

    if not isinstance(manifest, dict):
        raise ArtifactVerificationError("Artifact manifest must be a JSON object")
    if manifest.get("schema_version") != MANIFEST_SCHEMA_VERSION:
        raise ArtifactVerificationError("Unsupported artifact manifest schema")
    if manifest.get("repository") != MODEL_REPOSITORY:
        raise ArtifactVerificationError("Artifact manifest repository does not match Tara")
    if manifest.get("revision") != revision:
        raise ArtifactVerificationError("Artifact manifest revision does not match configuration")

    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, dict):
        raise ArtifactVerificationError("Artifact manifest has no artifacts section")
    _verify_tree("processor", processor_dir, artifacts.get("processor"))
    _verify_tree("model", model_dir, artifacts.get("model"))


def _describe_tree(root: Path) -> dict[str, dict[str, int | str]]:
    if not root.is_dir():
        raise ArtifactVerificationError(f"Artifact directory does not exist: {root}")
    files: dict[str, dict[str, int | str]] = {}
    for path in _artifact_files(root):
        relative = path.relative_to(root).as_posix()
        files[relative] = {
            "size": path.stat().st_size,
            "sha256": _sha256(path),
        }
    if not files:
        raise ArtifactVerificationError(f"Artifact directory is empty: {root}")
    return files


def _verify_tree(label: str, root: Path, expected: object) -> None:
    if not isinstance(expected, dict) or not expected:
        raise ArtifactVerificationError(f"Artifact manifest has no {label} files")
    actual_paths = {path.relative_to(root).as_posix(): path for path in _artifact_files(root)}
    if set(actual_paths) != set(expected):
        raise ArtifactVerificationError(f"{label.capitalize()} artifact file set does not match manifest")

    for relative, metadata in expected.items():
        if not isinstance(relative, str) or not isinstance(metadata, dict):
            raise ArtifactVerificationError(f"Invalid {label} artifact manifest entry")
        path = actual_paths[relative]
        expected_size = metadata.get("size")
        expected_sha = metadata.get("sha256")
        if not isinstance(expected_size, int) or path.stat().st_size != expected_size:
            raise ArtifactVerificationError(f"{label.capitalize()} artifact size mismatch: {relative}")
        if not isinstance(expected_sha, str) or _sha256(path) != expected_sha:
            raise ArtifactVerificationError(f"{label.capitalize()} artifact checksum mismatch: {relative}")


def _artifact_files(root: Path) -> list[Path]:
    if not root.is_dir():
        raise ArtifactVerificationError(f"Artifact directory does not exist: {root}")
    files: list[Path] = []
    for path in root.rglob("*"):
        relative = path.relative_to(root)
        if ".cache" in relative.parts:
            continue
        if path.is_symlink():
            raise ArtifactVerificationError(f"Artifact symlinks are not allowed: {relative}")
        if path.is_file():
            files.append(path)
    return sorted(files)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
