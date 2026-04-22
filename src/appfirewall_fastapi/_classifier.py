"""404 path classifier.

When a request falls through the app's routes and ASGI returns 404, we want to
separate three kinds of misses:

  - ``scanner``:    known attack/probe patterns (/wp-admin, /.env, /.git/config,
                    path-traversal, etc.). These feed the rate limiter.
  - ``benign-miss``: expected-but-unhandled paths from normal clients
                    (/favicon.ico, /robots.txt, apple-touch-icon variants).
                    Recorded for visibility but never rate-limited.
  - ``unknown``:    everything else. Recorded, not rate-limited.

The classification is a pure function of the request path. It must be fast —
this runs on every request that 404s, which for many public services is a big
fraction of all traffic once scanners find you.

Pattern sources:
  - PHP/WordPress probes:    /wp-*, /xmlrpc.php, /wp-login.php
  - Dotfile probes:          /.env, /.git/*, /.ssh/*, /.aws/*
  - Framework admin probes:  /phpmyadmin, /phpinfo.php, /server-status
  - Spring/Java probes:      /actuator, /actuator/*
  - Shell/backdoor probes:   /shell.php, /cmd.php, /c99.php, /backdoor.php
  - Path traversal:          ../, %2e%2e, ..%2f
  - OS files:                /etc/passwd, /boot.ini
  - Vendor-library probes:   /vendor/phpunit/*
"""

from __future__ import annotations

import re
from typing import Literal

Classification = Literal["scanner", "benign-miss", "unknown"]


# --- Benign patterns ---------------------------------------------------------
# These are paths that real browsers and apps will ask for even when they don't
# exist on the server. We record them for visibility but don't rate-limit.
_BENIGN_EXACT = frozenset({
    "/favicon.ico",
    "/robots.txt",
    "/sitemap.xml",
    "/humans.txt",
    "/ads.txt",
    "/manifest.json",
    "/manifest.webmanifest",
    "/browserconfig.xml",
    "/.well-known/security.txt",
    "/.well-known/change-password",
    "/.well-known/apple-app-site-association",
    "/apple-app-site-association",
})

_BENIGN_PREFIX: tuple[str, ...] = (
    "/apple-touch-icon",   # apple-touch-icon.png, -precomposed, -120x120 etc.
    "/.well-known/",       # Catches ACME, security.txt, etc. not covered above
)


# --- Scanner patterns --------------------------------------------------------
# These are ordered roughly by how common they are in the wild, so the
# compiled regex's alternation can short-circuit sooner on typical traffic.
# Kept as plain strings so they're easy to audit.

_SCANNER_SUBSTRINGS: tuple[str, ...] = (
    # Path traversal. Matches literal "../" anywhere in the path, plus URL-
    # encoded variants.
    "../",
    "..%2f",
    "..%5c",
    "%2e%2e/",
    "%2e%2e%2f",
    # Sensitive OS/VCS files.
    "/etc/passwd",
    "/etc/shadow",
    "/boot.ini",
    "/win.ini",
    "/.git/",
    "/.svn/",
    "/.hg/",
    "/.bzr/",
    "/.ssh/",
    "/.aws/",
    "/.docker/",
    "/.kube/",
    # Config and secret files.
    "/.env",
    "/.dockerenv",
    "/web.config",
    "/config.php",
    "/database.yml",
    "/settings.py",
    # Admin / CMS probes.
    "/wp-admin",
    "/wp-login",
    "/wp-content",
    "/wp-includes",
    "/wp-config",
    "/xmlrpc.php",
    "/wlwmanifest.xml",
    "/administrator",
    "/phpmyadmin",
    "/phpMyAdmin",
    "/phppgadmin",
    "/myadmin",
    "/mysqladmin",
    "/pma/",
    "/phpinfo",
    "/server-status",
    "/server-info",
    "/.DS_Store",
    # Framework/runtime probes.
    "/actuator",  # Spring Boot
    "/solr/",
    "/jenkins/",
    "/console/",  # Play framework
    # Shell/backdoor probes.
    "/shell.php",
    "/cmd.php",
    "/c99.php",
    "/r57.php",
    "/backdoor.php",
    "/eval.php",
    "/wso.php",
    "/webshell",
    # Vendor library probes (phpunit RCE, etc.).
    "/vendor/phpunit",
    "/vendor/autoload.php",
    # Generic malicious extensions appearing in non-benign paths.
    ".aspx.bak",
    ".php.bak",
    ".sql.bak",
)


# Compile once at import. Match is case-insensitive because scanners will
# probe /Wp-Admin, /WP-ADMIN, etc. to try to dodge blocklists.
_SCANNER_RE = re.compile(
    "|".join(re.escape(s) for s in _SCANNER_SUBSTRINGS),
    re.IGNORECASE,
)


def classify(path: str) -> Classification:
    """Classify a 404 path. Pure function. Never raises.

    The path is expected to already have its query string stripped by the
    caller (middleware normalizes this). If a query string is present, it's
    included in the scan — that's fine, since scanners sometimes put probe
    patterns in ?page=../../etc/passwd style payloads.
    """
    if not path:
        return "unknown"

    # Exact-match benign list first (cheapest check).
    if path in _BENIGN_EXACT:
        return "benign-miss"

    # Benign prefixes (apple-touch-icon-*, .well-known/*).
    for prefix in _BENIGN_PREFIX:
        if path.startswith(prefix):
            return "benign-miss"

    # Scanner patterns.
    if _SCANNER_RE.search(path):
        return "scanner"

    return "unknown"
