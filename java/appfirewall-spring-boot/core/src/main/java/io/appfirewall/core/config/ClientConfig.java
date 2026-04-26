package io.appfirewall.core.config;

import java.util.List;
import java.util.Map;

/**
 * Immutable configuration for a {@code Client} instance.
 *
 * <p>Field semantics are documented in the spec
 * (docs/specs/appfirewall-spring-boot.md §4.1) and must match the FastAPI
 * SDK's {@code _Client.__init__} parameters exactly.
 *
 * @param apiKey            bearer token; required when mode is SHIP
 * @param endpoint          ingest URL; default
 *                          {@code https://ingest.appfirewall.io/v1/events}
 * @param environment       free-form tag attached to every event
 * @param mode              SHIP / LOCAL / OFF
 * @param localLogPath      file path (only used when mode is LOCAL)
 * @param trustedProxies    list of CIDRs and/or the literal {@code "cloudflare"}
 * @param classify404       enable the 404 path classifier
 * @param rateLimit         per-class limits; map of class &rarr; (max, windowSeconds)
 * @param enforceRateLimit  if {@code true}, deny requests that exceed the limit
 * @param onError           {@code ignore | warn | raise}
 */
public record ClientConfig(
        String apiKey,
        String endpoint,
        String environment,
        Mode mode,
        String localLogPath,
        List<String> trustedProxies,
        boolean classify404,
        Map<String, RateLimitWindow> rateLimit,
        boolean enforceRateLimit,
        OnError onError
) {

    /** A (max, windowSeconds) pair for the per-class sliding window limiter. */
    public record RateLimitWindow(int max, double windowSeconds) {}

    /** Failure-handling policy. */
    public enum OnError { IGNORE, WARN, RAISE }
}
