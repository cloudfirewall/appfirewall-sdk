package io.appfirewall.core.ratelimit;

/**
 * Pluggable per-IP rate limiter. v0.1 ships {@link SlidingWindowLimiter}
 * (in-process); v0.2 will add a Redis-backed implementation. See spec §5.2.
 */
public interface RateLimiter {

    /**
     * Record a hit and return whether it is over the limit.
     *
     * @return {@code true} if the IP has exceeded its limit in this window.
     */
    boolean hit(String ip, String classification);

    /** Current count in the active window for diagnostics. */
    int currentCount(String ip, String classification);
}
