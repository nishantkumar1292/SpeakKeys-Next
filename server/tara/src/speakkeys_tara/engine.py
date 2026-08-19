from __future__ import annotations

import asyncio
from dataclasses import dataclass
from pathlib import Path
import time
from typing import Protocol

from .artifacts import verify_artifact_manifest


class TaraEngineProtocol(Protocol):
    def load(self) -> None: ...
    def transcribe_pcm16(self, pcm: bytes, sample_rate: int = 16_000) -> str: ...


class TaraEngine:
    """Direct CTranslate2 wrapper that preserves Tara's custom mixed-code prompt."""

    PROMPT_TOKENS = (
        "<|startoftranscript|>",
        "<|hi|>",
        "<|mixedcode|>",
        "<|transcribe|>",
        "<|notimestamps|>",
    )

    def __init__(
        self,
        model_dir: str,
        processor_dir: str,
        model_revision: str,
        artifact_manifest_path: str,
        device: str = "cuda",
        compute_type: str = "float16",
    ) -> None:
        self.model_dir = Path(model_dir)
        self.processor_dir = Path(processor_dir)
        self.model_revision = model_revision
        self.artifact_manifest_path = Path(artifact_manifest_path)
        self.device = device
        self.compute_type = compute_type
        self._model = None
        self._processor = None
        self._prompt: list[int] | None = None

    def load(self) -> None:
        if self._model is not None:
            return
        if not self.model_dir.is_dir():
            raise FileNotFoundError(f"Converted Tara model not found: {self.model_dir}")
        if not self.processor_dir.is_dir():
            raise FileNotFoundError(f"Tara processor files not found: {self.processor_dir}")

        verify_artifact_manifest(
            self.artifact_manifest_path,
            self.model_revision,
            self.processor_dir,
            self.model_dir,
        )

        import ctranslate2
        from transformers import WhisperProcessor

        processor = WhisperProcessor.from_pretrained(
            str(self.processor_dir),
            local_files_only=True,
        )
        prompt = processor.tokenizer.convert_tokens_to_ids(list(self.PROMPT_TOKENS))
        unknown_id = processor.tokenizer.unk_token_id
        if any(token_id is None or token_id == unknown_id for token_id in prompt):
            raise ValueError("Converted tokenizer is missing a required Tara mixed-code token")

        model = ctranslate2.models.Whisper(
            str(self.model_dir),
            device=self.device,
            compute_type=self.compute_type,
            inter_threads=1,
            max_queued_batches=1,
        )
        self._processor = processor
        self._prompt = [int(token_id) for token_id in prompt]
        self._model = model

        # Pay allocator/kernel initialization before the readiness probe succeeds.
        try:
            self.transcribe_pcm16(bytes(16_000 * 2), sample_rate=16_000)
        except Exception:
            self._model = None
            self._processor = None
            self._prompt = None
            raise

    def transcribe_pcm16(self, pcm: bytes, sample_rate: int = 16_000) -> str:
        if self._model is None or self._processor is None or self._prompt is None:
            raise RuntimeError("Tara engine is not loaded")
        if sample_rate != 16_000:
            raise ValueError("Tara accepts 16 kHz audio")
        if len(pcm) % 2:
            raise ValueError("PCM16 payload length must be even")

        import ctranslate2
        import numpy as np

        audio = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
        inputs = self._processor(
            audio,
            sampling_rate=sample_rate,
            return_tensors="np",
        )
        features = ctranslate2.StorageView.from_array(
            np.ascontiguousarray(inputs.input_features),
        )
        results = self._model.generate(
            features,
            [self._prompt],
            beam_size=1,
            max_length=448,
            sampling_topk=1,
        )
        return self._processor.tokenizer.decode(
            results[0].sequences_ids[0],
            skip_special_tokens=True,
        ).strip()


class InferenceQueueFull(Exception):
    pass


class InferenceDeadlineExceeded(Exception):
    pass


@dataclass
class _InferenceJob:
    pcm: bytes
    enqueued_at: float
    deadline_at: float
    future: asyncio.Future["InferenceResult"]


@dataclass(frozen=True)
class InferenceResult:
    text: str
    queue_seconds: float
    inference_seconds: float


class InferenceScheduler:
    """One bounded, latency-first inference lane for one GPU model process."""

    def __init__(self, engine: TaraEngineProtocol, max_queue_depth: int) -> None:
        self._engine = engine
        self._max_queue_depth = max_queue_depth
        self._queue: asyncio.Queue[_InferenceJob | None] | None = None
        self._worker: asyncio.Task[None] | None = None
        self._loaded = False
        self._closing = False
        self._active_deadline_at: float | None = None

    @property
    def queue_depth(self) -> int:
        return self._queue.qsize() if self._queue is not None else 0

    @property
    def ready(self) -> bool:
        return (
            self._loaded
            and not self._closing
            and self._worker is not None
            and not self._worker.done()
            and not self.active_deadline_exceeded
        )

    @property
    def healthy(self) -> bool:
        if not self._loaded:
            return True
        return (
            self._worker is not None
            and not self._worker.done()
            and not self.active_deadline_exceeded
        )

    @property
    def active_deadline_exceeded(self) -> bool:
        return (
            self._active_deadline_at is not None
            and time.monotonic() >= self._active_deadline_at
        )

    async def start(self) -> None:
        if self._worker is not None:
            return
        self._closing = False
        await asyncio.to_thread(self._engine.load)
        self._loaded = True
        # asyncio primitives bind to the running server loop. Creating the queue in __init__
        # makes ASGI lifespan/test portals attach it to the wrong loop on Python 3.9/3.10.
        self._queue = asyncio.Queue(self._max_queue_depth)
        self._worker = asyncio.create_task(self._run(), name="tara-inference-worker")

    async def close(self) -> None:
        if self._worker is None:
            return
        self._closing = True
        queue = self._require_queue()
        if not self._worker.done():
            await queue.put(None)
        await asyncio.gather(self._worker, return_exceptions=True)
        self._worker = None
        self._queue = None
        self._loaded = False
        self._closing = False
        self._active_deadline_at = None

    def submit(self, pcm: bytes, deadline_at: float) -> asyncio.Future[InferenceResult]:
        """Admit synchronously so overload is rejected before starting finalization."""

        if not self.ready:
            raise RuntimeError("Tara inference worker is not ready")
        if deadline_at <= time.monotonic():
            raise InferenceDeadlineExceeded("Tara inference deadline elapsed before admission")
        loop = asyncio.get_running_loop()
        future: asyncio.Future[InferenceResult] = loop.create_future()
        queue = self._require_queue()
        try:
            queue.put_nowait(
                _InferenceJob(
                    pcm=pcm,
                    enqueued_at=time.monotonic(),
                    deadline_at=deadline_at,
                    future=future,
                ),
            )
        except asyncio.QueueFull as error:
            raise InferenceQueueFull("Tara is busy") from error
        return future

    async def transcribe(self, pcm: bytes, deadline_seconds: float = 7.0) -> InferenceResult:
        future = self.submit(pcm, time.monotonic() + deadline_seconds)
        try:
            return await asyncio.wait_for(future, timeout=deadline_seconds)
        except asyncio.CancelledError:
            future.cancel()
            raise

    async def _run(self) -> None:
        queue = self._require_queue()
        while True:
            job = await queue.get()
            try:
                if job is None:
                    return
                if job.future.cancelled():
                    continue
                inference_started_at = time.monotonic()
                if inference_started_at >= job.deadline_at:
                    if not job.future.done():
                        job.future.set_exception(
                            InferenceDeadlineExceeded("Tara inference expired in the queue"),
                        )
                    continue
                try:
                    self._active_deadline_at = job.deadline_at
                    text = await asyncio.to_thread(self._engine.transcribe_pcm16, job.pcm)
                except Exception as error:
                    if not job.future.done():
                        job.future.set_exception(error)
                else:
                    if not job.future.done():
                        finished_at = time.monotonic()
                        if finished_at >= job.deadline_at:
                            job.future.set_exception(
                                InferenceDeadlineExceeded("Tara inference exceeded its deadline"),
                            )
                        else:
                            job.future.set_result(
                                InferenceResult(
                                    text=text,
                                    queue_seconds=inference_started_at - job.enqueued_at,
                                    inference_seconds=finished_at - inference_started_at,
                                ),
                            )
                finally:
                    self._active_deadline_at = None
            finally:
                queue.task_done()

    def _require_queue(self) -> asyncio.Queue[_InferenceJob | None]:
        if self._queue is None:
            raise RuntimeError("Tara inference scheduler is not running")
        return self._queue
