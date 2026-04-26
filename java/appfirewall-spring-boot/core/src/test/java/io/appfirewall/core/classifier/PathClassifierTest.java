package io.appfirewall.core.classifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mirrors {@code python/appfirewall-fastapi/tests/test_classifier.py}. New
 * cases here should be added to the Python test in lockstep so both SDKs
 * classify identically.
 */
class PathClassifierTest {

    @ParameterizedTest
    @ValueSource(strings = {
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
            "/vendor/phpunit/phpunit/src/Util/PHP/eval-stdin.php"
    })
    void knownScannerPaths(String path) {
        assertEquals(Classification.SCANNER, PathClassifier.classify(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/../../etc/passwd",
            "/foo/../../etc/passwd",
            "/page?file=../../../etc/passwd",
            "/%2e%2e/%2e%2e/etc/passwd",
            "/..%2fetc%2fpasswd"
    })
    void pathTraversal(String path) {
        assertEquals(Classification.SCANNER, PathClassifier.classify(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/WP-ADMIN",
            "/Wp-Login.PHP",
            "/PhpMyAdmin/",
            "/.GIT/config"
    })
    void caseInsensitive(String path) {
        assertEquals(Classification.SCANNER, PathClassifier.classify(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/favicon.ico",
            "/robots.txt",
            "/sitemap.xml",
            "/humans.txt",
            "/manifest.webmanifest",
            "/.well-known/security.txt"
    })
    void benignExactMatches(String path) {
        assertEquals(Classification.BENIGN_MISS, PathClassifier.classify(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/apple-touch-icon.png",
            "/apple-touch-icon-precomposed.png",
            "/apple-touch-icon-120x120.png",
            "/.well-known/acme-challenge/abc123"
    })
    void benignPrefixMatches(String path) {
        assertEquals(Classification.BENIGN_MISS, PathClassifier.classify(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/",
            "/api/v1/widgets",
            "/users/42",
            "/some/normal/path"
    })
    void unknownFallthrough(String path) {
        assertEquals(Classification.UNKNOWN, PathClassifier.classify(path));
    }

    @Test
    void nullAndEmpty() {
        assertEquals(Classification.UNKNOWN, PathClassifier.classify(null));
        assertEquals(Classification.UNKNOWN, PathClassifier.classify(""));
    }

    @Test
    void wireValuesStable() {
        assertEquals("scanner", Classification.SCANNER.wire());
        assertEquals("benign-miss", Classification.BENIGN_MISS.wire());
        assertEquals("unknown", Classification.UNKNOWN.wire());
    }
}
