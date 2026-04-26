# appfirewall-spring-boot

Origin-side abuse-signal middleware for Spring Boot apps behind Cloudflare.
Sibling of [`appfirewall-fastapi`](../../python/appfirewall-fastapi/) in the
[`appfirewall-sdk`](../../) monorepo.

> **Status:** v0.1 in progress &mdash; scaffolding + pure-logic ports
> (`PathClassifier`, `CircuitBreaker`) landed; filters, IP resolver,
> buffer/shipper, autoconfig wiring still TODO.

## Reading order

1. [docs/specs/appfirewall-spring-boot.md](../../docs/specs/appfirewall-spring-boot.md)
   &mdash; the full design contract for this SDK.
2. [`python/appfirewall-fastapi/`](../../python/appfirewall-fastapi/) &mdash;
   the reference implementation. When behaviour here is ambiguous, the
   Python SDK is the source of truth.
3. [`AGENTS.md`](../../AGENTS.md) at the repo root &mdash; cross-SDK
   conventions and the Golden Rules (fail open, never block the request
   path, etc.).

## Modules

```
java/appfirewall-spring-boot/
├── settings.gradle.kts
├── build.gradle.kts            ← root: toolchain, lint, test setup
├── core/                       ← pure Java, no Spring
│   └── src/main/java/io/appfirewall/core/
│       ├── classifier/         ← PathClassifier, Classification (✅ ported)
│       ├── breaker/            ← CircuitBreaker (✅ ported)
│       ├── ratelimit/          ← SlidingWindowLimiter (stub)
│       ├── ip/                 ← IpResolver, CloudflareRangeRegistry (stubs)
│       ├── buffer/             ← EventBuffer, Shipper (stubs)
│       ├── context/            ← RequestContext (✅ ported)
│       ├── config/             ← ClientConfig record, Mode enum (✅ done)
│       └── Client.java         ← coordinator (stub)
└── starter/                    ← Spring Boot autoconfig
    └── src/main/java/io/appfirewall/spring/
        ├── AppFirewall.java                 ← static facade (skeleton)
        ├── AppFirewallProperties.java       ← @ConfigurationProperties POJO
        ├── AppFirewallAutoConfiguration.java
        ├── servlet/AppFirewallFilter.java
        ├── reactive/AppFirewallWebFilter.java
        ├── context/RequestContextHolder.java
        └── health/AppFirewallHealthIndicator.java
```

## Build

Java 17 toolchain via Gradle:

```bash
cd java/appfirewall-spring-boot
./gradlew test
./gradlew :core:test     # just the pure-logic tests
./gradlew build
```

(Gradle wrapper is not committed yet &mdash; run `gradle wrapper --gradle-version 8.10`
once locally to generate it, or use a system-installed `gradle 8.x`.)

## What's ported, what's next

| Component | Status | Notes |
|---|---|---|
| `Classification` enum | ✅ | wire-compatible string values |
| `PathClassifier` | ✅ | full port + parametrized tests mirroring Python |
| `CircuitBreaker` | ✅ | full port + tests with injectable `Clock` |
| `RequestContext` | ✅ | value class; mutable `status` + customFields |
| `ClientConfig` / `Mode` | ✅ | record + enum |
| `IpResolver` | ✅ | full port + every case from `test_ip.py` |
| `CloudflareRangeRegistry` | ✅ | baked snapshot; 24 h refresh still TODO |
| `SlidingWindowLimiter` | ✅ | full port + tests with injectable time source |
| `EventBuffer` | ✅ | drop-oldest, bounded, non-blocking emit |
| `Shipper` | ✅ | dedicated thread, gzip NDJSON, breaker, WARN-on-fail |
| `JsonEncoder` (internal) | ✅ | hand-rolled; no JSON-lib runtime dep |
| `Client` coordinator | ✅ | wires subsystems + builds event maps |
| `AppFirewallFilter` (servlet) | ⏳ | `OncePerRequestFilter`; ThreadLocal context |
| `AppFirewallWebFilter` (reactive) | ⏳ | Reactor `Context` propagation |
| `AppFirewallAutoConfiguration` | ⏳ | bean wiring + filter ordering |
| Health + Metrics | ⏳ | Actuator + Micrometer |
| CF ranges 24 h refresh | ⏳ | `ScheduledExecutorService` + HttpClient |

All `core/` logic is in place; everything left is the Spring Boot
integration layer or a single non-blocking enhancement. Next priorities:

1. `RequestContextHolder` strategies (servlet `ThreadLocal`, reactive
   Reactor-Context) &mdash; needed by the filters and the static
   `AppFirewall.record(...)` facade.
2. Servlet `AppFirewallFilter` &mdash; `OncePerRequestFilter` wiring
   `Client` end-to-end through a request.
3. `AppFirewallAutoConfiguration` &mdash; build the `Client`, expose
   `RateLimiter` per class, register the filter, set
   `AppFirewall.setClient(...)`.
4. First `@SpringBootTest` driving traffic against a real `Client` in
   `Mode.LOCAL` and asserting the NDJSON output.
5. Reactive `AppFirewallWebFilter`.
6. Health + metrics.
7. `CloudflareRangeRegistry` 24-hour background refresh
   (`ScheduledExecutorService` + `HttpClient`; fail-soft).

## License

Apache-2.0.
