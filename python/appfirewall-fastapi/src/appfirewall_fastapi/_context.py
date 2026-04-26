"""Per-request context that rides the contextvar through the request's async call tree.

This is the mechanism that lets ``appfirewall.record("upload.parse_failed", ...)``
work from any handler or nested call without the user having to pass the context
explicitly. Starlette/ASGI already establishes a fresh contextvar scope per
request, so each concurrent request sees its own ``RequestContext`` instance.
"""

from __future__ import annotations

from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    # Avoid a runtime import cycle; _client imports from here too.
    from ._client import _Client


@dataclass
class RequestContext:
    """State captured at the top of each request, carried through the pipeline.

    Populated by ``AppFirewallMiddleware`` on every HTTP request. Downstream code
    (classifier, rate limiter, shipper) reads from this instead of re-parsing
    the ASGI scope each time it needs, say, the client IP.
    """

    # Identity
    ip: str                          # Resolved client IP (CF-aware, trusted-proxy-aware)
    method: str                      # HTTP method
    path: str                        # Request path, query string stripped
    ua: str | None = None         # User-Agent header, if present
    referer: str | None = None    # Referer header, if present

    # Cloudflare headers (present only when request came through a trusted CF peer)
    cf_country: str | None = None   # cf-ipcountry, two-letter ISO code
    cf_ray: str | None = None       # cf-ray, request ID for correlation
    cf_asn: str | None = None       # cf-asn, origin ASN (paid CF plans)

    # Populated by the middleware wrapping the ASGI send
    status: int = 0

    # Back-reference to the client so `appfirewall.record()` can enqueue events
    # without needing module-level state.
    client: _Client | None = field(default=None, repr=False)


# The contextvar itself. Each request sets this at the top of the middleware
# and clears it in the finally. Default None means "called outside a request",
# which is a silent no-op in the public record() API.
_current_ctx: ContextVar[RequestContext | None] = ContextVar(
    "appfirewall_current_ctx", default=None
)


def get_current_context() -> RequestContext | None:
    """Return the active ``RequestContext`` for this async task, if any."""
    return _current_ctx.get()


def set_current_context(ctx: RequestContext | None) -> Any:
    """Set the active ``RequestContext`` and return a token for later reset."""
    return _current_ctx.set(ctx)


def reset_current_context(token: Any) -> None:
    """Restore the previous context using the token from ``set_current_context``."""
    _current_ctx.reset(token)
