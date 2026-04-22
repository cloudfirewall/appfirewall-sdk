"""End-to-end middleware tests using a real FastAPI app + httpx.AsyncClient.

These exercise the full stack: ASGI lifespan, HTTP request, context resolution,
classification, event emission (local mode), and graceful shutdown.

Important implementation note: ``httpx.ASGITransport`` does NOT drive the ASGI
lifespan protocol. That means the shipper's shutdown flush never fires from
just using a client — tests that want to assert on persisted events must
explicitly drive lifespan (startup → requests → shutdown) and read the log
only *after* shutdown completes.

The helper here, ``run_in_lifespan``, wires that up.
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator, Awaitable
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Callable

import httpx
import pytest
from fastapi import FastAPI, HTTPException
from fastapi.responses import PlainTextResponse

from appfirewall_fastapi import AppFirewallMiddleware, appfirewall

# ---------------------------------------------------------------------------
# Test harness
# ---------------------------------------------------------------------------


@asynccontextmanager
async def lifespan_ctx(app: FastAPI) -> AsyncIterator[None]:
    """Drive ASGI lifespan startup/shutdown around a block of test code.

    The shipper starts lazily on the first request and is flushed/cancelled
    on ``lifespan.shutdown``. Without this, tests that read the event log
    will see missing or incomplete events.
    """
    startup_done = asyncio.Event()
    shutdown_done = asyncio.Event()
    recv_queue: asyncio.Queue[dict] = asyncio.Queue()

    async def recv() -> dict:
        return await recv_queue.get()

    async def send(message: dict) -> None:
        mtype = message["type"]
        if mtype == "lifespan.startup.complete":
            startup_done.set()
        elif mtype == "lifespan.shutdown.complete":
            shutdown_done.set()

    scope = {"type": "lifespan", "asgi": {"version": "3.0", "spec_version": "2.3"}}
    task = asyncio.create_task(app(scope, recv, send))  # type: ignore[arg-type]

    try:
        await recv_queue.put({"type": "lifespan.startup"})
        await asyncio.wait_for(startup_done.wait(), timeout=5.0)
        yield
    finally:
        await recv_queue.put({"type": "lifespan.shutdown"})
        try:
            await asyncio.wait_for(shutdown_done.wait(), timeout=10.0)
        except asyncio.TimeoutError:
            pass
        try:
            await asyncio.wait_for(task, timeout=1.0)
        except (asyncio.TimeoutError, Exception):  # noqa: BLE001
            if not task.done():
                task.cancel()


async def run_in_lifespan(
    app: FastAPI,
    body: Callable[[httpx.AsyncClient], Awaitable[None]],
) -> None:
    """Run ``body(client)`` inside a lifespan scope, guaranteeing shutdown flush."""
    transport = httpx.ASGITransport(app=app)
    async with lifespan_ctx(app):
        async with httpx.AsyncClient(
            transport=transport, base_url="http://testserver"
        ) as client:
            await body(client)
        # On exit from the lifespan_ctx, the middleware's shutdown runs and
        # the shipper drains.


def read_events(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text().splitlines() if line]


@pytest.fixture
def local_log(tmp_path: Path) -> Path:
    return tmp_path / "events.jsonl"


def build_app(local_log: Path) -> FastAPI:
    app = FastAPI()
    app.add_middleware(
        AppFirewallMiddleware,
        api_key="test",
        mode="local",
        local_log_path=str(local_log),
        # Test peer is 127.0.0.1 (not CF), so trust it as a generic proxy.
        trusted_proxies=["127.0.0.0/8"],
    )

    @app.get("/")
    def root() -> dict[str, str]:
        return {"ok": "yes"}

    @app.post("/upload")
    def upload() -> dict[str, str]:
        appfirewall.record("upload.success", size=100)
        return {"ok": "yes"}

    @app.get("/fail")
    def fail() -> None:
        raise HTTPException(500, "synthetic")

    @app.get("/plaintext")
    def plaintext() -> PlainTextResponse:
        return PlainTextResponse("hello")

    return app


# ---------------------------------------------------------------------------
# Basic HTTP flow
# ---------------------------------------------------------------------------


class TestBasicFlow:
    async def test_200_request_records_event(self, local_log: Path) -> None:
        app = build_app(local_log)

        async def body(client: httpx.AsyncClient) -> None:
            resp = await client.get("/")
            assert resp.status_code == 200
            assert resp.json() == {"ok": "yes"}

        await run_in_lifespan(app, body)

        events = read_events(local_log)
        http_events = [e for e in events if e["type"] == "http_request"]
        assert len(http_events) == 1
        assert http_events[0]["path"] == "/"
        assert http_events[0]["status"] == 200
        assert http_events[0]["method"] == "GET"

    async def test_404_gets_classification(self, local_log: Path) -> None:
        app = build_app(local_log)

        async def body(client: httpx.AsyncClient) -> None:
            resp = await client.get("/wp-admin")
            assert resp.status_code == 404

        await run_in_lifespan(app, body)

        events = read_events(local_log)
        scanner_events = [
            e for e in events
            if e.get("type") == "http_request" and e.get("classification") == "scanner"
        ]
        assert len(scanner_events) == 1
        assert scanner_events[0]["path"] == "/wp-admin"

    async def test_favicon_is_benign(self, local_log: Path) -> None:
        app = build_app(local_log)

        async def body(client: httpx.AsyncClient) -> None:
            resp = await client.get("/favicon.ico")
            assert resp.status_code == 404

        await run_in_lifespan(app, body)

        events = read_events(local_log)
        benign = [
            e for e in events
            if e.get("type") == "http_request" and e.get("classification") == "benign-miss"
        ]
        assert len(benign) == 1

    async def test_unknown_404_recorded(self, local_log: Path) -> None:
        app = build_app(local_log)

        async def body(client: httpx.AsyncClient) -> None:
            resp = await client.get("/some-random-path-that-doesnt-exist")
            assert resp.status_code == 404

        await run_in_lifespan(app, body)

        events = read_events(local_log)
        http_events = [e for e in events if e["type"] == "http_request"]
        assert len(http_events) == 1
        assert http_events[0]["classification"] == "unknown"

    async def test_plaintext_response_passes_through(self, local_log: Path) -> None:
        """Regression: the middleware must not buffer/touch response bodies.

        Pure ASGI (what we use) supports streaming. BaseHTTPMiddleware would
        buffer. This test exists so we notice if somebody changes the
        middleware inheritance.
        """
        app = build_app(local_log)

        async def body(client: httpx.AsyncClient) -> None:
            resp = await client.get("/plaintext")
            assert resp.status_code == 200
            assert resp.text == "hello"
            assert resp.headers["content-type"].startswith("text/plain")

        await run_in_lifespan(app, body)


# ---------------------------------------------------------------------------
# Custom record() API
# ---------------------------------------------------------------------------


class TestCustomRecord:
    async def test_record_from_handler(self, local_log: Path) -> None:
        app = build_app(local_log)

        async def body(client: httpx.AsyncClient) -> None:
            resp = await client.post("/upload")
            assert resp.status_code == 200

        await run_in_lifespan(app, body)

        events = read_events(local_log)
        custom = [e for e in events if e["type"] == "custom"]
        assert len(custom) == 1
        assert custom[0]["name"] == "upload.success"
        assert custom[0]["fields"]["size"] == 100

    async def test_record_outside_request_is_noop(self) -> None:
        """Calling record() at module level / outside any request must not raise."""
        appfirewall.record("noise", x=1)
        # The point is no exception — nothing to assert.


# ---------------------------------------------------------------------------
# Exceptions
# ---------------------------------------------------------------------------


class TestExceptionHandling:
    async def test_http_exception_status_recorded(self, local_log: Path) -> None:
        """HTTPException is converted by Starlette to a response; we see the status."""
        app = build_app(local_log)

        async def body(client: httpx.AsyncClient) -> None:
            resp = await client.get("/fail")
            assert resp.status_code == 500

        await run_in_lifespan(app, body)

        events = read_events(local_log)
        http_events = [e for e in events if e["type"] == "http_request"]
        assert any(e["status"] == 500 and e["path"] == "/fail" for e in http_events)


# ---------------------------------------------------------------------------
# Concurrency
# ---------------------------------------------------------------------------


class TestConcurrency:
    async def test_record_from_concurrent_requests_isolated(
        self, local_log: Path
    ) -> None:
        """Each concurrent request must have its own contextvar — record()
        events must be tagged with the correct request path.
        """
        app = FastAPI()
        app.add_middleware(
            AppFirewallMiddleware,
            api_key="test",
            mode="local",
            local_log_path=str(local_log),
            trusted_proxies=["127.0.0.0/8"],
        )

        @app.get("/a")
        async def handler_a() -> dict[str, str]:
            # Yield the loop so /b's request can interleave.
            await asyncio.sleep(0.01)
            appfirewall.record("tag", which="a")
            return {"ok": "a"}

        @app.get("/b")
        async def handler_b() -> dict[str, str]:
            await asyncio.sleep(0.01)
            appfirewall.record("tag", which="b")
            return {"ok": "b"}

        async def body(client: httpx.AsyncClient) -> None:
            results = await asyncio.gather(
                client.get("/a"), client.get("/b"),
                client.get("/a"), client.get("/b"),
            )
            for r in results:
                assert r.status_code == 200

        await run_in_lifespan(app, body)

        events = read_events(local_log)
        custom = [e for e in events if e["type"] == "custom"]
        assert len(custom) == 4
        # Each custom event's path should match its which= tag.
        for e in custom:
            which = e["fields"]["which"]
            path = e["path"]
            assert path == f"/{which}", f"context leak: which={which} path={path}"
