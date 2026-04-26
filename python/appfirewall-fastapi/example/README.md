# Demo FastAPI app

A small FastAPI app wired with `AppFirewallMiddleware`, pointed at a local
ingest. Use it to exercise the ingest + portal end-to-end.

## Setup

From `python/appfirewall-fastapi/` (the SDK directory in this monorepo):

```bash
pip install -e ".[dev]"
pip install uvicorn
```

## Run

```bash
cd example
uvicorn app:app --reload --port 8000
```

Defaults (override via env vars):

- `APPFIREWALL_API_KEY=afp_EBHVo6Y51EbaeXmnlCsMnxwH`
- `APPFIREWALL_ENDPOINT=http://localhost:8080/v1/events`

## Drive traffic

Each block below produces a different signal. Run them in another terminal
while `uvicorn` is running.

### Healthy traffic

```bash
curl -s localhost:8000/
curl -s localhost:8000/items/42
curl -s localhost:8000/checkout
```

### App-layer signals via `appfirewall.record()`

```bash
# auth.login_failed
curl -s -X POST localhost:8000/login \
  -H 'content-type: application/json' \
  -d '{"username":"alice","password":"wrong"}'

# auth.login_success
curl -s -X POST localhost:8000/login \
  -H 'content-type: application/json' \
  -d '{"username":"alice","password":"correct-horse-battery-staple"}'

# upload.parse_failed
echo 'GARBAGE' > /tmp/bad.bin
curl -s -X POST localhost:8000/upload -F "file=@/tmp/bad.bin"

# upload.success
echo 'VALID:hello' > /tmp/good.bin
curl -s -X POST localhost:8000/upload -F "file=@/tmp/good.bin"
```

### 404 classifier — scanner probes

```bash
for path in /wp-admin /wp-login.php /.env /.git/config /phpmyadmin \
            /actuator/env /solr/admin/info/system /admin.php \
            /shell.php /backup.sql /etc/passwd; do
  curl -s -o /dev/null -w "%{http_code} $path\n" "localhost:8000$path"
done
```

### Benign 404s

```bash
curl -s -o /dev/null -w "%{http_code} %{url_effective}\n" localhost:8000/favicon.ico
curl -s -o /dev/null -w "%{http_code} %{url_effective}\n" localhost:8000/robots.txt
curl -s -o /dev/null -w "%{http_code} %{url_effective}\n" localhost:8000/items/0
```

### Simulate different client IPs

The middleware trusts `X-Forwarded-For` from loopback in this demo, so you can
fan out across IPs without needing a real proxy:

```bash
for ip in 203.0.113.7 203.0.113.42 198.51.100.99; do
  for _ in $(seq 1 5); do
    curl -s -o /dev/null -H "X-Forwarded-For: $ip" "localhost:8000/.env"
  done
done
```

### Fail-open smoke test

```bash
curl -s -o /dev/null -w "%{http_code}\n" localhost:8000/boom   # → 500
```

The handler raises, the middleware records a 500 event, the customer-facing
exception still propagates as a 500 (the inner-app exception is never
swallowed).

## What to look for in the portal

- HTTP events for every request, with `status`, `path`, `ip`, `environment=demo`.
- 404s on scanner paths classified as `scanner`; `/favicon.ico` and `/robots.txt`
  as `benign-miss`.
- Custom events: `auth.login_failed`, `auth.login_success`, `upload.parse_failed`,
  `upload.success`, `checkout.declined`, `checkout.completed`.
- Per-IP grouping when you used the XFF fan-out.
- A 500 event from `/boom`.

## Notes

- Events are batched (≤500 events or 2s). Expect a small delay before they
  appear in the portal. Stop `uvicorn` cleanly (Ctrl-C) to flush on shutdown.
- If the ingest is unreachable, the middleware's circuit breaker opens and
  events are dropped silently — that is the v0.1 contract. Restart the demo
  once the ingest is up.
