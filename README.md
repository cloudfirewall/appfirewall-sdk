# appfirewall-sdk

Monorepo for **AppFirewall** SDKs — origin-side abuse-signal middleware that
sits inside customer applications, observes app-layer signals the CDN can't
see, and ships them out-of-band to the AppFirewall ingest service.

Part of the **[AppFirewall][af]** platform by Sireto.

[af]: https://appfirewall.io

## SDKs in this repo

| Status | SDK | Path | Package |
|---|---|---|---|
| ✅ Shipping (alpha) | Python / FastAPI | [`python/appfirewall-fastapi/`](./python/appfirewall-fastapi/) | [`appfirewall-fastapi`](https://pypi.org/p/appfirewall-fastapi) |
| 📐 Spec drafted | Java / Spring Boot | [spec](./docs/specs/appfirewall-spring-boot.md) | `io.appfirewall:appfirewall-spring-boot-starter` |
| 🔮 Planned | Python / Django, Node / Express, Node / Hono, Ruby / Rails | — | — |

The **FastAPI SDK is the reference implementation.** When behaviour is
ambiguous in another SDK, the FastAPI code is the source of truth. Wire
format, classifier patterns, IP-resolution semantics, and event schema must
stay byte-compatible across all SDKs — a single ingest service serves them
all.

## What every SDK does

1. Observes requests at the application layer.
2. Resolves the real client IP, validating forwarded headers against the
   socket peer (never spoofable from outside).
3. Classifies 404s as `scanner` / `benign-miss` / `unknown` using a curated
   pattern set (WordPress probes, dotfile probes, path traversal, etc.).
4. Exposes a synchronous `record(event, fields)` API for app-layer signals
   (auth failures, parse failures, business events).
5. Ships events in gzipped JSONL batches, out-of-band, with a circuit
   breaker.
6. **Fails open.** A bug in the SDK never breaks the customer's app.

## Repository layout

```
appfirewall-sdk/
├── AGENTS.md / CLAUDE.md          ← cross-SDK guide for AI agents
├── ROADMAP.md                     ← shipped / next / later, across all SDKs
├── HANDOFF.md                     ← historical state-of-the-world
├── docs/
│   ├── CONTRIBUTING.md            ← shared PR workflow
│   └── specs/                     ← forward-looking specs for un-built SDKs
├── .github/workflows/
│   └── <lang>-<framework>-publish.yml   ← one release pipeline per SDK
└── python/
    └── appfirewall-fastapi/       ← the FastAPI SDK
```

The directory name of each SDK matches its published artifact name. New
SDKs land under their language directory (`java/`, `node/`, …) following
the same shape as the FastAPI SDK.

## Working on an SDK

`cd` into the SDK directory and follow its own README. Each SDK owns its
toolchain (Python uses hatchling + pytest + mypy + ruff; Java will use
Gradle; etc.).

For cross-SDK PR conventions and the project's Golden Rules see
[`AGENTS.md`](./AGENTS.md) and [`docs/CONTRIBUTING.md`](./docs/CONTRIBUTING.md).

## Releases

Each SDK has its own release line. Tags use a per-SDK prefix so the
monorepo can ship one SDK without affecting another:

| SDK | Tag prefix | Example |
|---|---|---|
| `appfirewall-fastapi` | `python-fastapi-v` | `python-fastapi-v0.2.0` |

The release workflow in `.github/workflows/python-fastapi-publish.yml`
fires on a GitHub Release whose tag matches that prefix, runs the SDK's
test/typecheck/lint, builds, and publishes via PyPI trusted publishing.

## License

Apache-2.0.
