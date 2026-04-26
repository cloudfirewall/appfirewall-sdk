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
| `SlidingWindowLimiter` | ⏳ | stub; port from `_ratelimit.py` |
| `EventBuffer` / `Shipper` | ⏳ | drop-oldest; gzip NDJSON; circuit breaker |
| `AppFirewallFilter` (servlet) | ⏳ | `OncePerRequestFilter`; ThreadLocal context |
| `AppFirewallWebFilter` (reactive) | ⏳ | Reactor `Context` propagation |
| `AppFirewallAutoConfiguration` | ⏳ | bean wiring + filter ordering |
| Health + Metrics | ⏳ | Actuator + Micrometer |

The pure-logic ports were done first because they have full test coverage
in Python and zero framework coupling. The next priorities, in order:

1. `EventBuffer.Shipper` &mdash; finish the shipper loop with
   `java.net.http.HttpClient`, gzip NDJSON, and the WARNING-level failure
   logging contract from FastAPI's `_buffer.py`.
2. Servlet `AppFirewallFilter` + autoconfig wiring &mdash; first end-to-end run.
3. `RequestContextHolder` strategies (servlet `ThreadLocal`, reactive
   Reactor-Context).
4. `SlidingWindowLimiter` &mdash; port from `_ratelimit.py`.
5. Reactive `AppFirewallWebFilter`.
6. Health + metrics.
7. `CloudflareRangeRegistry` 24-hour background refresh.

## License

Apache-2.0.
