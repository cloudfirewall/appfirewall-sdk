# AGENTS.md

Guide for AI coding agents (Claude Code, Cursor, Codex, Aider, Devin, etc.)
working on this repository.

This file is the **single source of truth** for cross-SDK conventions. If
you're an agent reading this, start here, then read the SDK-specific
`README.md` / `docs/ARCHITECTURE.md` inside the SDK directory you're working
on. Human-facing docs live at the repo root (`README.md`, `ROADMAP.md`,
`docs/`) and in each SDK directory.

---

## What this project is

`appfirewall-sdk` is the monorepo for **AppFirewall** SDKs (a Sireto
platform). Each SDK is middleware that sits inside a customer's web
application and:

1. Observes requests at the application layer (what the CDN can't see).
2. Classifies 404s (scanner / benign-miss / unknown) and records outcomes.
3. Exposes a `record(event, fields)` API for app-layer signals.
4. Ships events in batches to the AppFirewall ingest service, out of band.

Across SDKs the **wire format, classifier patterns, IP-resolution semantics,
and event schema must stay byte-compatible.** A single ingest service serves
all of them.

### Repository layout

```
appfirewall-sdk/
├── README.md / AGENTS.md / CLAUDE.md / ROADMAP.md / LICENSE
├── docs/
│   ├── CONTRIBUTING.md          ← shared PR workflow
│   └── specs/                   ← forward-looking specs for un-built SDKs
├── .github/workflows/
│   └── <lang>-<framework>-publish.yml   ← one per SDK, path-filtered
├── python/
│   └── appfirewall-fastapi/     ← reference implementation, ships today
│       ├── README.md
│       ├── pyproject.toml
│       ├── docs/ARCHITECTURE.md
│       ├── src/, tests/, example/
└── (java/, node/, etc. — added as new SDKs land)
```

The directory name of each SDK matches its published artifact name. The
FastAPI SDK is the **reference implementation**: when behaviour is ambiguous
in another SDK, the FastAPI code is the source of truth.

The marketing pitch is on the public site; don't worry about that here.

---

## Golden rules — do not violate

These are non-negotiable product commitments. Every change must preserve them.

1. **Fail open.** If this middleware crashes or misbehaves, the customer's app
   still serves the request. Every exception inside our code — in the hot
   path, in the shipper, in the rate limiter, anywhere — is caught and
   logged. The *only* exception that propagates is one raised by the customer's
   own handler.

2. **Never block the request path.** `emit()` is `put_nowait` + drop-oldest.
   `record()` is synchronous and never awaits. Shipping happens on a
   background task. Request latency overhead target: <1ms p99.

3. **Never trust forwarded headers without peer validation.**
   `cf-connecting-ip` and `x-forwarded-for` are only honored when the socket
   peer is an explicitly trusted proxy. This is a security property, not a
   convenience. Tests assert this and they must keep passing.

4. **No required network calls at import.** `from appfirewall_fastapi import ...`
   never hits the network. All network activity (CF range refresh, ingest
   POST) is lazy and bounded.

5. **The public API is stable.** `AppFirewallMiddleware`, `appfirewall.record()`,
   `APPFIREWALL_API_KEY`, `APPFIREWALL_ENDPOINT`. Breaking changes to these
   require a major version bump and a migration note.

---

## SDK layout (FastAPI reference)

The Python/FastAPI SDK lives at `python/appfirewall-fastapi/`:

```
python/appfirewall-fastapi/
├── README.md              ← end-user install/usage
├── pyproject.toml         ← package metadata, tool config (ruff, mypy, pytest)
├── docs/
│   └── ARCHITECTURE.md    ← module layout + key design decisions
├── src/appfirewall_fastapi/
│   ├── __init__.py        ← public API: AppFirewallMiddleware, appfirewall
│   ├── _middleware.py     ← ASGI middleware class
│   ├── _client.py         ← coordinator; owns buffer, classifier, limiter
│   ├── _context.py        ← RequestContext dataclass + contextvar
│   ├── _classifier.py     ← 404 path → scanner/benign-miss/unknown
│   ├── _ratelimit.py      ← per-IP sliding-window limiter
│   ├── _buffer.py         ← EventBuffer + background Shipper
│   ├── _breaker.py        ← circuit breaker
│   ├── _ip.py             ← client IP resolution + CF header extraction
│   ├── _cf_ranges.py      ← Cloudflare IP range registry + refresh
│   ├── _shield.py         ← _Shield class + appfirewall singleton
│   └── py.typed           ← PEP 561 marker
├── tests/
│   ├── conftest.py
│   ├── test_classifier.py
│   ├── test_ip.py
│   ├── test_ratelimit.py
│   ├── test_middleware.py ← end-to-end via ASGI
│   └── test_fail_open.py  ← failure-injection tests
└── example/               ← runnable demo app
```

All non-public modules are prefixed with `_`. Do not import from `_*` modules
in tests you add — import from the package root (`from appfirewall_fastapi
import ...`) where possible.

New SDKs follow the same shape under their own language directory (e.g.
`java/appfirewall-spring-boot/`). Forward-looking specs live in
`docs/specs/`.

---

## Commands you'll use (FastAPI SDK)

All commands assume your working directory is `python/appfirewall-fastapi/`.

### Setup
```bash
cd python/appfirewall-fastapi
pip install -e ".[dev]"
```

### Tests
```bash
pytest                          # all tests, quiet mode per pyproject
pytest tests/test_ip.py -v      # single file, verbose
pytest -k "test_cf_peer" -v     # by name
pytest -x --tb=short            # stop at first failure, short traceback
```

### Type check
```bash
mypy src/                       # strict mode per pyproject
```

### Lint
```bash
ruff check src/ tests/          # check
ruff check src/ tests/ --fix    # auto-fix safe issues
```

### Build
```bash
python -m build                 # produces dist/*.whl and dist/*.tar.gz
```

### End-to-end check (run before declaring a change done)
```bash
pytest && mypy src/ && ruff check src/ tests/
```
All three must pass. No exceptions.

---

## Coding conventions

### Style

- **Python 3.9+ compatible.** CI tests 3.9 through 3.13.
- **`from __future__ import annotations`** at the top of every module. This lets
  us use modern typing syntax (`list[int]`, `str | None`) while staying 3.9
  compatible, because annotations are stringified.
- **Type hints everywhere.** `mypy --strict` must pass. New code without type
  hints will be rejected.
- **Line length: 100.** Configured in pyproject.
- **Prefer explicit over clever.** This is infrastructure code that will be
  read more than written.

### Error handling

- **In the hot path (middleware, ratelimiter, buffer.emit):** catch
  `Exception`, log at DEBUG, swallow. The customer's request must not be
  affected by our bugs. Use `except Exception:  # noqa: BLE001` pattern.
- **In user-facing API (`record()`):** same — silent no-op on error.
- **In background tasks (shipper, CF refresh):** catch and continue the loop.
  Never let a background task die.
- **In validation/init code:** `raise ValueError` with a descriptive message
  is acceptable. Fails at setup time, not request time.

### Naming

- `AppFirewallMiddleware` — the public middleware class
- `appfirewall` — the public singleton (lowercase, since it reads as a
  subject in customer code: `appfirewall.record(...)`)
- `_Client`, `_Shield`, `_*.py` — internals, underscore-prefixed
- Environment variables: `APPFIREWALL_*`
- API key prefixes: `afw_live_...`, `afw_test_...`

### Comments

Comments should explain **why**, not **what**. If the what isn't obvious from
the code, simplify the code first. Specifically:

- Inline rationale for non-obvious choices (e.g. "we use put_nowait because…")
- Security-relevant invariants (e.g. "this header is untrusted unless…")
- Known limitations / v0.2 concerns

Don't do: running narration of control flow, restatements of type hints,
"what the function returns" when it's obvious.

---

## Testing expectations

- **Every new feature needs tests.** No exceptions for "it's obvious."
- **Fail-open tests are sacred.** `tests/test_fail_open.py` exists specifically
  to verify the app serves requests when our code breaks. Add to it when you
  add a new failure mode that could leak out.
- **End-to-end tests drive ASGI lifespan explicitly.** See `run_in_lifespan`
  helper in `tests/test_middleware.py`. The `httpx.ASGITransport` does not
  drive lifespan on its own — if you forget this, the shipper's shutdown flush
  never fires and your events file will be empty. Read the existing tests
  before writing new ones.
- **IP-resolution tests are a security boundary.** If you change `_ip.py`,
  `tests/test_ip.py` must still pass and ideally gain new cases.
- **Parametrize classifier tests.** Scanner/benign patterns are data, not
  code — prefer `@pytest.mark.parametrize` over one-test-per-pattern.

---

## When adding a new feature

Work through this list, in order:

1. **Is it in scope for v0.1?** Check `ROADMAP.md`. If it's a v0.2+ item,
   don't start yet — adding it now bloats v0.1 past shippable.
2. **Does it violate a Golden Rule?** If yes, stop and ask.
3. **Where does it live?** Match the existing module split. Cross-cutting
   concerns go in `_client.py`. Request-path logic goes in `_middleware.py`.
   Don't create a new module without a reason.
4. **Tests first.** Write the failing test, then the code. Especially for
   anything in the hot path.
5. **Docs.** If it's a public API, update `README.md`. If it's architectural,
   update `docs/ARCHITECTURE.md`. If it's a future direction, update
   `ROADMAP.md`.
6. **Run the full check.** `pytest && mypy src/ && ruff check src/ tests/`.

---

## What NOT to do

- **Do not add dependencies without a clear reason.** The whole package has
  two runtime deps: `httpx` and `starlette`. Adding a third requires
  justification. Adding `pydantic` is a no — it's too heavy for middleware.
- **Do not introduce a new global mutable state.** The contextvar + the client
  instance owned by the middleware are the only two sources of shared state,
  and that's deliberate.
- **Do not block the request path, even briefly.** If you find yourself
  writing `await` in the middleware's hot path for something other than the
  inner app call, stop and rethink.
- **Do not add a config option without thinking about its default.** The
  default must be safe for the most common deployment (FastAPI app behind
  Cloudflare). Power users can flip flags.
- **Do not expand the public API surface casually.** Each public symbol is a
  compatibility commitment for years. When in doubt, add a private helper and
  wait to see if users actually need it exposed.
- **Do not commit secrets, API keys, or customer data.** Not even in test
  fixtures. Use `"test"` or `"afw_test_xxx"` placeholders.
- **Do not bump the version number.** Releases are gated through a process
  documented in `docs/CONTRIBUTING.md`.

---

## Common tasks — quick reference

### Add a new scanner pattern

1. Edit `_SCANNER_SUBSTRINGS` in `src/appfirewall_fastapi/_classifier.py`.
2. Add a `@pytest.mark.parametrize` case in `tests/test_classifier.py`.
3. Run `pytest tests/test_classifier.py`.

### Add a new configuration option

1. Add the parameter to `_Client.__init__` in `_client.py` with a sensible
   default.
2. Surface it through `AppFirewallMiddleware.__init__` in `_middleware.py`.
3. Document it in the README's configuration table.
4. Add a test in the relevant test file.

### Debug a test that hangs

Likely cause: lifespan not being driven, or the shipper task is stuck. Check
whether the test uses `run_in_lifespan` (it should if it reads events). The
default lifespan timeout is 10 seconds; if a test takes longer than that,
something is wrong.

### Update Cloudflare IP ranges

The baked-in list is in `_cf_ranges.py::_BAKED_V4` and `_BAKED_V6`. It
refreshes automatically in production from `api.cloudflare.com/client/v4/ips`.
Refresh the baked-in snapshot only when you change the refresh interval or
ship a long-lived release.

---

## If you're stuck

- Read `docs/ARCHITECTURE.md` for the why.
- Read `HANDOFF.md` for the most recent state-of-the-world.
- Check `ROADMAP.md` to see if your problem is a known future-item.
- Don't invent a solution to a problem not stated in the issue. Ask.
