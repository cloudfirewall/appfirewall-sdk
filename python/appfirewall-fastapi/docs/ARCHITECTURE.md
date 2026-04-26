# Architecture

How the pieces fit together, and why the key decisions were made that way.

---

## System context

```
┌──────────────┐    ┌──────────────┐    ┌──────────────────┐
│   Internet   ├───▶│  Cloudflare  ├───▶│  Customer origin │
└──────────────┘    └──────────────┘    │  (FastAPI app)   │
                           ▲            │                  │
                           │            │ ┌──────────────┐ │
                   ┌───────┴────────┐   │ │ appfirewall- │ │
                   │ AppFirewall    │   │ │   fastapi    │ │
                   │ platform       │◀──┼─┤  middleware  │ │
                   │ (SaaS)         │   │ └──────────────┘ │
                   └────────────────┘   └──────────────────┘
```

The SDK sits inside the customer's FastAPI app, observes app-layer signals,
and ships events out-of-band to the AppFirewall platform. The platform
correlates across customers' events and (in v0.3+) writes rules back to
Cloudflare via the CF API.

The SDK in v0.1 is **observation-only**. It doesn't block; it doesn't call
Cloudflare; it doesn't know anything about policy. Its whole job is producing
trustworthy signals.

---

## Module layout

```
src/appfirewall_fastapi/
├── __init__.py        Public API only
├── _middleware.py     ASGI entry point
├── _client.py         Coordinator (owns the subsystems)
├── _context.py        Per-request state + contextvar
├── _classifier.py     404 path → scanner/benign-miss/unknown
├── _ratelimit.py      Per-IP sliding-window limiter
├── _ip.py             Client IP resolution + CF metadata
├── _cf_ranges.py      Cloudflare IP range registry
├── _buffer.py         Event queue + background shipper
├── _breaker.py        Circuit breaker for shipper
└── _shield.py         _Shield class + public `appfirewall` singleton
```

Rule of thumb: a module is either (a) a pure computation with no I/O, or
(b) a small, focused stateful component with a single responsibility.
`_client.py` is the only file that orchestrates multiple subsystems, and
it's deliberately thin — it doesn't *do* work, it delegates.

### Dependency graph

```
          ┌──────────────┐
          │ _middleware  │  (ASGI entry)
          └──────┬───────┘
                 │ uses
                 ▼
          ┌──────────────┐       ┌──────────────┐
          │   _client    │──────▶│   _context   │  (RequestContext + contextvar)
          └──┬──┬──┬──┬──┘       └──────────────┘
             │  │  │  │
     ┌───────┘  │  │  └───────────┐
     ▼          ▼  ▼              ▼
┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐
│ _ip +  │ │_classif│ │_rate   │ │_buffer │
│_cf_ran │ │  ier   │ │ limit  │ │        │
└────────┘ └────────┘ └────────┘ └───┬────┘
                                     │
                                     ▼
                                ┌────────┐
                                │_breaker│
                                └────────┘

_shield.py → _context.py → _client.py (via back-reference in RequestContext)
```

No cycles. `_context.py` imports `_client` only under `TYPE_CHECKING` to
avoid the import-time cycle with the back-reference.

---

## Request lifecycle

Here's what happens on a single HTTP request.

```
1. uvicorn/hypercorn accepts connection
2. ASGI scope dispatched to middleware stack
3. AppFirewallMiddleware.__call__ receives scope
4. _handle_http() runs:
   a. _build_context() — parse headers, resolve IP, extract CF metadata
   b. set contextvar to RequestContext
   c. await client.ensure_started()  (idempotent; starts shipper on first req)
   d. wrap `send` to capture response status
   e. await inner app(scope, receive, send_wrapper)
      — customer handler may call appfirewall.record(...), which reads
        contextvar → calls client.record_custom_event() → buffer.emit()
   f. post-response: if status was 404 and classify_404, classify + record
   g. reset contextvar
5. Response complete. Event is already in the asyncio queue.

Out of band: shipper task batches events, gzips, POSTs to ingest.
```

Hot-path cost budget: **<1ms p99** of middleware overhead. This is achievable
because everything in the hot path is either a dict lookup, a regex search,
or a bounded small-N list iteration. No synchronous I/O. No thread crossings.

---

## Key design decisions

### Pure ASGI, not `BaseHTTPMiddleware`

Starlette's `BaseHTTPMiddleware` is easier to write but has two problems:

1. It buffers the entire response body into memory. Breaks streaming, adds
   latency.
2. It uses an internal anyio thread for the inner app, which creates
   context-propagation edge cases.

Pure ASGI is lower-level — we write `__call__(self, scope, receive, send)`
directly — but it's the right primitive for middleware that promises to be
invisible. We wrap `send` to capture the response status, and that's it.

**Never change this.** There's a regression test
(`test_plaintext_response_passes_through`) that fails if someone "improves"
the middleware to `BaseHTTPMiddleware`.

### Contextvars for `record()`

The public `appfirewall.record(event, **fields)` API works from any handler
or nested call without the user passing any context. This is done via
`contextvars.ContextVar` set at the top of the request and reset in the
`finally` block.

Why contextvars and not a thread-local: contextvars propagate correctly
across `asyncio.create_task`, `asyncio.to_thread`, and Starlette's
threadpool offload. Thread-locals don't. Since FastAPI freely mixes sync and
async handlers (running sync ones on a threadpool), a thread-local would
silently lose context in common cases.

Corollary: **each request gets its own ContextVar scope.** ASGI ensures this
by default. There's a concurrency test
(`test_record_from_concurrent_requests_isolated`) that verifies interleaved
requests don't leak context.

### `emit()` is synchronous drop-oldest

The event buffer's `emit()` is called from the hot request path (and from
`record()` inside handlers). It must:

1. Not block (no `await`, no lock contention).
2. Not allocate arbitrarily (no unbounded queue).
3. Not raise.

Implementation: `asyncio.Queue(maxsize=10_000).put_nowait(event)`. On full,
we `get_nowait()` one event (drop oldest) and retry `put_nowait()`. If that
fails too (race), we drop the new event and log once.

**Why drop oldest and not drop newest?** Recent events are more valuable
than stale ones. An ingest outage that causes 10,000 events to pile up is
better resolved by the most recent 10,000 than the first 10,000 captured.

### Circuit breaker on the shipper

When ingest is down, we don't want to pile up failing retries that consume
CPU and log noise. Three states:

- **CLOSED**: normal, shipping works.
- **OPEN** (after 5 consecutive failures): drop batches for 30 seconds.
- **HALF_OPEN** (after cooldown): let one batch through; if it succeeds, go
  back to CLOSED; if it fails, reset the timer.

During OPEN, events still accumulate in the buffer (and the oldest get dropped
as it fills). When we recover, we start shipping fresh events, not the
queued-up backlog. This is intentional — replaying 30 seconds of queued
events when ingest comes back would hide the outage from our own metrics on
the ingest side.

### IP resolution: validate the peer

Golden rule: **never trust a forwarded-for-style header without validating
the peer.** The architecture:

```
resolve_client_ip(headers, peer, config, cf_ranges):
    if "cloudflare" in config.trusted AND peer ∈ cf_ranges:
        trust cf-connecting-ip
    elif peer ∈ config.user_cidrs:
        trust leftmost x-forwarded-for (or x-real-ip)
    else:
        use peer
```

This function is small (~30 lines) but security-critical. Every change to it
needs to preserve the invariant that spoofed headers from untrusted peers
are ignored.

Same rule for `extract_cf_metadata`: `cf-ipcountry` / `cf-ray` / `cf-asn` are
only consumed when the peer is a real Cloudflare IP. Otherwise a bot could
set `cf-ipcountry: XX` and pollute analytics.

### CF IP ranges: baked in + background refresh

The published CF IP range list changes rarely (maybe 1-2 times a year). We
ship a snapshot at build time so the SDK works immediately, before any
network call succeeds. A background task refreshes from
`api.cloudflare.com/client/v4/ips` every 24 hours. If the refresh fails,
we keep the baked-in list — failing the refresh must never cause IP
resolution to break.

### Fail-open by default

Almost every `except Exception:` in this codebase is deliberate. The
customer's app has value to the customer; our middleware has value to the
customer. If the middleware breaks, the app continuing to serve requests is
strictly more valuable than the middleware producing an error.

**Exception: validation errors in `_Client.__init__`.** Bad config at startup
(e.g. `enforce_rate_limit=True` with invalid thresholds) raises. That's a
build-time error the developer will see and fix. It's not a hot-path error.

### Observation-first, enforcement opt-in

The default is to record events and not block. `enforce_rate_limit=False`
is the default. This is because:

1. The product is intended to work in concert with Cloudflare. Enforcement at
   the origin is redundant when CF is doing its job.
2. False-positive rate-limiting in production is a customer-facing disaster.
   Getting the defaults wrong once would kill adoption.
3. Customers who want to enforce locally (because they can't or don't want
   the CF-API integration) can flip `enforce_rate_limit=True`.

The platform story is: SDK observes → platform decides → CF enforces. SDK
having its own opinion is a fallback, not the path.

---

## Testing philosophy

- **Unit tests for pure functions** (`_classifier`, `_ratelimit`, `_ip`).
  Fast, deterministic, parametrized.
- **End-to-end tests through a real ASGI stack** (`test_middleware.py`).
  These drive lifespan + requests + shutdown using `httpx.ASGITransport`
  plus a custom `run_in_lifespan` helper. Covers the whole request
  lifecycle in-process.
- **Failure-injection tests** (`test_fail_open.py`). We patch the classifier
  to raise, we point `local_log_path` at an unwritable location, we send
  `record()` unserializable values. The app must still serve 200.
- **No mocks of our own code.** If something is hard to test without mocking
  it out, the design is probably wrong.

---

## Extension points (v0.2+ concerns)

When adding distributed coordination, geoip enrichment, or new frameworks,
keep these seams in mind:

- **`_ratelimit.SlidingWindowLimiter`** is the interface the client uses.
  Swapping in a Redis backend means implementing `hit(ip) -> bool` and
  `current_count(ip) -> int` with the same semantics.
- **`_buffer.EventBuffer.emit(event)`** is how all events enter the pipeline.
  A distributed-buffer variant implements `emit` with `XADD` to a stream.
- **`_classifier.classify(path)`** returns one of three strings. Extending
  it to look at more than just the path means changing its signature — do it
  as a new function, not by growing the existing one.
- **`_client._Client`** is where a new subsystem plugs in. It owns the
  shared config and lifecycle.

The public API (`AppFirewallMiddleware`, `appfirewall.record`, env vars)
is frozen. Everything else can change.
