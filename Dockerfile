# Kémzy Neural Renderer GPU image
# This image is for the persistent GPU worker, not the lightweight Render gateway.

FROM nvidia/cuda:12.8.1-cudnn-runtime-ubuntu24.04

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PYTHONPATH=/app/backend/zerogpu_space \
    PORT=8000 \
    LIVEPORTRAIT_REPO=/opt/LivePortrait \
    LIVEPORTRAIT_WEIGHTS=/opt/LivePortrait/pretrained_weights \
    KEMZY_ALLOW_MODEL_DOWNLOAD=1

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       python3 python3-pip python3-dev git ffmpeg libgl1 libglib2.0-0 \
    && ln -sf /usr/bin/python3 /usr/local/bin/python \
    && ln -sf /usr/bin/pip3 /usr/local/bin/pip \
    && rm -rf /var/lib/apt/lists/*

COPY backend/zerogpu_space/requirements.txt /tmp/renderer-requirements.txt
RUN python -m pip install --break-system-packages -r /tmp/renderer-requirements.txt \
    && rm -f /tmp/renderer-requirements.txt

COPY backend/zerogpu_space /app/backend/zerogpu_space

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=10s --start-period=15m --retries=10 \
    CMD python -c "import os,urllib.request; urllib.request.urlopen('http://127.0.0.1:' + os.environ.get('PORT','8000') + '/health', timeout=8)" || exit 1

CMD ["sh", "-c", "exec uvicorn app:app --host 0.0.0.0 --port ${PORT}"]
