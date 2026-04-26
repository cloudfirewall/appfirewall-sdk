# `appfirewall-spring-boot` — Development Specification

Status: **draft, pre-implementation.** This document specifies the Spring Boot
sibling of [`appfirewall-fastapi`](../). The actual implementation lives in
its own repository (`appfirewall-spring-boot`); this file exists in the
FastAPI repo only because the FastAPI SDK is the reference implementation —
when behaviour is ambiguous, the FastAPI codebase is the source of truth.

Audience: the Java/Spring engineer (or AI agent) implementing the Spring Boot
SDK. Read [`AGENTS.md`](../AGENTS.md), [`docs/ARCHITECTURE.md`](./ARCHITECTURE.md),
and [`ROADMAP.md`](../ROADMAP.md) in the FastAPI repo first.

---

## 1. Goal

Ship a drop-in Spring Boot starter that gives any Spring Boot 3.x / 4.x
application the same observation behaviour as `appfirewall-fastapi`:

- Resolve the real client IP, validating forwarded headers against the
  socket peer.
- Classify 404s into `scanner` / `benign-miss` / `unknown` using the same
  pattern set as the FastAPI SDK.
- Expose a synchronous, non-blocking `record(event, fields...)` API for
  app-layer signals.
- Batch + gzip events and ship them to the AppFirewall ingest service
  out-of-band, with a circuit breaker.
- Fail open. Customer requests must never fail because of this SDK.

Wire format, classifier patterns, IP-resolution semantics, and event schema
**must be byte-compatible with the FastAPI SDK.** A single ingest service
serves both. If you're tempted to "improve" something, write the issue
against `appfirewall-fastapi` first; only ship the change in both SDKs at
once.

---

## 2. Scope (v0.1)

### In scope
- Spring Boot **3.2+** and **4.0+**, on **Java 17+** (Java 21 preferred for
  virtual threads; not required).
- Servlet stack (Spring MVC) **and** reactive stack (Spring WebFlux). Both
  are first-class. Customers run one or the other; the starter detects
  which is on the classpath and wires the right components.
- A single `appfirewall-spring-boot-starter` module that pulls in everything
  via Spring Boot autoconfiguration.

### Not in scope (deliberately, per `ROADMAP.md`)
- Distributed rate-limiting (Redis backend) — v0.2.
- GeoIP / ASN enrichment beyond `cf-*` headers — v0.2.
- Closed-loop CF enforcement — v0.3+.
- Older Spring Boot 2.x. Boot 2.7 is EOL; we won't carry the WebFlux/MVC
  config-property API split.

---

## 3. Naming and coordinates

| Thing | Value |
|---|---|
| Maven groupId | `io.appfirewall` |
| Maven artifactId | `appfirewall-spring-boot-starter` |
| Java package root | `io.appfirewall.spring` |
| Public filter / handler class (servlet) | `AppFirewallFilter` |
| Public WebFilter class (reactive) | `AppFirewallWebFilter` |
| Public singleton-like API | `AppFirewall.record(event, fields)` (static facade) |
| Configuration prefix | `appfirewall.*` |
| Env var fallback | `APPFIREWALL_API_KEY`, `APPFIREWALL_ENDPOINT` |
| API key prefix | `afw_live_…` / `afw_test_…` (same as FastAPI) |
| User agent | `appfirewall-spring-boot/<version>` |

The static `AppFirewall.record(...)` facade is intentional. Spring users are
used to autowiring, but the FastAPI API is `appfirewall.record(...)` from
anywhere; matching that ergonomically beats Spring purity. Internally,
`AppFirewall` resolves the singleton client through a static holder set by
the autoconfiguration on startup.

---

## 4. Public API

### 4.1 Configuration (`application.yml` / `application.properties`)

```yaml
appfirewall:
  api-key: ${APPFIREWALL_API_KEY:}
  endpoint: ${APPFIREWALL_ENDPOINT:https://ingest.appfirewall.io/v1/events}
  environment: production            # tag attached to every event
  mode: ship                         # ship | local | off
  local-log-path:                    # path; only used when mode=local
  trusted-proxies:                   # list of CIDRs or "cloudflare"
    - cloudflare
  classify-404: true
  rate-limit:
    scanner:
      max: 10
      window-seconds: 60
  enforce-rate-limit: false
  on-error: ignore                   # ignore | warn | raise
```

All keys map to a single `@ConfigurationProperties("appfirewall")` POJO,
`AppFirewallProperties`. Defaults match the FastAPI SDK exactly.

`mode: off` (or unset `api-key` with `mode: ship`) → autoconfiguration
registers a no-op filter. Same fail-soft behaviour as FastAPI.

### 4.2 Programmatic API

```java
import io.appfirewall.spring.AppFirewall;

@PostMapping("/upload")
public ResponseEntity<?> upload(@RequestParam MultipartFile file) {
  try {
    parse(file.getBytes());
  } catch (ParseException e) {
    AppFirewall.record("upload.parse_failed", Map.of("reason", e.getMessage()));
    return ResponseEntity.badRequest().body("invalid format");
  }
  AppFirewall.record("upload.success", Map.of("size", file.getSize()));
  return ResponseEntity.ok().build();
}
```

Signature:

```java
public final class AppFirewall {
  public static void record(String event);
  public static void record(String event, Map<String, ?> fields);
  // Convenience overload to avoid Map.of(...) boilerplate for small calls:
  public static void record(String event, String k1, Object v1);
  public static void record(String event, String k1, Object v1, String k2, Object v2);
  // ... up to 4 pairs
}
```

Contract — **identical to FastAPI's `appfirewall.record()`**:

- Synchronous; never blocks; never awaits.
- Outside a request scope, silently no-ops.
- Never throws. Catches `Throwable` internally; logs at DEBUG.
- Field values must be JSON-serializable. Non-serializable values are
  dropped from that field, not from the whole event; one DEBUG log line.

### 4.3 Health / observability

A single Spring Boot Actuator health indicator under
`management.health.appfirewall`:

```json
{
  "status": "UP",
  "details": {
    "mode": "ship",
    "breaker": "CLOSED",
    "buffer_size": 12,
    "buffer_capacity": 10000,
    "events_emitted": 18432,
    "events_dropped_overflow": 0,
    "events_dropped_breaker": 0,
    "last_ship_status": 202,
    "last_ship_at": "2026-04-26T14:33:11Z"
  }
}
```

Health is `UP` when the breaker is CLOSED or HALF_OPEN, `OUT_OF_SERVICE` when
OPEN. **Never `DOWN`** — our outage must not cause customer health checks
to flip the pod.

Micrometer counters (registered if `MeterRegistry` is on the classpath):

- `appfirewall.events.emitted`
- `appfirewall.events.dropped` (tagged: `reason=overflow|breaker|serialize`)
- `appfirewall.shipper.flush` (tagged: `outcome=success|client_error|server_error|exception`)
- `appfirewall.shipper.batch.size` (DistributionSummary)
- `appfirewall.shipper.flush.duration` (Timer)

---

## 5. Architecture

```
┌─────────────────────────────────────────────────┐
│            Spring Boot autoconfig               │
│        AppFirewallAutoConfiguration             │
│                                                 │
│  - reads AppFirewallProperties                  │
│  - builds the Client (singleton)                │
│  - registers ServletFilter or WebFilter         │
│  - exposes HealthIndicator                      │
└─────────────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────┐
│                   Client                        │
│  (analogue of FastAPI _client.py)               │
│  - owns Buffer, Classifier, RateLimiter,        │
│    IpResolver, CfRangeRegistry, Breaker         │
└─────────────────────────────────────────────────┘
   │           │           │            │
   ▼           ▼           ▼            ▼
┌──────┐  ┌──────────┐ ┌──────────┐  ┌──────────┐
│ Ip   │  │ Classif. │ │ RateLim. │  │  Buffer  │
│ Res. │  └──────────┘ └──────────┘  │ + Shipper│
└──────┘                              │ + Breaker│
                                      └──────────┘
```

### 5.1 Module → file mapping (vs FastAPI)

| FastAPI module | Spring Boot class | Notes |
|---|---|---|
| `_middleware.py` (servlet path) | `AppFirewallFilter` (`OncePerRequestFilter`) | Servlet stack |
| `_middleware.py` (reactive path) | `AppFirewallWebFilter` (`WebFilter`) | Reactive stack |
| `_client.py` | `Client` (package-private) | Coordinator, single instance |
| `_context.py` | `RequestContext` + `RequestContextHolder` | See §6.4 |
| `_classifier.py` | `PathClassifier` | Same patterns; ported as constants |
| `_ratelimit.py` | `SlidingWindowLimiter` | Pluggable interface |
| `_ip.py` | `IpResolver` | Security-critical; mirror tests |
| `_cf_ranges.py` | `CloudflareRangeRegistry` | Baked snapshot + scheduled refresh |
| `_buffer.py` | `EventBuffer` + `Shipper` | One bounded queue + 1 worker thread |
| `_breaker.py` | `CircuitBreaker` | 3-state, identical thresholds |
| `_shield.py` / singleton | `AppFirewall` static facade + holder | See §6.5 |

### 5.2 Threading model

The FastAPI SDK runs everything on the asyncio event loop. Java has no
event loop; we map that to:

- **Hot path (filter):** runs on whatever thread Spring gives us (Tomcat
  worker, Netty event-loop thread, or virtual thread). Must do no blocking
  I/O. Buffer enqueue is `ArrayBlockingQueue.offer(event)`, non-blocking.
- **Shipper:** a single dedicated daemon thread (or virtual thread when
  Java 21+ is detected). Drains the queue, batches, gzips, POSTs via
  `java.net.http.HttpClient` (async API). Equivalent to the FastAPI shipper
  task.
- **CF range refresh:** `ScheduledExecutorService` with a single thread,
  24-hour fixed delay. Same fail-soft semantics as FastAPI.

**Never use `@Async` or the common ForkJoinPool for shipping.** The shipper
must own its lifecycle so we can join/timeout it cleanly on shutdown.

### 5.3 Hot-path budget

Same as FastAPI: **<1ms p99 overhead** measured per request on a realistic
stack. Achievable because the hot path is dict lookups, a regex search, and
a non-blocking `offer`. No JDBC, no JSON serialization (events are built as
`Map<String,Object>` and serialized off-thread by the shipper), no thread
crossings.

A JMH benchmark module is part of v0.1, run in CI on PRs that touch hot-path
code.

---

## 6. Detailed component specs

### 6.1 IP resolution (`IpResolver`)

Mirror of `_ip.py`. Inputs: HTTP headers, socket peer (`HttpServletRequest.
getRemoteAddr()` or reactive equivalent), config (trusted proxies +
Cloudflare flag), `CloudflareRangeRegistry`.

```
resolveClientIp(headers, peer, config, cfRanges) -> InetAddress
  if config.trustsCloudflare && cfRanges.contains(peer):
      cf-connecting-ip header → trusted
  elif peer ∈ config.userCidrs:
      leftmost x-forwarded-for or x-real-ip → trusted
  else:
      peer
```

**Security-critical.** Tests must port the spoofing-protection cases from
`tests/test_ip.py` line by line.

`extract_cf_metadata` follows the same trust gate. `cf-ipcountry`,
`cf-ray`, `cf-asn` are consumed only when peer is in CF ranges.

### 6.2 Classifier (`PathClassifier`)

Exact port of `_classifier.py`'s `_SCANNER_SUBSTRINGS` and benign patterns.
Compile to a single `Pattern` (or a sorted prefix structure if benchmarks
show regex is hot). Classification is by path only — query string is
stripped before classification, same as FastAPI.

`@ConfigurationProperties` does **not** expose extra patterns in v0.1.
Pattern set is curated; users who need extension wait for v0.2.

### 6.3 Event buffer + shipper

- `ArrayBlockingQueue<Map<String,Object>>` of capacity **10,000**.
- `offer()` returns false on full → drop oldest via `poll()`, retry once,
  then drop. Log once at WARNING.
- Shipper drains in a single thread, batching up to **500 events or 2 seconds**,
  whichever comes first. Identical to FastAPI.
- Encoding: NDJSON, gzipped. Headers:
  - `Authorization: Bearer <api-key>`
  - `Content-Type: application/x-ndjson`
  - `Content-Encoding: gzip`
  - `User-Agent: appfirewall-spring-boot/<version>`
- Timeout: 5 seconds per POST.
- Outcomes:
  - 2xx → `breaker.recordSuccess()`.
  - 4xx (not 429) → drop batch, log WARNING with status + count + endpoint,
    `breaker.recordSuccess()` (don't open the breaker on client bugs).
  - 5xx / 429 → log WARNING, `breaker.recordFailure()`.
  - Exception → log WARNING with message, `breaker.recordFailure()`.

The WARNING log on ingest failures is required, not optional. (FastAPI just
upgraded the same paths from DEBUG to WARNING; match it.)

### 6.4 Request context

The contextvars equivalent in Java is **`ThreadLocal`** for servlet,
**Reactor Context** for reactive. Both are abstracted behind:

```java
final class RequestContextHolder {
  static RequestContext current();  // null outside a request
  static AutoCloseable bind(RequestContext ctx);  // try-with-resources
}
```

Two implementations selected at startup based on the stack:

- `ServletRequestContextHolder` — backed by `ThreadLocal<RequestContext>`
  with `InheritableThreadLocal` semantics for `@Async` boundaries. **Cleared
  in a `finally` block at the end of the filter.** Memory leaks here are a
  classic Spring footgun; the test suite must include a soak/leak check.
- `ReactiveRequestContextHolder` — reads `RequestContext` from Reactor
  `Context` via a `ContextView` accessor; the `WebFilter` writes it with
  `chain.filter(exchange).contextWrite(...)`.

`AppFirewall.record(...)` reads through `RequestContextHolder.current()`
and silently no-ops on null. **In a reactive handler, calling
`AppFirewall.record(...)` synchronously inside a `Mono`/`Flux` operator
works only if the operator carries the Reactor `Context`.** We document
this; v0.1 doesn't try to magically thread context across `flatMap`
boundaries.

### 6.5 Singleton holder

`AppFirewall.record(...)` resolves the active `Client` through a private
static reference set by the autoconfiguration's `@PostConstruct`. If
autoconfig didn't run (tests without `@SpringBootTest`, plain JUnit), the
facade silently no-ops. This matches the FastAPI behaviour where calling
`appfirewall.record()` outside a request is a no-op.

Rationale for the static facade despite the Spring orthodoxy preferring
DI: the FastAPI public API is `appfirewall.record(...)` with no
import/wiring; preserving that ergonomic across SDKs is more important than
DI purity. Internal code uses DI normally; only the user-facing facade is
static.

---

## 7. Autoconfiguration

A single `AppFirewallAutoConfiguration` class, registered via
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

```
@AutoConfiguration
@ConditionalOnProperty(prefix = "appfirewall", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AppFirewallProperties.class)
public class AppFirewallAutoConfiguration {

  @Bean Client appFirewallClient(AppFirewallProperties props) { ... }

  @Bean
  @ConditionalOnWebApplication(type = SERVLET)
  AppFirewallFilter appFirewallFilter(Client c) { ... }

  @Bean
  @ConditionalOnWebApplication(type = REACTIVE)
  AppFirewallWebFilter appFirewallWebFilter(Client c) { ... }

  @Bean
  @ConditionalOnClass(HealthIndicator.class)
  AppFirewallHealthIndicator appFirewallHealth(Client c) { ... }

  @Bean
  @ConditionalOnClass(MeterRegistry.class)
  AppFirewallMetrics appFirewallMetrics(Client c, MeterRegistry r) { ... }
}
```

Filter registration:

- Servlet: `FilterRegistrationBean<AppFirewallFilter>` with order
  `Ordered.HIGHEST_PRECEDENCE + 100` — runs before app filters but after
  Spring Security's logging filter. Document this so users with custom
  ordering know where we sit.
- Reactive: `WebFilter` with `@Order(Ordered.HIGHEST_PRECEDENCE + 100)`.

---

## 8. Failure modes — fail-open table

Every row mirrors the FastAPI SDK. **Customer request must always serve.**

| Failure | Behaviour |
|---|---|
| `Client` constructor throws | Autoconfig logs ERROR, registers no-op filter. App starts. |
| `IpResolver` throws | Catch, log DEBUG, fall back to socket peer. |
| `PathClassifier` throws | Catch, log DEBUG, skip classification, still emit event. |
| `EventBuffer.emit` throws | Catch, log DEBUG. Never propagates. |
| Queue full | Drop oldest, log WARNING (once). |
| Shipper task dies | `UncaughtExceptionHandler` logs WARNING, restarts the thread. |
| Ingest 5xx / timeout | Breaker counts failure, log WARNING with endpoint + status. |
| Ingest 4xx (not 429) | Drop batch, log WARNING. Don't open breaker. |
| `AppFirewall.record` outside request | Silent no-op. |
| `AppFirewall.record` with non-serializable field | Drop that field, log DEBUG. |
| CF range refresh fails | Keep baked snapshot, log INFO once per outage window. |
| Filter throws an unexpected `RuntimeException` | Catch, log ERROR, call `chain.doFilter` so request still serves. |

**The only exception that propagates is one raised by the customer's
handler.** Same rule as FastAPI.

---

## 9. Testing requirements

- **Mirror the FastAPI test suite where applicable.** Especially:
  - `test_ip.py` → `IpResolverTest` (every spoofing case).
  - `test_classifier.py` → `PathClassifierTest` (every parametrized case).
  - `test_fail_open.py` → `FailOpenTest` (every failure injection).
  - `test_ratelimit.py` → `SlidingWindowLimiterTest`.
- **End-to-end:** `@SpringBootTest(webEnvironment = RANDOM_PORT)` for both
  servlet and reactive stacks. Verify events are flushed on graceful
  shutdown (Spring's `SmartLifecycle` + 5-second grace, mirroring
  `_SHUTDOWN_GRACE_SEC`).
- **Concurrency:** verify per-request `RequestContext` isolation across
  interleaved requests on a Tomcat thread pool *and* on Reactor schedulers.
- **Wire compatibility:** generate a batch of events, decode the gzipped
  body, assert it parses as the same NDJSON schema the FastAPI SDK emits.
  This test doubles as the protocol regression check.
- **No mocks of own code.** If a class is hard to test without mocking,
  redesign it.
- **JMH benchmark** for the filter hot path. Fail PR if p99 regresses
  >20% vs main.

CI matrix: Java 17 and Java 21, Spring Boot 3.2 / 3.3 / 4.0.

---

## 10. Build and packaging

- Gradle 8.x with Kotlin DSL. (Justification: matches Sireto's existing
  Spring Boot stack preferences.)
- Single published artifact: `appfirewall-spring-boot-starter`.
- Multi-module build internally:
  - `core/` — pure-Java, no Spring deps. The `Client`, `IpResolver`,
    `PathClassifier`, `EventBuffer`, etc. Reusable from a hypothetical
    plain-Java or Micronaut SDK later.
  - `starter/` — Spring Boot autoconfiguration + filters; depends on `core`.
  - `benchmark/` — JMH; not published.
- Publish to Maven Central via Sonatype OSSRH. Trusted publishing via
  a GitHub Actions reusable workflow analogous to the FastAPI
  `publish.yml`. Version sourced from git tags via the `nebula-release`
  or `axion-release` plugin (analogue to `hatch-vcs`).
- License: Apache-2.0, same as FastAPI.

---

## 11. Documentation deliverables

The Spring Boot repository ships at minimum:

- `README.md` — install, quick start, configuration table. Mirror the
  FastAPI README's structure exactly so users coming from one to the other
  feel at home.
- `AGENTS.md` — same Golden Rules, Spring-specific module layout.
- `docs/ARCHITECTURE.md` — Spring-specific version of the FastAPI
  architecture doc, with the threading-model section expanded.
- `docs/MIGRATING_FROM_FASTAPI.md` — short doc for engineers who already
  use the FastAPI SDK and are setting up the Spring app.

---

## 12. Open questions (resolve before starting v0.1)

1. **Reactor `Context` propagation.** Does v0.1 require `record()` to work
   from within `flatMap` operators, or do we punt to v0.2 and document
   that handlers should record before/after the reactive chain? Lean: punt.
2. **Servlet vs Jakarta.** Spring Boot 3+ is Jakarta-only. We don't support
   2.x, so this is moot — confirm by writing only against `jakarta.*`.
3. **Virtual threads.** Detect Java 21+ and use a virtual-thread shipper, or
   just use a platform daemon thread for predictability? Lean:
   platform thread for v0.1 — one fewer variable when debugging.
4. **Spring Security ordering.** Should we run before or after Spring
   Security's filter chain? Before means we observe blocked requests too;
   after means we don't double-count CSRF rejections. Lean: before, so
   scanner probes get classified even when Security 401s them.

These should be decided in the new repo before v0.1 ships, not deferred
indefinitely.

---

## 13. Out-of-scope cross-references

This SDK does not specify the ingest service. The wire format is documented
in [`docs/ARCHITECTURE.md`](./ARCHITECTURE.md) and (eventually) in the
ingest service repo. If the wire format here disagrees with the FastAPI
SDK at any point, **the FastAPI SDK is correct** until the spec is updated
in lockstep.
