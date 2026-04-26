package io.appfirewall.core.ratelimit;

/**
 * Pluggable per-IP rate limiter. v0.1 ships {@link SlidingWindowLimiter}
 * (in-process); v0.2 will add a Redis-backed implementation. See spec §5.2.
 *
 * <p>This interface is per-IP and per-instance. The {@code Client}
 * coordinator owns one limiter per classification (e.g. one for
 * {@code scanner}) and routes hits accordingly.
 */
public interface RateLimiter {

    /**
     * Record a hit for {@code ip} and return whether it pushed the IP over
     * the limit in the current window.
     *
     * @return {@code true} if this hit brought the IP's count strictly above
     *         the configured maximum.
     */
    boolean hit(String ip);

    /** Current count in the active window for diagnostics. */
    int currentCount(String ip);
}
