# ROADMAP

Last updated: 2026-04-23

This doc lives at the repo root because agents and humans both need to know
"is X my problem to solve right now, or is it a known future-item?"

---

## Shipped in v0.1

The first public release. Observation-first, single-framework (FastAPI).

- ✅ **ASGI middleware** (`AppFirewallMiddleware`). Pure ASGI, not
  `BaseHTTPMiddleware` — streaming responses pass through unbuffered.
- ✅ **Client IP resolution** with transport-peer validation. `cf-connecting-ip`
  is only trusted when the peer is in Cloudflare's published IP ranges.
  Generic `x-forwarded-for` / `x-real-ip` works with user-supplied proxy
  CIDRs.
- ✅ **Cloudflare IP range registry** with baked-in snapshot + background
  refresh every 24 hours from `api.cloudflare.com/client/v4/ips`. Fail-soft if
  the refresh fails.
- ✅ **404 classifier** — `scanner` / `benign-miss` / `unknown` based on a
  compiled pattern set covering WordPress probes, dotfile probes, path
  traversal, admin probes, framework-specific probes (Spring actuator, phpMyAdmin,
  Jenkins, Solr), and shell/backdoor patterns.
- ✅ **Sliding-window rate limiter** (scanner class only, in-process).
- ✅ **Event buffer + shipper** — asyncio queue, drop-oldest on overflow, gzip'd
  JSONL batches of up to 500 events or 2 seconds, with a 3-state circuit
  breaker guarding against ingest outages.
- ✅ **`appfirewall.record()`** — contextvars-based, works from sync/async
  handlers, silent no-op outside a request.
- ✅ **Three modes**: `ship` (prod), `local` (write JSONL to disk for
  dev/evaluation), `off` (everything disabled).
- ✅ **Lazy startup + graceful lifespan shutdown** with 5-second flush grace.
- ✅ **Fail-open** at every layer — middleware, classifier, limiter, buffer,
  shipper. Customer app never crashes because of our bugs.
- ✅ **Type-complete** (`py.typed` shipped, `mypy --strict` clean).

### v0.1 test coverage

86 tests across 5 files: classifier patterns, IP resolution (including
spoofing-protection tests), rate limiter sliding-window correctness,
end-to-end middleware via real ASGI lifespan, and failure-injection tests
proving fail-open.

---

## Not in v0.1 (deliberately)

These are real requirements, but we drew a tight line around v0.1 to ship.

### Distributed coordination
- Rate limiter is per-process. A scanner hitting four uvicorn workers gets
  four independent counters. The observation pipeline still captures every
  hit; only in-process enforcement is fragmented.
- No shared buffer across workers. Each worker has its own queue.

### Ingest-side features
- No ingest service exists yet. The SDK POSTs to an endpoint that isn't built
  on Sireto's side. Customers using v0.1 in `mode="ship"` will need to either
  run their own compatible ingest (documented format) or wait for the
  platform service.
- No dashboard, no per-IP drill-down, no alerting.

### Policy / closed loop
- No CF API integration. The "close the loop back to the edge by creating IP
  Access Rules" is the whole platform story, but the SDK's job in v0.1 is
  purely to produce signals. Blocking happens elsewhere.
- No policy engine.

### Other frameworks
- `appfirewall-express`, `appfirewall-hono`, `appfirewall-django`,
  `appfirewall-rails` are names we've reserved but not built.

---

## v0.2 — next up

Not committed to dates. Order is roughly ascending risk/effort.

### 1. Distributed rate limiter (Redis-backed)
Swap the in-process `SlidingWindowLimiter` for a pluggable backend. The
Redis backend uses a sorted-set-per-IP with `ZADD` + `ZREMRANGEBYSCORE` for
the sliding window, script-atomic. Keep the in-process limiter as the
default so users without Redis aren't broken.

**Dependencies**: adds `redis[asyncio]` as an optional extra
(`pip install appfirewall-fastapi[redis]`).

### 2. GeoIP / ASN enrichment
Enrich events with country and ASN using `cf-ipcountry` / `cf-asn` (already
extracted, not yet used for classification). Plus MaxMind GeoLite2 fallback
for non-CF peers if the user provides a license key.

### 3. Per-IP upload/auth-failure quotas
Extend the classifier beyond 404s to include structured events from
`record()`. An IP that triggers `upload.parse_failed` 50 times in a minute
should be flaggable without each call site having to count.

### 4. Stripped-down ingest-service contract
Publish the wire format (JSONL schema, auth header, gzip encoding) so
customers can run their own ingest. This lowers the "I don't trust your SaaS
with my data" objection and is a plausible OSS-first growth path.

### 5. `appfirewall-django` and `appfirewall-express`
Django's middleware protocol is different enough (WSGI/ASGI dual, sync by
default) that it's a fresh implementation, not a port. Express is a
different language. Both reuse the event schema + ingest contract from
FastAPI.

---

## v0.3+ — later

### Closed-loop enforcement
The product thesis: SDK observes, platform decides, Cloudflare enforces.
v0.3 wires the "platform decides → Cloudflare enforces" half. Requires:
- A decision API (policy engine on the ingest service).
- CF API client in the platform backend (creates IP Access Rules, WAF Custom
  Rules, adds to CF Lists).
- Customer onboarding flow that captures a CF API token scoped to the right
  zone(s).

### CAPTCHA-on-upload
Decision-driven: based on server-side signals (failed parse rate from this IP,
ASN reputation), respond with a 429 that triggers the customer's frontend to
present a CAPTCHA before retry.

### Self-hosted ingest
A ready-to-run reference implementation of the ingest service (Go or
Spring Boot + ClickHouse + Redpanda/Redis), so large customers can keep
everything in their own perimeter.

### Framework-specific integrations
Beyond middleware:
- FastAPI dependency (`Depends(appfirewall.context)`) for handler-level ergonomics
- pydantic-validation-failure hook (auto-record on schema rejection)
- OAuth/JWT library hooks for auth-failure events

---

## Deferred indefinitely

- Machine-learning-based classification of 404 paths. The hand-rolled pattern
  list captures the vast majority of real scanner traffic and is debuggable.
  ML is only worth it when the pattern list stops working.
- GraphQL-specific middleware. FastAPI users doing GraphQL have Strawberry or
  Ariadne; we'd re-integrate at a per-library level, which is too thin a
  market to justify v0.x effort.
- Windows-specific code paths. The SDK runs on whatever the ASGI server runs
  on; we don't touch OS primitives directly.

---

## How this roadmap is maintained

- **Update on release.** When a version ships, move its items from "next" to
  "shipped" and add a dated entry.
- **Update when scope changes.** If a v0.2 item gets deferred or a v0.3 item
  gets pulled forward, update here so agents stop confusing it for a
  can-start-now task.
- **Don't list every ticket.** This is product direction. Day-to-day work
  lives in issues, not here.
