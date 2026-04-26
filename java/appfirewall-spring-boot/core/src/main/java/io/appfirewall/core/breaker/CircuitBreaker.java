package io.appfirewall.core.breaker;

import java.time.Clock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Three-state circuit breaker for the shipper.
 *
 * <p>Mirrors {@code python/appfirewall-fastapi/src/appfirewall_fastapi/_breaker.py}.
 * The breaker does not do retries or timing itself &mdash; callers observe
 * the outcome of their own attempts and feed {@link #recordSuccess()} /
 * {@link #recordFailure()} back in.
 *
 * <p>Thread-safe.
 */
public final class CircuitBreaker {

    private final int threshold;
    private final long resetAfterNanos;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private BreakerState state = BreakerState.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAtNanos = 0L;

    public CircuitBreaker(int failThreshold, long resetAfterMillis) {
        this(failThreshold, resetAfterMillis, Clock.systemUTC());
    }

    /** Test-friendly constructor accepting a custom {@link Clock}. */
    public CircuitBreaker(int failThreshold, long resetAfterMillis, Clock clock) {
        if (failThreshold <= 0) {
            throw new IllegalArgumentException("failThreshold must be positive");
        }
        if (resetAfterMillis <= 0) {
            throw new IllegalArgumentException("resetAfterMillis must be positive");
        }
        this.threshold = failThreshold;
        this.resetAfterNanos = resetAfterMillis * 1_000_000L;
        this.clock = clock;
    }

    /**
     * @return {@code true} if a call should be attempted; advances state as needed.
     */
    public boolean allow() {
        lock.lock();
        try {
            return switch (state) {
                case CLOSED -> true;
                case OPEN -> {
                    if (nowNanos() - openedAtNanos >= resetAfterNanos) {
                        state = BreakerState.HALF_OPEN;
                        yield true;
                    }
                    yield false;
                }
                // After the first probe is granted by the OPEN→HALF_OPEN transition,
                // subsequent callers see false until success/failure is recorded.
                case HALF_OPEN -> false;
            };
        } finally {
            lock.unlock();
        }
    }

    public void recordSuccess() {
        lock.lock();
        try {
            state = BreakerState.CLOSED;
            consecutiveFailures = 0;
        } finally {
            lock.unlock();
        }
    }

    public void recordFailure() {
        lock.lock();
        try {
            consecutiveFailures++;
            if (consecutiveFailures >= threshold && state != BreakerState.OPEN) {
                state = BreakerState.OPEN;
                openedAtNanos = nowNanos();
            }
        } finally {
            lock.unlock();
        }
    }

    public BreakerState state() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    private long nowNanos() {
        // Clock.millis is monotonic when used with systemUTC on a fixed-rate
        // host; for tests we accept any monotonic-ish source. The reference
        // Python uses time.monotonic(); we approximate with a clock-derived
        // long here, keeping the test seam.
        return clock.instant().toEpochMilli() * 1_000_000L;
    }
}
