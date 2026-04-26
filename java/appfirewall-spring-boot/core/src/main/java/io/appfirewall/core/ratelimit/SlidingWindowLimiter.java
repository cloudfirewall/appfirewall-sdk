package io.appfirewall.core.ratelimit;

/**
 * In-process sliding-window per-IP rate limiter.
 *
 * <p>Port of {@code python/appfirewall-fastapi/src/appfirewall_fastapi/_ratelimit.py}.
 * The Python tests in {@code tests/test_ratelimit.py} are the spec.
 *
 * <p>TODO(v0.1): port the implementation. Plan:
 * <ul>
 *   <li>{@code ConcurrentHashMap<String, Deque<Long>>} keyed by IP;</li>
 *   <li>each {@link #hit(String, String)} appends now-millis, evicts entries
 *       older than the window, returns {@code size > max}.</li>
 *   <li>Periodic GC of empty deques to bound memory under churn.</li>
 * </ul>
 */
public final class SlidingWindowLimiter implements RateLimiter {

    @Override
    public boolean hit(String ip, String classification) {
        throw new UnsupportedOperationException("v0.1 TODO: port from _ratelimit.py");
    }

    @Override
    public int currentCount(String ip, String classification) {
        throw new UnsupportedOperationException("v0.1 TODO");
    }
}
