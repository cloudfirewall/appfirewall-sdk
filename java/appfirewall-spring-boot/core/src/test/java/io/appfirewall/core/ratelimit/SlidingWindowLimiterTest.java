package io.appfirewall.core.ratelimit;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirrors {@code python/appfirewall-fastapi/tests/test_ratelimit.py}.
 */
class SlidingWindowLimiterTest {

    @Nested
    class BasicThreshold {
        @Test
        void allowsUpToLimit() {
            SlidingWindowLimiter lim = new SlidingWindowLimiter(3, 60);
            assertFalse(lim.hit("1.1.1.1"));
            assertFalse(lim.hit("1.1.1.1"));
            assertFalse(lim.hit("1.1.1.1"));
        }

        @Test
        void tripsOnFourthHit() {
            SlidingWindowLimiter lim = new SlidingWindowLimiter(3, 60);
            for (int i = 0; i < 3; i++) lim.hit("1.1.1.1");
            assertTrue(lim.hit("1.1.1.1"));
        }

        @Test
        void independentIpsDontInterfere() {
            SlidingWindowLimiter lim = new SlidingWindowLimiter(2, 60);
            lim.hit("1.1.1.1");
            lim.hit("1.1.1.1");
            assertTrue(lim.hit("1.1.1.1"));
            assertFalse(lim.hit("2.2.2.2"));
            assertFalse(lim.hit("2.2.2.2"));
            assertTrue(lim.hit("2.2.2.2"));
        }
    }

    @Nested
    class WindowBehavior {
        @Test
        void expiredEventsDropOut() {
            AtomicLong now = new AtomicLong(1_000_000L);
            SlidingWindowLimiter lim = new SlidingWindowLimiter(2, 60, now::get);
            lim.hit("1.1.1.1");
            lim.hit("1.1.1.1");
            assertTrue(lim.hit("1.1.1.1"));
            // Advance past the window.
            now.set(1_000_000L + 61_000L);
            assertFalse(lim.hit("1.1.1.1"));
        }

        @Test
        void partialWindowExpiry() {
            AtomicLong now = new AtomicLong(1_000_000L);
            SlidingWindowLimiter lim = new SlidingWindowLimiter(3, 60, now::get);
            lim.hit("1.1.1.1");                       // t=1_000_000
            now.set(1_000_000L + 30_000L);
            lim.hit("1.1.1.1");                       // t=1_030_000
            lim.hit("1.1.1.1");                       // t=1_030_000
            now.set(1_000_000L + 61_000L);
            // First hit expired, two remain in window. New hit → 3, allowed; 4th trips.
            assertFalse(lim.hit("1.1.1.1"));
            assertTrue(lim.hit("1.1.1.1"));
        }
    }

    @Test
    void countMatchesHits() {
        SlidingWindowLimiter lim = new SlidingWindowLimiter(10, 60);
        for (int i = 0; i < 5; i++) lim.hit("1.1.1.1");
        assertEquals(5, lim.currentCount("1.1.1.1"));
        assertEquals(0, lim.currentCount("unknown"));
    }

    @Nested
    class Validation {
        @Test
        void rejectsNonpositiveMax() {
            assertThrows(IllegalArgumentException.class, () -> new SlidingWindowLimiter(0, 60));
        }

        @Test
        void rejectsNonpositiveWindow() {
            assertThrows(IllegalArgumentException.class, () -> new SlidingWindowLimiter(10, 0));
        }
    }

    @Test
    void evictsStaleIpsWhenCapHit() {
        AtomicLong now = new AtomicLong(1_000_000L);
        SlidingWindowLimiter lim = new SlidingWindowLimiter(10, 1, now::get);
        lim.setMaxIps(10);

        for (int i = 0; i < 10; i++) lim.hit("1.0.0." + i);
        assertEquals(10, lim.trackedIps());

        // Advance past the 1-second window so all are stale.
        now.set(1_002_000L);

        // One more IP triggers eviction of the stale entries.
        lim.hit("2.0.0.1");

        assertTrue(lim.isTracking("2.0.0.1"));
        assertTrue(lim.trackedIps() < 10);
    }
}
