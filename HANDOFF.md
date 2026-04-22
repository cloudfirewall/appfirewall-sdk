# HANDOFF

Written: 2026-04-23
Written for: the next contributor (human or AI) picking up this repo.

This is a **one-time handoff document**. It captures the current state of the
world so you can get productive without reading every file. It is not
intended to be kept up to date — if you're reading this six months from now
and the situation has drifted, trust the code over this doc.

---

## TL;DR

- **v0.1 of `appfirewall-fastapi` is complete and shippable.** Code, tests,
  docs, and tooling are all in place.
- **86 tests pass. mypy strict is clean. ruff is clean. Wheel builds.**
  Fresh-venv smoke install works.
- **The package is NOT yet published to PyPI.** That's a deliberate pause —
  the ingest service it POSTs to doesn't exist yet on the platform side.
- **The brand is `AppFirewall` (at appfirewall.io)**, the company is Sireto,
  the Python package name is `appfirewall-fastapi`. All naming is settled.

---

## What's in the repo right now

```
/home/claude/appfirewall-fastapi/
├── AGENTS.md              ← AI-agent canonical guide (read first)
├── CLAUDE.md              ← Claude Code-specific notes
├── HANDOFF.md             ← this file
├── LICENSE                ← Apache-2.0
├── README.md              ← end-user install/usage
├── ROADMAP.md             ← v0.1 shipped, v0.2+ plans
├── pyproject.toml
├── dist/
│   ├── appfirewall_fastapi-0.1.0-py3-none-any.whl
│   └── appfirewall_fastapi-0.1.0.tar.gz
├── docs/
│   ├── ARCHITECTURE.md    ← module layout + key decisions
│   └── CONTRIBUTING.md    ← PR workflow + standards
├── src/appfirewall_fastapi/
│   ├── __init__.py        ← exports AppFirewallMiddleware, appfirewall, __version__
│   ├── _breaker.py        ← 3-state circuit breaker
│   ├── _buffer.py         ← EventBuffer + background Shipper
│   ├── _cf_ranges.py      ← CF IP ranges + 24h refresh
│   ├── _classifier.py     ← 404 path classifier (scanner/benign/unknown)
│   ├── _client.py         ← coordinator
│   ├── _context.py        ← RequestContext + contextvar
│   ├── _ip.py             ← IP resolution + CF metadata extraction
│   ├── _middleware.py     ← pure ASGI middleware
│   ├── _ratelimit.py      ← sliding-window limiter
│   ├── _shield.py         ← _Shield + appfirewall singleton
│   └── py.typed
└── tests/
    ├── conftest.py
    ├── test_classifier.py (49 tests)
    ├── test_fail_open.py (5 tests)
    ├── test_ip.py (14 tests)
    ├── test_middleware.py (9 tests, end-to-end)
    └── test_ratelimit.py (9 tests)
```

Total source: ~2,600 lines including tests. Well-commented; each module has a
purpose-stating docstring at the top.

---

## What's been decided (so you don't re-debate these)

### Naming
- **Company**: Sireto (existing brand, user owns)
- **Platform/product**: AppFirewall
- **Marketing domain**: `appfirewall.io` (primary); `cloudfirewall.io` → 301 redirects
- **Python package**: `appfirewall-fastapi`
- **Import name**: `appfirewall_fastapi`
- **Middleware class**: `AppFirewallMiddleware`
- **Public singleton**: `appfirewall`
- **Env vars**: `APPFIREWALL_API_KEY`, `APPFIREWALL_ENDPOINT`
- **API key prefix convention**: `afw_live_...` / `afw_test_...`
- **Future packages (names reserved, not yet built)**:
  `appfirewall-express`, `appfirewall-hono`, `appfirewall-django`,
  `appfirewall-rails`

Long naming discussion was had to arrive here. Don't reopen it. If you hate
the name, reopen *after* the product has customers.

### Architecture
- **Pure ASGI middleware**, not `BaseHTTPMiddleware`. Don't change this.
  There's a regression test.
- **Contextvars** for propagating request state to `record()`.
- **Drop-oldest** on buffer overflow. Not drop-newest.
- **Circuit breaker** on the shipper. Not aggressive retries.
- **Fail-open everywhere** in the hot path.
- **Observation by default**, enforcement opt-in. Enforcement properly
  belongs at the CF edge via the (not-yet-built) platform API.

### Tech choices
- Python 3.9+ minimum.
- Runtime deps: `httpx>=0.24`, `starlette>=0.27`. No pydantic, no orjson, no
  heavy deps.
- `mypy --strict`, `ruff` (E/F/W/I/B/UP/ASYNC).
- Hatch build backend.

---

## How to verify the repo is healthy

From the repo root:

```bash
pip install -e ".[dev]"
pytest                   # → 86 passed
mypy src/                # → Success: no issues found in 11 source files
ruff check src/ tests/   # → All checks passed!
python -m build          # → creates dist/*.whl, dist/*.tar.gz
```

Fresh-venv smoke test:

```bash
cd /tmp && python -m venv v && . v/bin/activate
pip install fastapi /path/to/appfirewall-fastapi/dist/*.whl
python -c "from appfirewall_fastapi import AppFirewallMiddleware, appfirewall; print('ok')"
```

All of the above passes on commit `HEAD` as of this handoff.

---

## What was fixed during the audit (for context)

The implementation was largely pre-existing when this audit ran. Issues found
and fixed:

1. **`_ip.py`** — `extract_cf_metadata` was returning `cf-ipcity or cf-asn`
   for the ASN slot. Fixed to return `cf-asn` cleanly. This was a subtle
   bug; the existing test didn't catch it because `cf-ipcity` was absent.
   Noted as a reminder that the test suite isn't exhaustive.

2. **`_cf_ranges.py`** — 5 mypy errors around `httpx` forward references
   and `IPv4Network`/`IPv6Network` union typing. Fixed with proper
   `TYPE_CHECKING` import and explicit constructors.

3. **`tests/test_middleware.py`** — completely rewrote. The original file:
   - Referenced a `client` fixture that didn't exist (it was
     `client_with_app` returning a tuple, which no test matched).
   - Called `client.aclose()` as a "flush" mechanism. This doesn't drive
     ASGI lifespan shutdown, so the shipper never flushed and events were
     missing.
   - Had a test expecting `httpx` to propagate an uncaught exception from
     the inner app, which isn't how Starlette + ASGITransport work by
     default.
   New file uses a `run_in_lifespan` helper that drives startup + requests +
   shutdown properly, plus adds a concurrency test verifying contextvars
   isolate interleaved requests.

4. **Typing modernization** — ran `ruff --fix --unsafe-fixes` to convert
   `List[X]` / `Optional[X]` to `list[X]` / `X | None`. Safe because every
   module has `from __future__ import annotations`. Tests and mypy confirmed
   nothing broke.

---

## What to do next (in priority order)

### 1. Decide whether to publish v0.1 to PyPI

Current state: wheel is built and works, but no one has run
`twine upload dist/*`. Arguments for waiting:

- The ingest endpoint it POSTs to (`ingest.appfirewall.io`) doesn't exist.
  Customers in `mode="ship"` get DNS errors and fall back to
  breaker-OPEN → drop.
- `mode="local"` works fine for evaluators who just want to see what the
  SDK does, but the shipping story is incomplete.

Arguments for publishing anyway:

- Reserves the PyPI name permanently.
- Lets people install it, read the code, play with it in `mode="local"`.
- "Alpha" classifier is already set in `pyproject.toml`.

Recommended: publish. The README is honest about pre-release status. Add a
note to the README that shipping mode requires the platform, which is
coming. Set `Development Status :: 3 - Alpha` (already done).

### 2. Start on the ingest service

The SDK is useless without somewhere to ship to. The ingest service is:

- A Spring Boot 4 / Java 25 service (per your existing stack preferences)
- POST endpoint at `/v1/events` accepting `Content-Type: application/x-ndjson`
  with `Content-Encoding: gzip` and `Authorization: Bearer afw_...`
- Validates the API key, writes events to ClickHouse, returns 202
- Rate-limits per API key at the edge (Cloudflare of course)
- See `docs/ARCHITECTURE.md` for the event shape

This is a separate repo (`appfirewall-ingest`). Not part of this package.

### 3. Optional v0.1.x polish

Not required to ship, nice to have:

- **Benchmark the hot path.** Verify <1ms p99 on a realistic ASGI stack.
  There's a `pytest-benchmark` fixture already configured in dev deps;
  you just need to write the benchmark.
- **GitHub Actions CI.** Tests on Python 3.9, 3.10, 3.11, 3.12, 3.13.
  Run mypy + ruff.
- **`.github/ISSUE_TEMPLATE.md`** and **`.github/PULL_REQUEST_TEMPLATE.md`.**
  There's a PR description skeleton in `docs/CONTRIBUTING.md` that can be
  lifted directly.

### 4. v0.2 planning (don't start coding yet)

See `ROADMAP.md` for the ordered list. The first real new feature is the
distributed rate limiter (Redis-backed). Don't start this until the ingest
service is live — you'll want real load to test against.

---

## Known risks and gotchas

- **`httpx.ASGITransport` does not drive lifespan.** Tests that assert on
  the event log must use `run_in_lifespan`. This trips up every new
  contributor once. Comment in the test file documents it.

- **RubyGems availability check is blocked in the sandbox.** When checking
  if `appfirewall-rails` is available, the network allowlist returns "Host
  not in allowlist" rather than the real API response. Verify RubyGems
  availability manually before publishing a Ruby gem, but don't block on
  it for v0.1 (we're not shipping Ruby yet).

- **The Cloudflare IP range baked snapshot is from 2026-04-22.** If that
  date is more than six months old when you're reading this, either
  refresh the snapshot manually from
  https://api.cloudflare.com/client/v4/ips, or trust that the 24h
  background refresh will pick up the real list on first run. It's
  `_BAKED_V4` / `_BAKED_V6` in `_cf_ranges.py`.

- **`BLE001` (blind-except) is used deliberately.** Our fail-open story
  requires catching bare `Exception`. Don't let anyone "fix" these
  `# noqa: BLE001` by narrowing the except.

- **The `_shield.py` filename is a vestige** of an earlier naming iteration
  ("Sireto Shield"). It's a private module so it doesn't leak. Renaming
  requires touching several imports and a couple of tests — not worth it
  for v0.1, do it in a v0.2 cleanup PR if it bothers you.

---

## People / context

- **Primary developer**: Sireto (backend engineer, based in Roermond NL).
  Prefers concise responses, no preambles. Likes programmatic/code-based
  config over runtime API management (explains the "no REST endpoint to
  configure rate limits" decision). Familiar stack: Spring Boot 4, Java 25
  virtual threads, PostgreSQL, PyTorch, Metaflow, DSPy, Google ML Kit.
- **Adjacent work**: Sireto is also building an ML Kit OCR server (Docker
  packaging is next milestone), an API proxy gateway (v0.6.0), and
  exploring a custom-domain library for multi-product SaaS. These aren't
  related to this repo but context for why this repo's approach is
  enterprise-grade rather than prototypical.

---

## If something goes wrong

- If `pytest` fails on a fresh clone: run
  `pip install -e ".[dev]"` first. If it still fails, the problem is
  environmental (Python version, httpx/starlette version drift) — check
  `pyproject.toml` for the minimums.
- If mypy drifts: check that the `from __future__ import annotations` line
  is still at the top of every module. Modern typing syntax works on 3.9
  *only* because of that.
- If ruff flags something new: check if it's a new rule in a newer ruff
  version. We pin with `>=0.1`, so you might be on a newer version than
  the code was last checked against.
- If the shipper task hangs on shutdown: the `_SHUTDOWN_GRACE_SEC = 5.0`
  in `_buffer.py` bounds this. Extend it if you have a customer with very
  slow ingest, but be aware that extending it also slows down dev-loop
  restarts.

---

Good luck. The code is clean and the tests are honest. Start by reading
AGENTS.md, then browse the source. It's small.
