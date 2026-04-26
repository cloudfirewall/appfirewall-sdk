package io.appfirewall.core.breaker;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    @Test
    void closedAllowsTraffic() {
        CircuitBreaker b = new CircuitBreaker(3, 1000);
        assertTrue(b.allow());
        assertEquals(BreakerState.CLOSED, b.state());
    }

    @Test
    void opensAfterThresholdConsecutiveFailures() {
        CircuitBreaker b = new CircuitBreaker(3, 1000);
        b.recordFailure();
        b.recordFailure();
        assertEquals(BreakerState.CLOSED, b.state());
        b.recordFailure();
        assertEquals(BreakerState.OPEN, b.state());
        assertFalse(b.allow());
    }

    @Test
    void successResetsFailureCount() {
        CircuitBreaker b = new CircuitBreaker(3, 1000);
        b.recordFailure();
        b.recordFailure();
        b.recordSuccess();
        b.recordFailure();
        b.recordFailure();
        // Three failures total but not consecutive — still CLOSED.
        assertEquals(BreakerState.CLOSED, b.state());
    }

    @Test
    void transitionsToHalfOpenAfterCooldown() {
        FakeClock clock = new FakeClock(0);
        CircuitBreaker b = new CircuitBreaker(2, 1000, clock);
        b.recordFailure();
        b.recordFailure();
        assertEquals(BreakerState.OPEN, b.state());
        assertFalse(b.allow());

        clock.advanceMillis(1500);
        assertTrue(b.allow(), "cooldown elapsed; OPEN→HALF_OPEN");
        assertEquals(BreakerState.HALF_OPEN, b.state());

        // Subsequent allow() during HALF_OPEN denies further probes until
        // success/failure is recorded.
        assertFalse(b.allow());
    }

    @Test
    void halfOpenSuccessReturnsToClosed() {
        FakeClock clock = new FakeClock(0);
        CircuitBreaker b = new CircuitBreaker(1, 100, clock);
        b.recordFailure();
        clock.advanceMillis(150);
        assertTrue(b.allow());
        b.recordSuccess();
        assertEquals(BreakerState.CLOSED, b.state());
    }

    @Test
    void rejectsInvalidConfig() {
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(0, 100));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(1, 0));
    }

    /** Simple controllable clock for time-dependent assertions. */
    private static final class FakeClock extends Clock {
        private final AtomicLong millis;

        FakeClock(long startMillis) {
            this.millis = new AtomicLong(startMillis);
        }

        void advanceMillis(long delta) {
            millis.addAndGet(delta);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
    }
}
