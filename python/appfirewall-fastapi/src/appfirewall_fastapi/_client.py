"""Internal coordinator.

The middleware and the public ``appfirewall.record()`` API both talk to a
``_Client`` instance. The client owns the subsystems (event buffer, classifier,
rate limiter, CF ranges), handles lifecycle (start background tasks lazily,
shut them down gracefully), and provides the small methods those callers need.

The split keeps the middleware focused on ASGI mechanics and the classifier/
rate-limiter/buffer modules focused on their own concerns.
"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from datetime import datetime, timezone
from typing import Any, Literal

from ._buffer import EventBuffer, Mode
from ._cf_ranges import CloudflareRanges
from ._classifier import Classification, classify
from ._context import RequestContext
from ._ip import TrustedProxyConfig, extract_cf_metadata, resolve_client_ip
from ._ratelimit import SlidingWindowLimiter

_LOG = logging.getLogger("appfirewall")

ErrorMode = Literal["ignore", "warn", "raise"]

_DEFAULT_ENDPOINT = "https://ingest.appfirewall.io/v1/events"


class _Client:
    """One per AppFirewallMiddleware instance. Holds all shared state."""

    def __init__(
        self,
        *,
        api_key: str,
        endpoint: str | None = None,
        environment: str | None = None,
        mode: Mode = "ship",
        local_log_path: str | None = None,
        trusted_proxies: Sequence[str] = ("cloudflare",),
        classify_404: bool = True,
        rate_limit: dict[str, tuple[int, float]] | None = None,
        enforce_rate_limit: bool = False,
        on_error: ErrorMode = "ignore",
    ) -> None:
        self.api_key = api_key
        self.endpoint = endpoint or _DEFAULT_ENDPOINT
        self.environment = environment
        self.mode: Mode = mode
        self.on_error: ErrorMode = on_error
        self.classify_404 = classify_404
        self.enforce_rate_limit = enforce_rate_limit

        self._trusted = TrustedProxyConfig(list(trusted_proxies))
        if self._trusted.invalid:
            _LOG.warning(
                "appfirewall: ignoring invalid trusted_proxies entries: %s",
                self._trusted.invalid,
            )

        self._cf_ranges = CloudflareRanges()

        # Per-class rate limiters. Only 'scanner' is populated in v0.1.
        limits = rate_limit or {"scanner": (10, 60.0)}
        self._limiters: dict[str, SlidingWindowLimiter] = {
            cls: SlidingWindowLimiter(max_events=n, window_seconds=w)
            for cls, (n, w) in limits.items()
        }

        self._buffer = EventBuffer(
            mode=mode,
            endpoint=self.endpoint,
            api_key=api_key,
            environment=environment,
            local_log_path=local_log_path,
        )

        self._started = False

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------
    async def ensure_started(self) -> None:
        if self._started:
            return
        try:
            await self._buffer.ensure_started()
            await self._cf_ranges.start_refresh()
        except Exception as exc:  # noqa: BLE001
            self._handle_error("ensure_started", exc)
        self._started = True

    async def shutdown(self) -> None:
        try:
            await self._cf_ranges.stop_refresh()
        except Exception:  # noqa: BLE001
            pass
        try:
            await self._buffer.shutdown()
        except Exception:  # noqa: BLE001
            pass
        self._started = False

    # ------------------------------------------------------------------
    # Request-time API (called from the middleware hot path)
    # ------------------------------------------------------------------
    def build_context(
        self,
        *,
        headers: dict[str, str],
        peer: str | None,
        method: str,
        path: str,
    ) -> RequestContext:
        """Resolve client IP + CF metadata and build a RequestContext."""
        ip = resolve_client_ip(
            headers=headers, peer=peer, config=self._trusted, cf_ranges=self._cf_ranges
        )
        country, ray, asn = extract_cf_metadata(
            headers=headers, peer=peer, config=self._trusted, cf_ranges=self._cf_ranges
        )
        return RequestContext(
            ip=ip,
            method=method,
            path=path,
            ua=headers.get("user-agent"),
            referer=headers.get("referer"),
            cf_country=country,
            cf_ray=ray,
            cf_asn=asn,
            client=self,
        )

    def classify_path(self, path: str) -> Classification:
        return classify(path)

    def rate_limited(self, ip: str, classification: Classification) -> bool:
        """Record a hit for this IP in this class; return True if over limit.

        Only called when ``self.enforce_rate_limit`` is True AND the hit is in
        a class that has a limiter configured. Benign-miss and unknown never
        feed the limiter in v0.1.
        """
        if not self.enforce_rate_limit:
            return False
        limiter = self._limiters.get(classification)
        if limiter is None:
            return False
        return limiter.hit(ip)

    def record_http_event(self, ctx: RequestContext, classification: Classification | None) -> None:
        """Called once per request, after the response status is known."""
        event: dict[str, Any] = {
            "type": "http_request",
            "ts": _now_iso(),
            "ip": ctx.ip,
            "method": ctx.method,
            "path": ctx.path,
            "status": ctx.status,
        }
        if ctx.ua:
            event["ua"] = ctx.ua
        if ctx.referer:
            event["referer"] = ctx.referer
        if ctx.cf_country:
            event["cf_country"] = ctx.cf_country
        if ctx.cf_ray:
            event["cf_ray"] = ctx.cf_ray
        if ctx.cf_asn:
            event["cf_asn"] = ctx.cf_asn
        if classification is not None:
            event["classification"] = classification
        self._buffer.emit(event)

    def record_custom_event(
        self, ctx: RequestContext | None, name: str, fields: dict[str, Any]
    ) -> None:
        """Called from the public ``appfirewall.record(name, **fields)``.

        If ``ctx`` is None, the record was made outside a request. That's
        allowed but we flag it with origin="no_request" so the server can tell.
        """
        event: dict[str, Any] = {
            "type": "custom",
            "ts": _now_iso(),
            "name": name,
            "fields": _sanitize_fields(fields),
        }
        if ctx is not None:
            event["ip"] = ctx.ip
            event["path"] = ctx.path
            event["method"] = ctx.method
            if ctx.cf_ray:
                event["cf_ray"] = ctx.cf_ray
        else:
            event["origin"] = "no_request"
        self._buffer.emit(event)

    # ------------------------------------------------------------------
    # Errors
    # ------------------------------------------------------------------
    def _handle_error(self, where: str, exc: BaseException) -> None:
        if self.on_error == "raise":
            raise exc
        if self.on_error == "warn":
            _LOG.warning("appfirewall: error in %s: %s", where, exc)
        # "ignore": truly silent


# ----------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------

def _now_iso() -> str:
    """RFC3339 UTC timestamp with millisecond precision."""
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace(
        "+00:00", "Z"
    )


# Types that serialize safely to JSON without surprises. Anything else gets
# repr()'d — we never want record() to raise because a user passed a weird
# object like a datetime or a custom class.
_SAFE_JSON_TYPES = (str, int, float, bool, type(None))
_MAX_STR_LEN = 2_000
_MAX_FIELDS = 50


def _sanitize_fields(fields: dict[str, Any]) -> dict[str, Any]:
    """Make a best-effort JSON-safe copy of user-supplied fields.

    Rules:
      - Keep up to _MAX_FIELDS entries. Extra keys are silently dropped.
      - Scalars pass through, clipped to _MAX_STR_LEN for strings.
      - Lists/tuples/dicts of scalars are kept with the same clipping.
      - Anything else becomes ``repr(value)``, also clipped.
    """
    out: dict[str, Any] = {}
    for i, (k, v) in enumerate(fields.items()):
        if i >= _MAX_FIELDS:
            break
        out[str(k)[:_MAX_STR_LEN]] = _sanitize_value(v)
    return out


def _sanitize_value(v: Any) -> Any:
    if isinstance(v, str):
        return v[:_MAX_STR_LEN]
    if isinstance(v, _SAFE_JSON_TYPES):
        return v
    if isinstance(v, (list, tuple)):
        return [_sanitize_value(x) for x in list(v)[:100]]
    if isinstance(v, dict):
        return {
            str(k)[:_MAX_STR_LEN]: _sanitize_value(val)
            for k, val in list(v.items())[:50]
        }
    try:
        return repr(v)[:_MAX_STR_LEN]
    except Exception:  # noqa: BLE001
        return "<unrepresentable>"
