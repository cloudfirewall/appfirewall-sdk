"""Fail-open tests.

The central promise of this middleware: if something goes wrong in our code,
the customer's app still works. These tests deliberately inject failures and
verify the app still returns 200.

Injection points covered:
  - Missing API key (defaults to mode="off", no events, no crash)
  - Local log path unwritable (event drop, no crash)
  - Classifier exception simulated
  - Buffer emit called from weird contexts
"""

from __future__ import annotations

from pathlib import Path
from unittest.mock import patch

import httpx
from fastapi import FastAPI

from appfirewall_fastapi import AppFirewallMiddleware, appfirewall


async def _hit_root(app: FastAPI) -> int:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as c:
        resp = await c.get("/")
    return resp.status_code


class TestNoApiKey:
    async def test_missing_key_does_not_crash(self) -> None:
        app = FastAPI()
        # No api_key provided. Should log a warning and switch to mode="off".
        app.add_middleware(AppFirewallMiddleware)

        @app.get("/")
        def root() -> dict[str, str]:
            return {"ok": "yes"}

        status = await _hit_root(app)
        assert status == 200


class TestUnwritableLocalLog:
    async def test_broken_log_path_still_serves(self, tmp_path: Path) -> None:
        # Point to a path that can't be written (a file used as a parent dir).
        conflicting = tmp_path / "not-a-dir"
        conflicting.write_text("i am a file")
        bad_path = conflicting / "events.jsonl"

        app = FastAPI()
        app.add_middleware(
            AppFirewallMiddleware,
            api_key="test",
            mode="local",
            local_log_path=str(bad_path),
        )

        @app.get("/")
        def root() -> dict[str, str]:
            return {"ok": "yes"}

        status = await _hit_root(app)
        assert status == 200


class TestClassifierCrash:
    async def test_classifier_error_does_not_break_request(
        self, tmp_path: Path
    ) -> None:
        app = FastAPI()
        app.add_middleware(
            AppFirewallMiddleware,
            api_key="test",
            mode="local",
            local_log_path=str(tmp_path / "events.jsonl"),
        )

        @app.get("/")
        def root() -> dict[str, str]:
            return {"ok": "yes"}

        with patch(
            "appfirewall_fastapi._client.classify",
            side_effect=RuntimeError("boom"),
        ):
            status = await _hit_root(app)
            assert status == 200


class TestRecordFailureIsolation:
    async def test_record_with_unserializable_does_not_crash(self, tmp_path: Path) -> None:
        app = FastAPI()
        app.add_middleware(
            AppFirewallMiddleware,
            api_key="test",
            mode="local",
            local_log_path=str(tmp_path / "events.jsonl"),
        )

        class Weird:
            def __repr__(self) -> str:
                raise RuntimeError("can't repr")

        @app.get("/")
        def root() -> dict[str, str]:
            appfirewall.record("weird", thing=Weird())
            return {"ok": "yes"}

        status = await _hit_root(app)
        assert status == 200


class TestWeirdScope:
    async def test_no_client_peer(self) -> None:
        # Some ASGI servers don't populate scope["client"]. We should not crash.
        app = FastAPI()
        app.add_middleware(AppFirewallMiddleware, api_key="test", mode="off")

        @app.get("/")
        def root() -> dict[str, str]:
            return {"ok": "yes"}

        # Build an ASGI scope manually without the "client" key.
        scope = {
            "type": "http",
            "asgi": {"version": "3.0", "spec_version": "2.3"},
            "http_version": "1.1",
            "method": "GET",
            "scheme": "http",
            "path": "/",
            "raw_path": b"/",
            "query_string": b"",
            "headers": [],
            "server": ("testserver", 80),
            # note: no "client" key
        }

        messages: list[dict] = []

        async def receive() -> dict:
            return {"type": "http.request", "body": b"", "more_body": False}

        async def send(message: dict) -> None:
            messages.append(message)

        # Get the ASGI app root (the middleware). Starlette wraps; we go through
        # the same __call__ path the server would use.
        await app(scope, receive, send)  # type: ignore[arg-type]

        # Response should still be 200.
        start = next(m for m in messages if m["type"] == "http.response.start")
        assert start["status"] == 200
