#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess

from huggingface_hub import HfApi, snapshot_download
from transformers import WhisperProcessor

from speakkeys_tara.artifacts import create_artifact_manifest


REQUIRED_TOKENS = (
    "<|startoftranscript|>",
    "<|hi|>",
    "<|mixedcode|>",
    "<|transcribe|>",
    "<|notimestamps|>",
)
COMMIT_SHA = re.compile(r"[0-9a-f]{40}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision", required=True, help="Immutable Trelis/tara commit SHA")
    parser.add_argument("--output-root", default="/models")
    args = parser.parse_args()

    if not COMMIT_SHA.fullmatch(args.revision):
        raise SystemExit("--revision must be a lowercase 40-hex commit SHA")

    resolved_revision = HfApi().model_info("Trelis/tara", revision=args.revision).sha
    if resolved_revision != args.revision:
        raise SystemExit("Hugging Face did not resolve the requested Tara commit exactly")

    root = Path(args.output_root)
    hf_dir = root / "tara-hf"
    ct2_dir = root / "tara-ct2"
    root.mkdir(parents=True, exist_ok=True)
    for output in (hf_dir, ct2_dir):
        if output.exists() and (not output.is_dir() or any(output.iterdir())):
            raise SystemExit(f"Refusing to mix artifacts in non-empty directory: {output}")

    snapshot_download(
        repo_id="Trelis/tara",
        revision=args.revision,
        local_dir=hf_dir,
    )
    subprocess.run(
        [
            "ct2-transformers-converter",
            "--model",
            str(hf_dir),
            "--output_dir",
            str(ct2_dir),
            "--quantization",
            "float16",
            "--force",
        ],
        check=True,
    )

    processor = WhisperProcessor.from_pretrained(str(hf_dir), local_files_only=True)
    token_ids = processor.tokenizer.convert_tokens_to_ids(list(REQUIRED_TOKENS))
    if any(
        token_id is None or token_id == processor.tokenizer.unk_token_id
        for token_id in token_ids
    ):
        raise SystemExit("Tara tokenizer verification failed: required mixed-code token is missing")

    manifest = create_artifact_manifest(args.revision, hf_dir, ct2_dir)
    manifest_path = root / "tara-manifest.json"
    temporary_manifest = root / "tara-manifest.json.tmp"
    temporary_manifest.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary_manifest.replace(manifest_path)
    (root / "tara-revision.txt").write_text(args.revision + "\n", encoding="utf-8")
    print(f"Converted and verified Trelis/tara revision {args.revision} into {ct2_dir}")


if __name__ == "__main__":
    main()
