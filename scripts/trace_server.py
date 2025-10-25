#!/usr/bin/env python3
"""Simple FastAPI service for uploading and hosting trace files."""

from __future__ import annotations

import base64
import logging
import os
import secrets
import shutil
import sys
from pathlib import Path
from typing import Optional, Tuple
import time

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
import uvicorn

DEFAULT_STORAGE = Path("/tmp/mperf")
TRACE_ID_BYTES = 15  # 120 bits; divisible by 3 to avoid Base64 padding
LOGGER = logging.getLogger("trace_server")


def ensure_storage(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    if not os.access(path, os.W_OK):
        raise PermissionError(f"Storage path {path} is not writable")
    return path


def store_trace(upload: UploadFile, storage_dir: Path) -> Tuple[str, Path]:
    random_bytes = secrets.token_bytes(TRACE_ID_BYTES)
    file_id = base64.urlsafe_b64encode(random_bytes).decode("ascii")
    data_path = storage_dir / file_id

    with data_path.open("wb") as outfile:
        shutil.copyfileobj(upload.file, outfile)

    return file_id, data_path


def resolve_trace_path(storage_dir: Path, file_id: str) -> Optional[Tuple[Path, str]]:
    data_path = storage_dir / file_id
    if not data_path.is_file():
        return None

    download_name = f"{file_id}.trace"
    return data_path, download_name


def create_app(storage_dir: Path) -> FastAPI:
    logger = logging.getLogger("trace_server")
    app = FastAPI(title="Trace Upload Service")
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=False,
        allow_methods=["GET", "POST", "OPTIONS"],
        allow_headers=["*"],
    )

    @app.middleware("http")
    async def log_requests(request, call_next):
        start = time.perf_counter()
        response = await call_next(request)
        elapsed_ms = (time.perf_counter() - start) * 1000
        logger.info(
            "%s %s -> %s (%.1f ms)",
            request.method,
            request.url.path,
            response.status_code,
            elapsed_ms,
        )
        return response

    @app.post("/trace")
    async def upload_trace(file: UploadFile = File(...)):
        file_id, saved_path = store_trace(file, storage_dir)
        logger.info("Stored trace %s at %s", file_id, saved_path)
        return JSONResponse(status_code=200, content={"id": file_id})

    @app.get("/trace/{trace_id}")
    async def download_trace(trace_id: str):
        resolved = resolve_trace_path(storage_dir, trace_id)
        if not resolved:
            raise HTTPException(status_code=404, detail="Trace not found")
        file_path, download_name = resolved
        return FileResponse(
            path=file_path,
            filename=download_name,
            media_type="application/octet-stream",
            headers={"Cache-Control": "no-cache"},
        )

    return app


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )
    storage_dir = ensure_storage(DEFAULT_STORAGE)
    app = create_app(storage_dir)

    uvicorn.run(
        app,
        host="127.0.0.1",
        port=8080,
        log_level="info",
    )


if __name__ == "__main__":
    try:
        main()
    except PermissionError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)
