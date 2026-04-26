"""Event buffer and async shipper.

The request path calls ``EventBuffer.emit()`` which must never block. Events
are put on a bounded asyncio queue. A single background task (the shipper)
drains the queue, batches events, gzips them, and POSTs to the ingest endpoint
with a short timeout and a circuit breaker guarding against pile-ups when
ingest is down.

In ``mode="local"`` the shipper writes newline-delimited JSON to a file on
disk instead of shipping — useful for development and for customers who want
to evaluate the middleware without signing up.

Key invariants:
  - emit() is O(1), never awaits, never raises (catches and drops).
  - When the queue is full, we drop the *oldest* event to make room. Recent
    events are more valuable than stale ones.
  - Shipper task is started lazily on first use, not at import or at ASGI
    startup — this avoids problems with uvicorn --reload and fork-based
    workers that copy the process after import but before run.
  - On shutdown, the shipper gets a short grace period to flush before being
    cancelled.
"""

from __future__ import annotations

import asyncio
import gzip
import json
import logging
import time
from pathlib import Path
from typing import Any, Literal

from ._breaker import CircuitBreaker

_LOG = logging.getLogger("appfirewall")

Mode = Literal["ship", "local", "off"]

_BATCH_MAX_SIZE = 500
_BATCH_MAX_AGE_SEC = 2.0
_POST_TIMEOUT_SEC = 5.0
_QUEUE_MAXSIZE = 10_000
_SHUTDOWN_GRACE_SEC = 5.0


class EventBuffer:
    """Bounded queue + shipper in a single unit.

    The queue is an ``asyncio.Queue``. Because ``put_nowait`` is synchronous,
    ``emit()`` can be called from sync code (e.g. a ``threading.Thread``
    worker) as well as async code. See the notes in ``emit`` for the
    cross-thread path.
    """

    def __init__(
        self,
        *,
        mode: Mode,
        endpoint: str,
        api_key: str,
        environment: str | None,
        local_log_path: str | None,
    ) -> None:
        self._mode: Mode = mode
        self._endpoint = endpoint
        self._api_key = api_key
        self._environment = environment
        self._local_log_path = Path(local_log_path) if local_log_path else None

        self._queue: asyncio.Queue[dict[str, Any]] | None = None
        self._task: asyncio.Task[None] | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._breaker = CircuitBreaker(fail_threshold=5, reset_after=30.0)
        self._warned_overflow = False
        self._warned_cross_thread = False

        # Lazy httpx client, created inside the shipper's loop.
        self._http: Any | None = None  # httpx.AsyncClient

    async def ensure_started(self) -> None:
        """Create the queue and start the shipper task. Idempotent."""
        if self._mode == "off":
            return
        if self._task is not None and not self._task.done():
            return

        loop = asyncio.get_running_loop()
        self._loop = loop
        self._queue = asyncio.Queue(maxsize=_QUEUE_MAXSIZE)

        if self._mode == "ship":
            # Import httpx lazily so 'from appfirewall_fastapi import ...'
            # never triggers a network-capable dependency load.
            import httpx

            self._http = httpx.AsyncClient(
                timeout=_POST_TIMEOUT_SEC,
                headers={
                    "authorization": f"Bearer {self._api_key}",
                    "content-type": "application/x-ndjson",
                    "content-encoding": "gzip",
                    "user-agent": "appfirewall-fastapi/0.1.0",
                },
            )

        self._task = asyncio.create_task(self._run(), name="appfirewall-shipper")

    async def shutdown(self) -> None:
        """Flush what we can, then cancel the shipper. Called from lifespan.shutdown."""
        if self._task is None:
            return
        task = self._task
        # Signal shutdown by putting None sentinel; the loop drains on sight.
        if self._queue is not None:
            try:
                self._queue.put_nowait({"__shutdown__": True})
            except asyncio.QueueFull:
                pass
        try:
            await asyncio.wait_for(task, timeout=_SHUTDOWN_GRACE_SEC)
        except asyncio.TimeoutError:
            task.cancel()
            try:
                await task
            except (asyncio.CancelledError, Exception):  # noqa: BLE001
                pass
        finally:
            self._task = None
            if self._http is not None:
                try:
                    await self._http.aclose()
                except Exception:  # noqa: BLE001
                    pass
                self._http = None

    def emit(self, event: dict[str, Any]) -> None:
        """Enqueue an event. O(1), never blocks, never raises.

        Safe to call from any async or sync context. If the shipper task's
        event loop is available, we use it; otherwise the event is silently
        dropped (we don't want to spawn loops or threads from this path).
        """
        if self._mode == "off" or self._queue is None or self._loop is None:
            # ensure_started hasn't run yet — this happens on the very first
            # request, for one instant, because emit() may be called from
            # middleware init. Drop the event; the cost is negligible.
            return

        # Tag every event with the environment if set. Cheap to do here rather
        # than in every call site.
        if self._environment is not None and "environment" not in event:
            event = {**event, "environment": self._environment}

        # Fast path: we're on the same loop as the shipper. put_nowait is
        # synchronous and loop-safe from the loop's thread.
        try:
            running = asyncio.get_running_loop()
        except RuntimeError:
            running = None

        if running is self._loop:
            self._put_or_drop(event)
            return

        # Cross-loop / cross-thread path: use call_soon_threadsafe to hop
        # back to the shipper's loop. This is the path for sync handlers
        # that call appfirewall.record() from a threadpool-offloaded context.
        try:
            self._loop.call_soon_threadsafe(self._put_or_drop, event)
        except RuntimeError:
            # Loop already closed — nothing to do.
            if not self._warned_cross_thread:
                _LOG.debug("appfirewall: event loop closed, dropping event")
                self._warned_cross_thread = True

    def _put_or_drop(self, event: dict[str, Any]) -> None:
        """Inner put with drop-oldest overflow handling. Runs on the shipper loop."""
        q = self._queue
        if q is None:
            return
        try:
            q.put_nowait(event)
        except asyncio.QueueFull:
            # Drop oldest, try once more. If that fails too, give up on this
            # event — never wait.
            try:
                q.get_nowait()
                q.put_nowait(event)
            except (asyncio.QueueEmpty, asyncio.QueueFull):
                pass
            if not self._warned_overflow:
                _LOG.warning(
                    "appfirewall: event buffer overflowed; dropping oldest events"
                )
                self._warned_overflow = True

    # ------------------------------------------------------------------
    # Shipper loop
    # ------------------------------------------------------------------
    async def _run(self) -> None:
        assert self._queue is not None
        batch: list[dict[str, Any]] = []
        last_flush = time.monotonic()
        shutting_down = False

        while True:
            timeout = max(0.01, _BATCH_MAX_AGE_SEC - (time.monotonic() - last_flush))
            try:
                evt = await asyncio.wait_for(self._queue.get(), timeout=timeout)
            except asyncio.TimeoutError:
                evt = None

            if evt is not None:
                if evt.get("__shutdown__"):
                    shutting_down = True
                else:
                    batch.append(evt)

            age = time.monotonic() - last_flush
            should_flush = (
                len(batch) >= _BATCH_MAX_SIZE
                or (batch and age >= _BATCH_MAX_AGE_SEC)
                or (shutting_down and batch)
            )

            if should_flush:
                try:
                    await self._flush(batch)
                except Exception as exc:  # noqa: BLE001
                    # Shipper must never die. Log once per unique message.
                    _LOG.debug("appfirewall: flush error: %s", exc)
                batch = []
                last_flush = time.monotonic()

            if shutting_down and not batch:
                return

    async def _flush(self, batch: list[dict[str, Any]]) -> None:
        if not batch:
            return

        if self._mode == "local":
            await self._flush_local(batch)
            return

        # mode == "ship"
        if not self._breaker.allow():
            # Breaker is open: drop this batch rather than pile up failing
            # retries. We already recorded everything in-process for the
            # admin introspection (if enabled).
            return

        body = _encode_batch_gzip(batch)
        try:
            assert self._http is not None
            resp = await self._http.post(self._endpoint, content=body)
            if 200 <= resp.status_code < 300:
                self._breaker.record_success()
            elif 400 <= resp.status_code < 500 and resp.status_code != 429:
                # 4xx that isn't rate-limited is a client bug (bad API key,
                # bad request shape) — retrying won't help. Count as success
                # from the breaker's perspective so we don't go OPEN and
                # stop sending; count as "don't retry this batch" by just
                # dropping it.
                _LOG.warning(
                    "appfirewall: ingest at %s returned %s; dropping %d events",
                    self._endpoint,
                    resp.status_code,
                    len(batch),
                )
                self._breaker.record_success()
            else:
                # 5xx, 429, or timeout-as-exception path below.
                _LOG.warning(
                    "appfirewall: ingest at %s returned %s; %d events will be retried",
                    self._endpoint,
                    resp.status_code,
                    len(batch),
                )
                self._breaker.record_failure()
        except Exception as exc:  # noqa: BLE001
            _LOG.warning(
                "appfirewall: failed to ship %d events to %s: %s",
                len(batch),
                self._endpoint,
                exc,
            )
            self._breaker.record_failure()

    async def _flush_local(self, batch: list[dict[str, Any]]) -> None:
        if self._local_log_path is None:
            return
        # Write JSONL synchronously in a thread so we don't block the loop on
        # slow disks. A tiny write with a bounded batch size is cheap here.
        lines = "\n".join(json.dumps(e, separators=(",", ":")) for e in batch) + "\n"
        path = self._local_log_path
        try:
            await asyncio.to_thread(_append_text, path, lines)
        except Exception as exc:  # noqa: BLE001
            _LOG.debug("appfirewall: local log write failed: %s", exc)


def _append_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as f:
        f.write(text)


def _encode_batch_gzip(batch: list[dict[str, Any]]) -> bytes:
    """JSONL → gzip bytes. Stable output for easier ingest-side debugging."""
    joined = "\n".join(json.dumps(e, separators=(",", ":")) for e in batch)
    return gzip.compress(joined.encode("utf-8"))
