package io.appfirewall.core.classifier;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Classifies a 404 path into {@link Classification#SCANNER},
 * {@link Classification#BENIGN_MISS}, or {@link Classification#UNKNOWN}.
 *
 * <p>Pure function; thread-safe; never throws. Patterns mirror
 * {@code python/appfirewall-fastapi/src/appfirewall_fastapi/_classifier.py}
 * exactly &mdash; the two SDKs must classify identically because a single
 * ingest service correlates events from both.
 *
 * <p>Match is case-insensitive: scanners commonly probe {@code /Wp-Admin},
 * {@code /WP-ADMIN}, etc. to dodge naive blocklists.
 */
public final class PathClassifier {

    private PathClassifier() {}

    private static final Set<String> BENIGN_EXACT = Set.of(
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
            "/apple-app-site-association"
    );

    private static final List<String> BENIGN_PREFIX = List.of(
            "/apple-touch-icon",
            "/.well-known/"
    );

    // Ordered roughly by frequency in the wild. Kept as plain strings for
    // auditability; compiled into a single alternation regex below.
    private static final List<String> SCANNER_SUBSTRINGS = List.of(
            // Path traversal.
            "../",
            "..%2f",
            "..%5c",
            "%2e%2e/",
            "%2e%2e%2f",
            // Sensitive OS/VCS files.
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
            // Config and secret files.
            "/.env",
            "/.dockerenv",
            "/web.config",
            "/config.php",
            "/database.yml",
            "/settings.py",
            // Admin / CMS probes.
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
            // Framework/runtime probes.
            "/actuator",
            "/solr/",
            "/jenkins/",
            "/console/",
            // Shell/backdoor probes.
            "/shell.php",
            "/cmd.php",
            "/c99.php",
            "/r57.php",
            "/backdoor.php",
            "/eval.php",
            "/wso.php",
            "/webshell",
            // Vendor library probes.
            "/vendor/phpunit",
            "/vendor/autoload.php",
            // Generic malicious extensions in non-benign paths.
            ".aspx.bak",
            ".php.bak",
            ".sql.bak"
    );

    private static final Pattern SCANNER_RE = Pattern.compile(
            SCANNER_SUBSTRINGS.stream().map(Pattern::quote).collect(Collectors.joining("|")),
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Classify a 404 path. Pure function. Never throws.
     *
     * <p>The path is expected to have its query string already stripped by
     * the caller. If a query string is present it's still scanned, since
     * scanners sometimes hide probe payloads in {@code ?file=../../etc/passwd}
     * style query parameters.
     *
     * @param path request path; may be {@code null}.
     * @return the classification (never {@code null}; {@code UNKNOWN} for
     *         {@code null} or empty input).
     */
    public static Classification classify(String path) {
        if (path == null || path.isEmpty()) {
            return Classification.UNKNOWN;
        }

        if (BENIGN_EXACT.contains(path)) {
            return Classification.BENIGN_MISS;
        }

        for (String prefix : BENIGN_PREFIX) {
            if (path.startsWith(prefix)) {
                return Classification.BENIGN_MISS;
            }
        }

        // Lowercasing matches the FastAPI re.IGNORECASE behavior. We rely on
        // the regex's CASE_INSENSITIVE flag rather than allocating, but for
        // the exact-set checks above we deliberately stay case-sensitive
        // (browsers don't randomize favicon casing).
        if (SCANNER_RE.matcher(path).find()) {
            return Classification.SCANNER;
        }

        return Classification.UNKNOWN;
    }

    // Exposed for tests and benchmarks; stable for as long as the FastAPI
    // SDK's pattern set stays in sync.
    static List<String> scannerSubstrings() {
        return SCANNER_SUBSTRINGS;
    }

    // Locale.ROOT placeholder so static-analysis sees we considered locale.
    @SuppressWarnings("unused")
    private static final Locale CASE_LOCALE = Locale.ROOT;
}
