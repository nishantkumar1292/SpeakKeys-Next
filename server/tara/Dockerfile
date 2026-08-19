FROM nvidia/cuda:12.4.1-cudnn-runtime-ubuntu22.04

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

RUN apt-get update \
    && apt-get install --no-install-recommends -y python3 python3-pip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /service
COPY pyproject.toml ./
COPY requirements-runtime.txt ./
COPY src ./src
COPY scripts ./scripts
RUN python3 -m pip install --upgrade pip \
    && python3 -m pip install -r requirements-runtime.txt \
    && python3 -m pip install --no-deps .

ENV PORT=8080 \
    TARA_MODEL_DIR=/models/tara-ct2 \
    TARA_PROCESSOR_DIR=/models/tara-hf \
    TARA_ARTIFACT_MANIFEST=/models/tara-manifest.json \
    TARA_DEVICE=cuda \
    TARA_COMPUTE_TYPE=float16 \
    TARA_AUTH_MODE=firebase \
    TARA_ENVIRONMENT=production

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=120s --retries=3 \
    CMD ["python3", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health/ready', timeout=2)"]

# Exactly one process owns the GPU. Horizontal replicas provide concurrency and HA.
CMD ["python3", "-m", "speakkeys_tara"]
