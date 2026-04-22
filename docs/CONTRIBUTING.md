# Contributing

This doc covers the "how" of making a change: workflow, standards, release
process. For the "what" and "why" of this project, read
[`../AGENTS.md`](../AGENTS.md) and [`ARCHITECTURE.md`](./ARCHITECTURE.md).

---

## Setup

```bash
git clone https://github.com/sireto/appfirewall-fastapi.git
cd appfirewall-fastapi
pip install -e ".[dev]"
```

Verify the clone is healthy:

```bash
pytest && mypy src/ && ruff check src/ tests/
```

All three should pass. If they don't, open an issue before starting work —
something is wrong with main, not with you.

---

## Workflow

### 1. Open an issue first (for non-trivial changes)

If you're about to add a feature, change behavior, or bump a dependency,
open an issue describing the change before writing code. The project has
opinions (fail-open, observation-first, no required runtime deps) that can
make well-intentioned PRs hard to merge if they're built on different
assumptions.

Exceptions where you can skip the issue: typo fixes, comment improvements,
test additions that don't change behavior, dependency-update PRs from
dependabot-style tools.

### 2. Branch from `main`

```bash
git checkout -b your-change-name
```

Branch naming isn't strictly enforced but prefer `topic/short-description`
or `fix/specific-issue`. Avoid personal-name prefixes.

### 3. Make the change

- One logical change per PR. Don't bundle a refactor with a feature.
- Update the relevant docs as part of the same PR — `README.md` for
  public-API changes, `docs/ARCHITECTURE.md` for structural changes,
  `ROADMAP.md` if you're moving an item between columns.
- Tests before code, especially for anything in the hot path.

### 4. Run the full check before pushing

```bash
pytest && mypy src/ && ruff check src/ tests/
```

If `ruff check` flags something that isn't a real issue, explain the noqa
in a comment. Don't add rule-wide ignores to `pyproject.toml` to make a
single lint issue go away.

### 5. Open a PR

PR description template:

```markdown
## What

One-sentence summary of the change.

## Why

Link the issue. If no issue, explain the motivation.

## How

Only the non-obvious parts. Skip play-by-play.

## Risk

What could this break? How did you check it didn't?
```

### 6. Respond to review

If a reviewer asks a question, answer it in the PR before resolving the
thread. Don't just push a fix — the reasoning is what gets archived.

---

## Coding standards

### Types

- `mypy --strict` must pass. New code without type hints won't be merged.
- Use `from __future__ import annotations` at the top of every module.
- Prefer `list[X]` over `List[X]`, `X | None` over `Optional[X]`. Works on
  3.9+ because of the `__future__` import.
- Generic `dict[str, Any]` is acceptable for event payloads that cross
  JSON serialization. Specific types are better when practical.

### Tests

- One test file per source module: `test_foo.py` for `_foo.py`.
- Group tests in classes by concern (`TestCloudflareTrust`,
  `TestGenericProxy`, `TestEdgeCases`).
- Parametrize when you can. `@pytest.mark.parametrize("path", [...])`
  beats 15 near-identical test methods.
- End-to-end tests go in `test_middleware.py` and must use `run_in_lifespan`.
- Fail-open assertions go in `test_fail_open.py`. When you add a new failure
  mode, add the assertion.

### Comments

- Module docstrings explain the module's purpose in 3-10 lines. They're the
  first thing a new reader sees.
- Class docstrings explain what the class is responsible for.
- Method docstrings: one-line summary for most methods, fuller docs for
  anything non-obvious or security-relevant.
- Inline comments explain **why**, not what. If the code isn't
  self-explanatory, simplify the code first.

### Error handling

Hot path (request, emit, record):

```python
try:
    do_thing()
except Exception:  # noqa: BLE001
    _LOG.debug("appfirewall: thing failed: %s", exc)
```

Background tasks (shipper, refresh loop):

```python
while True:
    try:
        await work()
    except Exception:  # noqa: BLE001
        _LOG.debug("...")
    await asyncio.sleep(interval)
```

Validation / init code:

```python
if max_events <= 0:
    raise ValueError("max_events must be positive")
```

### Dependencies

- Runtime deps: keep them at 2 (`httpx`, `starlette`). Add a third only with
  a PR that justifies the trade-off and gets explicit sign-off.
- Dev deps: reasonable additions (new pytest plugins, type stubs) are
  fine.
- Optional extras for v0.2+ features are the right way to add heavier deps.
  Example: `redis` will come in via `pip install appfirewall-fastapi[redis]`.

---

## Release process

Only maintainers with PyPI access do releases. Not covered by PR.

1. **Verify main is green.** All CI checks pass.
2. **Update version** in `src/appfirewall_fastapi/__init__.py` and
   `pyproject.toml`. Single source of truth: `__init__.py`. Match them.
3. **Update `ROADMAP.md`** — move shipped items from "next" to "shipped" and
   add a dated entry.
4. **Tag the release:** `git tag v0.x.y && git push --tags`
5. **Build:** `python -m build`
6. **Inspect the wheel:** `unzip -l dist/*.whl` — confirm no accidentally-included
   files (no `.env`, no test fixtures, no `__pycache__`).
7. **Upload to PyPI:** `twine upload dist/*`
8. **Create a GitHub release** from the tag, paste the ROADMAP entry as the
   release notes.

### Version bumping

- **Patch** (`0.1.0 → 0.1.1`): bug fixes, doc changes, internal refactors
  that don't change behavior.
- **Minor** (`0.1.0 → 0.2.0`): new features, new config options,
  new-but-backward-compatible behavior.
- **Major** (`0.x → 1.0`): breaking changes to the public API. The
  `AppFirewallMiddleware` constructor signature, `appfirewall.record()`
  signature, or env variable names.

v0.x is pre-1.0; minor bumps *may* contain internal-API breaks as long as
the public API is stable. Document any such breaks in the release notes.

---

## What gets rejected

Some categories of PR will be closed without deep review. Not because
they're wrong, but because they fight the project's design.

- PRs that add `pydantic` or any heavy runtime dep as a required dependency.
- PRs that make the middleware block on I/O in the request path.
- PRs that remove fail-open handling in the name of "letting errors bubble
  up." Errors bubbling up is the anti-goal.
- PRs that add a new config option without thinking about the default. If
  the default isn't safe-for-everyone-behind-Cloudflare, the PR is wrong.
- PRs that refactor "for clarity" without changing behavior. Do that as part
  of a feature change, not on its own.
- PRs that add a new third-party framework (e.g. Django support) in this
  repo. Those go in their own package (`appfirewall-django`).

---

## Getting help

- Check [`../AGENTS.md`](../AGENTS.md) for conventions.
- Check [`ARCHITECTURE.md`](./ARCHITECTURE.md) for "why is it this way."
- Check [`../ROADMAP.md`](../ROADMAP.md) to see if it's a deferred item.
- Open a discussion or an issue.
