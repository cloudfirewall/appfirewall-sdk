# appfirewall-fastapi

Origin-side abuse signal middleware for FastAPI apps behind Cloudflare.

Part of the **[AppFirewall][af]** platform by Sireto. Cloudflare protects your
edge; AppFirewall sees what your CDN can't — parse failures, auth failures, and
application-layer abuse signals at your origin — and closes the loop back to
the edge.

[af]: https://appfirewall.io

> **Status:** v0.1, pre-release. The public API (`AppFirewallMiddleware`,
> `appfirewall.record`) is stable; internals may change.

## Install

```bash
pip install appfirewall-fastapi
```

## Quick start

```python
from fastapi import FastAPI
from appfirewall_fastapi import AppFirewallMiddleware

app = FastAPI()
app.add_middleware(
    AppFirewallMiddleware,
    api_key="afw_live_...",   # or set APPFIREWALL_API_KEY
)
```

That's it. The middleware will:

- Resolve the real client IP from `cf-connecting-ip` (validated against
  Cloudflare's published IP ranges — never spoofable from outside).
- Classify 404s as `scanner` / `benign-miss` / `unknown` using a pattern
  library of known probes (`/wp-admin`, `/.env`, `/.git/config`,
  path-traversal, etc.).
- Ship events in batches (2s / 500 events, gzipped JSONL) to the AppFirewall
  ingest endpoint out-of-band, so your request path is never blocked.

## Recording app-layer signals

The value `AppFirewall` provides over edge-only protection comes from signals
your app can see but Cloudflare can't. Use `appfirewall.record()` inside your
handlers:

```python
from fastapi import HTTPException, UploadFile
from appfirewall_fastapi import appfirewall

@app.post("/upload")
async def upload(file: UploadFile):
    try:
        parsed = parse_flint(await file.read())
    except ParseError as e:
        appfirewall.record("upload.parse_failed", reason=str(e))
        raise HTTPException(400, "invalid format")
    appfirewall.record("upload.success", size=len(parsed))
    return {"ok": True}
```

`record()` is synchronous, non-blocking, and never raises — safe to sprinkle
anywhere. Outside a request, it's a silent no-op.

## Configuration

All options can be passed as keyword arguments to `add_middleware` or set via
environment variables.

| Option | Env var | Default | Purpose |
|---|---|---|---|
| `api_key` | `APPFIREWALL_API_KEY` | *(none)* | Bearer token. If unset, `mode` forces to `"off"`. |
| `endpoint` | `APPFIREWALL_ENDPOINT` | `https://ingest.appfirewall.io/v1/events` | Override for self-hosted ingest. |
| `environment` | — | `None` | Tag attached to every event. Useful for `production`/`staging`. |
| `mode` | — | `"ship"` | `"ship"` \| `"local"` \| `"off"`. |
| `local_log_path` | — | `None` | In `mode="local"`, write JSONL to this path instead of shipping. |
| `trusted_proxies` | — | `("cloudflare",)` | Accept `cf-connecting-ip` / XFF from these peers. |
| `classify_404` | — | `True` | Classify unknown 404s into scanner / benign / unknown. |
| `rate_limit` | — | `{"scanner": (10, 60.0)}` | Per-class limits: max per window, window seconds. |
| `enforce_rate_limit` | — | `False` | If True, send 429 when an IP exceeds its per-class limit in-process. Off by default — enforcement belongs at the edge. |
| `on_error` | — | `"ignore"` | `"ignore"` \| `"warn"` \| `"raise"`. |

## Fail-open guarantees

This middleware is deliberately conservative:

- If the middleware crashes, your app still serves the request.
- If ingest is down, events are buffered and dropped silently when the buffer
  fills. A circuit breaker prevents retry storms.
- If the API key is missing, `mode` flips to `"off"` with a single warning.
- `appfirewall.record()` never raises, never blocks, never awaits.
- Request latency overhead target: **<1ms p99**.

The tradeoff: the SDK's default is observation, not enforcement. Blocking bad
actors happens at the Cloudflare edge via the AppFirewall control plane, which
this SDK feeds. This is the design. For local enforcement in emergencies,
set `enforce_rate_limit=True`.

## Local development

Point `mode="local"` at a file on disk to iterate without an ingest endpoint:

```python
app.add_middleware(
    AppFirewallMiddleware,
    api_key="dev",
    mode="local",
    local_log_path="/tmp/appfirewall.jsonl",
)
```

Tail the file to watch events as your app runs:

```bash
tail -f /tmp/appfirewall.jsonl | jq .
```

## Development

```bash
pip install -e ".[dev]"
pytest
mypy src/
ruff check src/
```

All three must pass. See [`docs/CONTRIBUTING.md`](./docs/CONTRIBUTING.md) for
the PR workflow and [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) for the
module layout and design decisions. If you're an AI coding agent, start with
[`AGENTS.md`](./AGENTS.md).

## License

Apache-2.0.
