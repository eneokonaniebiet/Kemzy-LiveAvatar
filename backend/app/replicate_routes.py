from __future__ import annotations

import hmac
import os
from typing import Any

import httpx
from fastapi import APIRouter, File, Header, HTTPException, UploadFile

router = APIRouter(prefix="/v1/replicate", tags=["Replicate FasterLivePortrait"])

REPLICATE_API = "https://api.replicate.com/v1"
MODEL_VERSION = "87c30186211a69bb4636d86823fec8e261f77e82999c868efad71fe9b4464142"
MAX_FILE_BYTES = 100 * 1024 * 1024


def _authorize(x_kemzy_access_key: str | None) -> str:
    expected = (os.getenv("KEMZY_REPLICATE_ACCESS_KEY") or "").strip()
    token = (os.getenv("REPLICATE_API_TOKEN") or "").strip()
    if not expected or not token:
        raise HTTPException(status_code=503, detail="Replicate integration is not configured")
    if not x_kemzy_access_key or not hmac.compare_digest(x_kemzy_access_key, expected):
        raise HTTPException(status_code=401, detail="Unauthorized")
    return token


async def _read_upload(file: UploadFile, expected_prefix: str) -> bytes:
    content_type = (file.content_type or "").lower()
    if not content_type.startswith(expected_prefix):
        raise HTTPException(status_code=415, detail=f"Expected a {expected_prefix.rstrip('/')} file")
    data = await file.read(MAX_FILE_BYTES + 1)
    if not data:
        raise HTTPException(status_code=400, detail=f"{file.filename or 'File'} is empty")
    if len(data) > MAX_FILE_BYTES:
        raise HTTPException(status_code=413, detail="Each file must be 100 MB or smaller")
    return data


async def _upload_to_replicate(client: httpx.AsyncClient, token: str, data: bytes, filename: str, content_type: str) -> str:
    response = await client.post(
        f"{REPLICATE_API}/files",
        headers={"Authorization": f"Bearer {token}"},
        files={"content": (filename, data, content_type)},
    )
    if response.is_error:
        raise HTTPException(status_code=502, detail=f"Replicate file upload failed ({response.status_code})")
    payload = response.json()
    file_url = (payload.get("urls") or {}).get("get") or payload.get("url")
    if not file_url:
        raise HTTPException(status_code=502, detail="Replicate did not return a file URL")
    return str(file_url)


@router.post("/animate")
async def animate(
    source_image: UploadFile = File(...),
    driving_video: UploadFile = File(...),
    fps: int = 30,
    driving_multiplier: float = 1.0,
    x_kemzy_access_key: str | None = Header(default=None),
) -> dict[str, Any]:
    token = _authorize(x_kemzy_access_key)
    if not 1 <= fps <= 60:
        raise HTTPException(status_code=422, detail="fps must be between 1 and 60")
    if not 0.1 <= driving_multiplier <= 3:
        raise HTTPException(status_code=422, detail="driving_multiplier must be between 0.1 and 3")

    image_data = await _read_upload(source_image, "image/")
    video_data = await _read_upload(driving_video, "video/")
    image_name = source_image.filename or "source-image.jpg"
    video_name = driving_video.filename or "driving-video.mp4"
    image_type = source_image.content_type or "image/jpeg"
    video_type = driving_video.content_type or "video/mp4"

    timeout = httpx.Timeout(120.0, connect=20.0)
    async with httpx.AsyncClient(timeout=timeout) as client:
        image_url = await _upload_to_replicate(client, token, image_data, image_name, image_type)
        video_url = await _upload_to_replicate(client, token, video_data, video_name, video_type)
        response = await client.post(
            f"{REPLICATE_API}/predictions",
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
                "Prefer": "wait=1",
            },
            json={
                "version": MODEL_VERSION,
                "input": {
                    "source_image": image_url,
                    "driving_video": video_url,
                    "fps": fps,
                    "driving_multiplier": driving_multiplier,
                },
            },
        )
    if response.is_error:
        raise HTTPException(status_code=502, detail=f"Replicate prediction request failed ({response.status_code})")
    result = response.json()
    return {
        "prediction_id": result.get("id"),
        "status": result.get("status", "starting"),
        "output": result.get("output"),
        "urls": result.get("urls", {}),
        "error": result.get("error"),
    }


@router.get("/predictions/{prediction_id}")
async def get_prediction(
    prediction_id: str,
    x_kemzy_access_key: str | None = Header(default=None),
) -> dict[str, Any]:
    token = _authorize(x_kemzy_access_key)
    if not prediction_id or len(prediction_id) > 128 or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_" for c in prediction_id):
        raise HTTPException(status_code=400, detail="Invalid prediction ID")
    async with httpx.AsyncClient(timeout=httpx.Timeout(30.0, connect=10.0)) as client:
        response = await client.get(
            f"{REPLICATE_API}/predictions/{prediction_id}",
            headers={"Authorization": f"Bearer {token}"},
        )
    if response.is_error:
        raise HTTPException(status_code=502, detail=f"Replicate prediction lookup failed ({response.status_code})")
    result = response.json()
    return {
        "prediction_id": result.get("id"),
        "status": result.get("status"),
        "output": result.get("output"),
        "error": result.get("error"),
        "urls": result.get("urls", {}),
        "metrics": result.get("metrics", {}),
    }
