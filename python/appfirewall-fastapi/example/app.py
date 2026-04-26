"""Demo FastAPI app for exercising the AppFirewall ingest + portal end-to-end.

Run::

    pip install -e ".[dev]"
    pip install uvicorn
    cd example
    uvicorn app:app --reload --port 8000

Then drive traffic with ``curl`` (see README.md in this directory) and watch
events arrive at the local ingest service running on ``http://localhost:8080``.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import random
from collections.abc import AsyncIterator

import httpx
from fastapi import FastAPI, HTTPException, Request, UploadFile

from appfirewall_fastapi import AppFirewallMiddleware, appfirewall

API_KEY = os.environ.get("APPFIREWALL_API_KEY", "afw_live_fch4PvUyCdGkfZZ2eNueXe1M")
ENDPOINT = os.environ.get("APPFIREWALL_ENDPOINT", "http://localhost:8080/v1/events")
# ENDPOINT = os.environ.get("APPFIREWALL_ENDPOINT", "https://events.appfirewall.io/v1/events")

AUTODRIVE = os.environ.get("APPFIREWALL_DEMO_AUTODRIVE", "1") not in ("0", "false", "")
AUTODRIVE_INTERVAL_SEC = float(os.environ.get("APPFIREWALL_DEMO_INTERVAL", "3.0"))

_LOG = logging.getLogger("appfirewall.demo")

# Mix of healthy traffic, app-layer signals, scanner probes, and benign 404s.
# The autodriver picks one of these per tick at random.
_SCANNER_PATHS = [
    "/wp-admin",
    "/wp-login.php",
    "/.env",
    "/.git/config",
    "/phpmyadmin/",
    "/actuator/env",
    "/solr/admin/info/system",
    "/admin.php",
    "/shell.php",
    "/backup.sql",
    "/etc/passwd",
]
_BENIGN_404_PATHS = ["/favicon.ico", "/robots.txt", "/items/0"]
_HEALTHY_PATHS = ["/", "/health", "/items/1", "/items/42", "/items/777", "/checkout"]
_SIM_CLIENT_IPS = [
    "203.0.113.7",
    "203.0.113.42",
    "198.51.100.99",
    "198.51.100.7",
    "192.0.2.55",
]


@contextlib.asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    task: asyncio.Task[None] | None = None
    if AUTODRIVE:
        task = asyncio.create_task(_autodrive_loop(), name="appfirewall-demo-autodrive")
        _LOG.warning(
            "appfirewall demo: autodriver started (interval=%.1fs). "
            "Set APPFIREWALL_DEMO_AUTODRIVE=0 to disable.",
            AUTODRIVE_INTERVAL_SEC,
        )
    try:
        yield
    finally:
        if task is not None:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task


app = FastAPI(title="AppFirewall demo app", lifespan=lifespan)

app.add_middleware(
    AppFirewallMiddleware,
    api_key=API_KEY,
    endpoint=ENDPOINT,
    environment="demo",
    # Trust XFF from loopback so curl --header 'X-Forwarded-For: ...' works
    # for simulating different client IPs during local testing.
    trusted_proxies=("127.0.0.1/32", "::1/128"),
)


@app.get("/")
def index() -> dict[str, str]:
    return {"app": "appfirewall-demo", "status": "ok"}


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/items/{item_id}")
def get_item(item_id: int) -> dict[str, int | str]:
    if item_id == 0:
        raise HTTPException(404, "no such item")
    return {"id": item_id, "name": f"item-{item_id}"}


@app.post("/login")
async def login(request: Request) -> dict[str, str]:
    body = await request.json()
    user = body.get("username", "")
    password = body.get("password", "")
    if password != "correct-horse-battery-staple":
        appfirewall.record("auth.login_failed", username=user, reason="bad_password")
        raise HTTPException(401, "invalid credentials")
    appfirewall.record("auth.login_success", username=user)
    return {"status": "ok"}


@app.post("/upload")
async def upload(file: UploadFile) -> dict[str, str | int]:
    data = await file.read()
    if not data.startswith(b"VALID:"):
        appfirewall.record(
            "upload.parse_failed",
            filename=file.filename or "",
            size=len(data),
            reason="bad_magic",
        )
        raise HTTPException(400, "invalid format")
    appfirewall.record("upload.success", filename=file.filename or "", size=len(data))
    return {"status": "ok", "size": len(data)}


@app.get("/checkout")
def checkout() -> dict[str, str | int]:
    if random.random() < 0.1:
        appfirewall.record("checkout.declined", reason="card_declined")
        raise HTTPException(402, "payment required")
    amount = random.randint(500, 50000)
    appfirewall.record("checkout.completed", amount_cents=amount)
    return {"status": "ok", "amount_cents": amount}


@app.get("/boom")
def boom() -> None:
    raise RuntimeError("intentional crash to test fail-open + 500 reporting")


# ----------------------------------------------------------------------
# Autodriver: hits this app's own endpoints in a loop so events flow to
# the ingest without anyone curling. Disable with APPFIREWALL_DEMO_AUTODRIVE=0.
# ----------------------------------------------------------------------


async def _autodrive_loop() -> None:
    base_url = os.environ.get("APPFIREWALL_DEMO_BASE_URL", "http://127.0.0.1:9001")
    # Small initial delay so uvicorn finishes binding the port before we hit it.
    await asyncio.sleep(1.5)
    async with httpx.AsyncClient(base_url=base_url, timeout=5.0) as client:
        while True:
            try:
                await _autodrive_tick(client)
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                _LOG.debug("autodrive tick failed: %s", exc)
            await asyncio.sleep(AUTODRIVE_INTERVAL_SEC)


async def _autodrive_tick(client: httpx.AsyncClient) -> None:
    """One iteration. Picks a behaviour at random, weighted toward healthy."""
    headers = {"x-forwarded-for": random.choice(_SIM_CLIENT_IPS)}
    behaviour = random.choices(
        ["healthy", "scanner", "benign_404", "login", "upload", "boom"],
        weights=[5, 4, 2, 3, 2, 1],
        k=1,
    )[0]

    if behaviour == "healthy":
        path = random.choice(_HEALTHY_PATHS)
        await client.get(path, headers=headers)
        return

    if behaviour == "scanner":
        path = random.choice(_SCANNER_PATHS)
        await client.get(path, headers=headers)
        return

    if behaviour == "benign_404":
        path = random.choice(_BENIGN_404_PATHS)
        await client.get(path, headers=headers)
        return

    if behaviour == "login":
        good = random.random() < 0.4
        await client.post(
            "/login",
            json={
                "username": random.choice(["alice", "bob", "carol", "dave"]),
                "password": "correct-horse-battery-staple" if good else "nope",
            },
            headers=headers,
        )
        return

    if behaviour == "upload":
        good = random.random() < 0.5
        body = b"VALID:hello world" if good else b"GARBAGE-bytes"
        files = {"file": ("payload.bin", body, "application/octet-stream")}
        await client.post("/upload", files=files, headers=headers)
        return

    if behaviour == "boom":
        await client.get("/boom", headers=headers)
        return
