"""Public ``appfirewall.record()`` API.

Importable as ``from appfirewall_fastapi import appfirewall`` and callable
from any code path inside a request::

    @app.post("/upload")
    async def upload(file: UploadFile):
        try:
            parsed = parse_flint(await file.read())
        except ParseError as e:
            appfirewall.record("upload.parse_failed", reason=str(e))
            raise HTTPException(400, "invalid format")
        appfirewall.record("upload.success", size=len(parsed))
        return {"ok": True}

Called outside a request, ``record()`` is a silent no-op. We deliberately do
not attach ambient state here: the only way to associate a custom event with
a request is to call it from inside one. That keeps the API simple and
surprise-free.

This module defines a ``_Shield`` class and a singleton ``appfirewall``
instance. We use a class rather than bare module functions so the instance
can be passed around for testing (and so the name ``appfirewall`` reads as a
subject in customer code, not a namespace).
"""

from __future__ import annotations

from typing import Any

from ._context import get_current_context


class _Shield:
    """The object bound to the module-level ``appfirewall`` name."""

    def record(self, name: str, **fields: Any) -> None:
        """Record a custom event. Sync, non-blocking, never raises.

        ``name`` is a dotted event name like ``"upload.parse_failed"``. Use a
        short, stable name — the server side groups by this.

        Extra kwargs become the event's ``fields`` payload, sanitized by the
        client (see ``_client._sanitize_fields``).
        """
        try:
            ctx = get_current_context()
            # If we're inside a request, ctx.client is set. If we're called
            # from somewhere unusual (a scheduled task, an import-time hook),
            # there's no ctx and nothing to do.
            if ctx is None or ctx.client is None:
                return
            ctx.client.record_custom_event(ctx, name, dict(fields))
        except Exception:  # noqa: BLE001
            # record() is a fire-and-forget signaling primitive. It must never
            # raise into customer code, even if we have a bug. The cost of
            # swallowing here is that a misconfigured event name silently
            # vanishes; the server side has a "dropped events" counter for
            # operators who want to catch this.
            pass


# The singleton. Customers do ``from appfirewall_fastapi import appfirewall``.
appfirewall = _Shield()
