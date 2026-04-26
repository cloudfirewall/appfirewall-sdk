"""``AppFirewallMiddleware`` — pure ASGI, not BaseHTTPMiddleware.

BaseHTTPMiddleware buffers the full response body in memory, which breaks
streaming responses and adds real latency. Pure ASGI is slightly more verbose
but is the right choice for a middleware that promises to be invisible.

Responsibilities:
  1. Handle ``scope["type"] == "lifespan"`` for graceful startup/shutdown.
  2. On ``scope["type"] == "http"``, resolve the client IP, build a
     RequestContext, and stash it in the contextvar so ``appfirewall.record()``
     works from inside the handler.
  3. Wrap ``send`` to capture the response status for classification.
  4. After the handler completes (success *or* exception), emit an HTTP event
     with the final status and — if the status was 404 and classification is
     enabled — the classification.
  5. Optionally enforce rate limiting for scanner-class requests.

Fail-open: any exception inside our own code is caught and logged. The
customer's app continues to run. The only exception we re-raise is one raised
by the inner app itself — we never swallow those.
"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from typing import TYPE_CHECKING, Any

from ._classifier import Classification
from ._client import _Client
from ._context import RequestContext, reset_current_context, set_current_context

if TYPE_CHECKING:
    from starlette.types import ASGIApp, Message, Receive, Scope, Send

_LOG = logging.getLogger("appfirewall")


class AppFirewallMiddleware:
    """ASGI middleware. Add via ``app.add_middleware(AppFirewallMiddleware, ...)``.

    Example::

        from fastapi import FastAPI
        from appfirewall_fastapi import AppFirewallMiddleware

        app = FastAPI()
        app.add_middleware(AppFirewallMiddleware, api_key="afw_live_...")

    All keyword arguments other than the required ones have working defaults;
    see ``_Client.__init__`` for the full list.
    """

    def __init__(
        self,
        app: ASGIApp,
        *,
        api_key: str | None = None,
        endpoint: str | None = None,
        environment: str | None = None,
        mode: str = "ship",
        local_log_path: str | None = None,
        trusted_proxies: Sequence[str] = ("cloudflare",),
        classify_404: bool = True,
        rate_limit: dict[str, tuple[int, float]] | None = None,
        enforce_rate_limit: bool = False,
        on_error: str = "ignore",
    ) -> None:
        self.app = app

        # Resolve the API key. Env var is the canonical way to configure this
        # in production so the key never goes through source control.
        resolved_key = api_key or _env("APPFIREWALL_API_KEY")
        resolved_endpoint = endpoint or _env("APPFIREWALL_ENDPOINT")

        if mode == "ship" and not resolved_key:
            # Degrade gracefully: switch to "off". The customer will see a
            # single warning and nothing else breaks.
            _LOG.warning(
                "appfirewall: no API key provided (pass api_key=... or set "
                "APPFIREWALL_API_KEY). Disabling event shipping."
            )
            mode = "off"
            resolved_key = ""

        # Type-narrow the strings that came in as ``str`` for API friendliness
        # but are actually Literals inside the client.
        self._client = _Client(
            api_key=resolved_key or "",
            endpoint=resolved_endpoint,
            environment=environment,
            mode=mode,  # type: ignore[arg-type]
            local_log_path=local_log_path,
            trusted_proxies=trusted_proxies,
            classify_404=classify_404,
            rate_limit=rate_limit,
            enforce_rate_limit=enforce_rate_limit,
            on_error=on_error,  # type: ignore[arg-type]
        )

    async def __call__(
        self, scope: Scope, receive: Receive, send: Send
    ) -> None:
        scope_type = scope.get("type")

        if scope_type == "lifespan":
            await self._handle_lifespan(scope, receive, send)
            return

        if scope_type != "http":
            # websocket, ftp, etc. — just pass through untouched.
            await self.app(scope, receive, send)
            return

        await self._handle_http(scope, receive, send)

    # ------------------------------------------------------------------
    # HTTP path (hot)
    # ------------------------------------------------------------------
    async def _handle_http(
        self, scope: Scope, receive: Receive, send: Send
    ) -> None:
        # Build context. Wrap in try so a bug in our code never kills the request.
        ctx: RequestContext | None = None
        token: Any = None
        try:
            ctx = self._build_context(scope)
            token = set_current_context(ctx)
            await self._client.ensure_started()
        except Exception as exc:  # noqa: BLE001
            _LOG.debug("appfirewall: context build failed: %s", exc)
            # Fail open: call the app with whatever we managed to set up.
            await self.app(scope, receive, send)
            return

        # Wrap send to capture the final response status.
        async def send_wrapper(message: Message) -> None:
            if message["type"] == "http.response.start" and ctx is not None:
                ctx.status = int(message.get("status", 0))
            await send(message)

        # Inner-app execution. We do NOT swallow its exceptions — those are
        # the customer's bugs and must propagate. We only catch-and-log our
        # own bugs around it.
        exception_raised: BaseException | None = None
        try:
            await self.app(scope, receive, send_wrapper)
        except BaseException as exc:  # noqa: BLE001
            exception_raised = exc
            # An uncaught exception from the inner app means status was either
            # never sent (Starlette will convert it to 500) or the response
            # started and died midway. Record 500 if we don't have a real
            # status yet, since that's what the client will effectively see.
            if ctx is not None and ctx.status == 0:
                ctx.status = 500

        # Post-response: emit event, apply post-hoc rate-limit accounting.
        try:
            if ctx is not None:
                self._post_response(ctx)
        except Exception as exc:  # noqa: BLE001
            _LOG.debug("appfirewall: post-response hook failed: %s", exc)
        finally:
            if token is not None:
                try:
                    reset_current_context(token)
                except Exception:  # noqa: BLE001
                    pass

        if exception_raised is not None:
            raise exception_raised

    def _build_context(self, scope: Scope) -> RequestContext:
        # ASGI gives headers as list[tuple[bytes, bytes]]. Normalize to a
        # dict[str, str] with lowercase keys, which is what the IP resolver
        # and context builder expect.
        headers: dict[str, str] = {}
        for raw_name, raw_value in scope.get("headers", []):
            try:
                name = raw_name.decode("latin-1").lower()
                value = raw_value.decode("latin-1")
            except Exception:  # noqa: BLE001
                continue
            # Preserve the first occurrence of each header. For XFF/cookie
            # this mirrors typical WSGI-style behavior.
            headers.setdefault(name, value)

        client = scope.get("client")
        peer = client[0] if client else None

        raw_path = scope.get("path") or "/"
        # Strip query string; the classifier operates on the path only.
        path = raw_path.split("?", 1)[0]

        method = scope.get("method", "GET")
        return self._client.build_context(
            headers=headers, peer=peer, method=method, path=path
        )

    def _post_response(self, ctx: RequestContext) -> None:
        classification: Classification | None = None
        if ctx.status == 404 and self._client.classify_404:
            classification = self._client.classify_path(ctx.path)
            # Feed the rate limiter (no-op if enforce_rate_limit is False or
            # the classification isn't in the limiter map).
            self._client.rate_limited(ctx.ip, classification)
        self._client.record_http_event(ctx, classification)

    # ------------------------------------------------------------------
    # Lifespan
    # ------------------------------------------------------------------
    async def _handle_lifespan(
        self, scope: Scope, receive: Receive, send: Send
    ) -> None:
        # The inner app handles lifespan too; we wrap it so we can piggy-back
        # on the startup/shutdown events without the user having to wire
        # anything in.
        async def inner_receive() -> Message:
            message = await receive()
            mtype = message.get("type")
            if mtype == "lifespan.startup":
                # Our own startup is lazy-on-first-request, so nothing to do
                # here. Listed for clarity.
                pass
            elif mtype == "lifespan.shutdown":
                try:
                    await self._client.shutdown()
                except Exception as exc:  # noqa: BLE001
                    _LOG.debug("appfirewall: shutdown error: %s", exc)
            return message

        await self.app(scope, inner_receive, send)


def _env(name: str) -> str | None:
    import os

    val = os.environ.get(name)
    if val is None or val == "":
        return None
    return val
