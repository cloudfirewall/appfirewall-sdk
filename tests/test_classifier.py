"""Tests for the 404 classifier.

Coverage:
  - Core scanner patterns (wp-admin, .env, .git/config, path traversal)
  - Case-insensitivity
  - Encoded traversal
  - Benign exact matches
  - Benign prefixes (apple-touch-icon-*, .well-known/*)
  - Unknown fallthrough
  - Query-string payloads
"""

from __future__ import annotations

import pytest

from appfirewall_fastapi._classifier import classify


class TestScannerPatterns:
    @pytest.mark.parametrize("path", [
        "/wp-admin",
        "/wp-admin/",
        "/wp-admin/admin-ajax.php",
        "/wp-login.php",
        "/xmlrpc.php",
        "/.env",
        "/.env.local",
        "/.env.production",
        "/.git/config",
        "/.git/HEAD",
        "/.aws/credentials",
        "/.ssh/id_rsa",
        "/phpmyadmin",
        "/phpmyadmin/",
        "/server-status",
        "/actuator",
        "/actuator/env",
        "/shell.php",
        "/cmd.php",
        "/vendor/phpunit/phpunit/src/Util/PHP/eval-stdin.php",
    ])
    def test_known_scanner_paths(self, path: str) -> None:
        assert classify(path) == "scanner"

    @pytest.mark.parametrize("path", [
        "/../../etc/passwd",
        "/foo/../../etc/passwd",
        "/page?file=../../../etc/passwd",
        "/%2e%2e/%2e%2e/etc/passwd",
        "/..%2fetc%2fpasswd",
    ])
    def test_path_traversal(self, path: str) -> None:
        assert classify(path) == "scanner"

    @pytest.mark.parametrize("path", [
        "/WP-ADMIN",
        "/Wp-Login.PHP",
        "/PhpMyAdmin/",
        "/.GIT/config",
    ])
    def test_case_insensitive(self, path: str) -> None:
        assert classify(path) == "scanner"


class TestBenignMisses:
    @pytest.mark.parametrize("path", [
        "/favicon.ico",
        "/robots.txt",
        "/sitemap.xml",
        "/humans.txt",
        "/manifest.webmanifest",
        "/.well-known/security.txt",
    ])
    def test_exact_matches(self, path: str) -> None:
        assert classify(path) == "benign-miss"

    @pytest.mark.parametrize("path", [
        "/apple-touch-icon.png",
        "/apple-touch-icon-precomposed.png",
        "/apple-touch-icon-120x120.png",
        "/apple-touch-icon-120x120-precomposed.png",
    ])
    def test_apple_touch_icons(self, path: str) -> None:
        assert classify(path) == "benign-miss"

    @pytest.mark.parametrize("path", [
        "/.well-known/acme-challenge/abc",
        "/.well-known/openid-configuration",
    ])
    def test_well_known_prefix(self, path: str) -> None:
        # .well-known/security.txt is exact-matched as benign; other .well-known
        # paths take the prefix rule.
        assert classify(path) == "benign-miss"


class TestUnknown:
    @pytest.mark.parametrize("path", [
        "/",
        "/api/v1/users/42",
        "/old-product-link-that-no-longer-exists",
        "/static/js/main.abc123.js",
        "/blog/2024/some-post/",
    ])
    def test_regular_404s(self, path: str) -> None:
        assert classify(path) == "unknown"

    def test_empty_path(self) -> None:
        assert classify("") == "unknown"


class TestDoesNotOverMatch:
    """Regression tests for false-positive patterns we've been careful about."""

    def test_env_in_middle_of_legit_path(self) -> None:
        # We match ``/.env`` with a leading slash and dot, so this should NOT
        # be flagged as scanner just because "env" appears in it.
        assert classify("/api/environment/config") == "unknown"

    def test_wp_in_path(self) -> None:
        # "wp-" without the admin/login/etc suffix isn't in our list.
        assert classify("/blog/wp-is-overrated") == "unknown"
