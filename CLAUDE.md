# CLAUDE.md

Claude Code-specific notes. **Start by reading [`AGENTS.md`](./AGENTS.md)** —
that's the canonical guide for all AI agents working on this repo. This file
covers only things that are Claude Code-specific.

---

## Reading order for a fresh session

1. [`AGENTS.md`](./AGENTS.md) — cross-SDK conventions, Golden Rules, repo layout.
2. [`HANDOFF.md`](./HANDOFF.md) — historical state-of-the-world (one-time doc).
3. [`ROADMAP.md`](./ROADMAP.md) — what's shipped vs. next.
4. The SDK directory you're working on (e.g. `python/appfirewall-fastapi/`):
   its `README.md` first, then `docs/ARCHITECTURE.md`.
5. The specific module(s) you're touching.

Don't try to read the whole codebase before starting. The modules are small
(~100-300 lines each) and self-contained. Read what you need.

---

## Working in this repo with Claude Code

This is a multi-SDK monorepo. Most tasks live entirely inside one SDK
directory — `cd` into it before running its toolchain.

### Commands at the top of a FastAPI-SDK session

```bash
cd python/appfirewall-fastapi
pip install -e ".[dev]"
pytest && mypy src/ && ruff check src/ tests/
```

The full check should pass on a fresh clone. If it doesn't, fix that before
starting on the actual task.

### Before marking a task done

Run the full check again from inside the SDK directory:

```bash
pytest && mypy src/ && ruff check src/ tests/
```

**All three must pass.** No "this test was already failing" — if a test was
failing before your change, either fix it as part of your work or explicitly
note it in your handoff.

### Commit messages

Prefer present-tense imperative, short subject, optional body:

```
add X-Forwarded-Host validation to trusted-proxy check

The v0.1 check only validated the peer IP, missing the case where a
trusted upstream sets Host. This adds Host validation when the peer is
in trusted_proxies.
```

Skip the body for trivial changes. Skip Co-Authored-By lines — the user
doesn't want attribution trails.

---

## Claude-specific guardrails

### Skills

If the user asks for a deliverable that matches a skill (docx, pptx, xlsx,
pdf, frontend-design), **read the SKILL.md first** before starting. The
skills encode environment-specific constraints (available libraries,
rendering quirks, output paths) that training data doesn't cover.

For this repo specifically, most tasks are Python coding — no skill needed.
Exceptions: if asked to generate a product one-pager, architecture diagram
as a PDF, or customer-facing deck, use the appropriate skill.

### When to use the Visualizer

For this project, the Visualizer is appropriate when:

- Drawing the event flow (request → middleware → buffer → shipper → ingest)
- Showing the trust model (peer validation gates)
- Rendering a classifier decision tree

Not appropriate when:

- The user wants code changes — just make them
- The user wants a quick status update — use prose
- The user wants to see a file — use `present_files`

### Fail-open instinct

This project's architecture is built around fail-open. When you're tempted to
write a `raise` inside the hot path, stop. The right answer is almost always
`except Exception: log.debug(...); pass`. See Golden Rule #1 in AGENTS.md.

If you find yourself thinking "but the user should know this failed," the
answer is a DEBUG log, never a raise. Customer-facing observability of our
own failures is a v0.2+ feature.

### Incremental work

The user prefers feature-by-feature increments with tests, migrations, and
docs at each step (not a big-bang PR). When a task is larger than one
module, break it into chunks and confirm the approach before writing all
the code.

---

## Project conventions worth re-emphasizing

(These are in AGENTS.md but Claude sometimes drifts, so pinning them here.)

- **`from __future__ import annotations`** at the top of every module. Always.
- **No bullet-point essays.** The user prefers concise prose, with lists only
  when the content is genuinely enumerable. Check your output before sending.
- **No lengthy preambles.** If the user asks a direct question, answer
  directly. Save context-setting for when it actually helps.
- **Don't announce what you're about to do, then do it.** Just do it.
- **Don't apologize when you make a mistake.** Acknowledge, fix, move on. No
  self-abasement.

---

## If a tool call fails

The sandbox network allowlist blocks some domains (e.g., RubyGems API). When
a curl or web_fetch fails with "Host not in allowlist," that's an
infrastructure limit, not a bug in the target. Report the limit to the user
and move on rather than retrying.

---

## MCP and external integrations

This repo has no MCP server integrations. The project doesn't need them —
everything is local filesystem + PyPI. If a future task involves reading
GitHub issues, search for an `mcp` tool before assuming it's unavailable,
but don't connect one speculatively.
