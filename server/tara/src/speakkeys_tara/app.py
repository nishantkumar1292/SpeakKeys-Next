from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI, Response, WebSocket
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from .admission import ConnectionAdmission, UtteranceRateLimiter
from .auth import DevelopmentTokenVerifier, FirebaseTokenVerifier, TokenVerifier
from .config import Settings
from .engine import InferenceScheduler, TaraEngine, TaraEngineProtocol
from .protocol import serve_websocket


def create_app(
    settings: Settings | None = None,
    engine: TaraEngineProtocol | None = None,
    verifier: TokenVerifier | None = None,
) -> FastAPI:
    configured = settings or Settings.from_environment()
    configured.validate()
    tara_engine = engine or TaraEngine(
        model_dir=configured.model_dir,
        processor_dir=configured.processor_dir,
        model_revision=configured.model_revision,
        artifact_manifest_path=configured.artifact_manifest_path,
        device=configured.device,
        compute_type=configured.compute_type,
    )
    token_verifier = verifier or (
        DevelopmentTokenVerifier()
        if configured.auth_mode == "disabled"
        else FirebaseTokenVerifier(configured.firebase_project_id)
    )
    scheduler = InferenceScheduler(tara_engine, configured.max_queue_depth)
    admission = ConnectionAdmission(
        configured.max_connections,
        configured.max_connections_per_user,
    )
    rate_limiter = UtteranceRateLimiter(
        configured.max_utterances_per_minute,
        configured.utterance_rate_window_seconds,
    )

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        await scheduler.start()
        application.state.started = True
        try:
            yield
        finally:
            application.state.started = False
            await scheduler.close()

    application = FastAPI(
        title="SpeakKeys Tara",
        version="0.1.0",
        docs_url=None,
        redoc_url=None,
        lifespan=lifespan,
    )
    application.state.started = False
    application.state.scheduler = scheduler
    application.state.admission = admission
    application.state.rate_limiter = rate_limiter

    @application.get("/health/live")
    async def live(response: Response) -> dict[str, str]:
        if application.state.started and not scheduler.healthy:
            response.status_code = 503
            return {"status": "worker_failed"}
        return {"status": "live"}

    @application.get("/health/ready")
    async def ready(response: Response) -> dict[str, str]:
        if not application.state.started or not scheduler.ready:
            response.status_code = 503
            return {"status": "loading"}
        return {"status": "ready", "queue_depth": str(scheduler.queue_depth)}

    @application.get("/health/worker")
    async def worker(response: Response) -> dict[str, str]:
        if not scheduler.ready:
            response.status_code = 503
            return {"status": "unavailable"}
        return {"status": "ready", "queue_depth": str(scheduler.queue_depth)}

    @application.get("/metrics")
    async def metrics() -> Response:
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    @application.websocket("/v1/realtime")
    async def realtime(websocket: WebSocket) -> None:
        await serve_websocket(
            websocket,
            scheduler,
            token_verifier,
            admission,
            rate_limiter,
            configured,
        )

    return application
