package io.appfirewall.core.ratelimit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * In-process sliding-window per-IP rate limiter.
 *
 * <p>Port of
 * {@code python/appfirewall-fastapi/src/appfirewall_fastapi/_ratelimit.py}.
 * Tests in {@code tests/test_ratelimit.py} are the spec.
 *
 * <p>Thread-safe: the filter may be invoked from many request threads
 * concurrently in some Spring deployments (Tomcat thread pool, virtual
 * threads, etc.).
 *
 * <p>Memory: the IP map is bounded at {@value #DEFAULT_MAX_IPS} distinct IPs.
 * When the cap is reached, fully-stale entries are evicted; if still above
 * 80 % of cap, the oldest half by last-event timestamp is dropped. This
 * gives bursty-but-recent IPs room and prevents a distributed scan from
 * blowing memory.
 */
public final class SlidingWindowLimiter implements RateLimiter {

    public static final int DEFAULT_MAX_IPS = 50_000;

    private final int max;
    private final long windowMillis;
    private final LongSupplier nowMillis;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Deque<Long>> events = new HashMap<>();

    private int maxIps = DEFAULT_MAX_IPS;

    public SlidingWindowLimiter(int maxEvents, double windowSeconds) {
        this(maxEvents, windowSeconds, System::currentTimeMillis);
    }

    /** Test-friendly ctor that accepts a controllable time source. */
    public SlidingWindowLimiter(int maxEvents, double windowSeconds, LongSupplier nowMillis) {
        if (maxEvents <= 0) {
            throw new IllegalArgumentException("maxEvents must be positive");
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be positive");
        }
        this.max = maxEvents;
        this.windowMillis = (long) (windowSeconds * 1000.0);
        this.nowMillis = nowMillis;
    }

    @Override
    public boolean hit(String ip) {
        long now = nowMillis.getAsLong();
        long cutoff = now - windowMillis;

        lock.lock();
        try {
            Deque<Long> dq = events.get(ip);
            if (dq == null) {
                if (events.size() >= maxIps) {
                    evictLocked(now);
                }
                dq = new ArrayDeque<>();
                events.put(ip, dq);
            }

            // Drop expired events from the head.
            while (!dq.isEmpty() && dq.peekFirst() < cutoff) {
                dq.removeFirst();
            }

            dq.addLast(now);

            // Tripped means strictly over the limit (max+1th and beyond).
            return dq.size() > max;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int currentCount(String ip) {
        long cutoff = nowMillis.getAsLong() - windowMillis;
        lock.lock();
        try {
            Deque<Long> dq = events.get(ip);
            if (dq == null) {
                return 0;
            }
            while (!dq.isEmpty() && dq.peekFirst() < cutoff) {
                dq.removeFirst();
            }
            return dq.size();
        } finally {
            lock.unlock();
        }
    }

    /** Lower the IP cap. Test-only escape hatch; do not call in production. */
    void setMaxIps(int maxIps) {
        if (maxIps <= 0) throw new IllegalArgumentException("maxIps must be positive");
        this.maxIps = maxIps;
    }

    int trackedIps() {
        lock.lock();
        try {
            return events.size();
        } finally {
            lock.unlock();
        }
    }

    boolean isTracking(String ip) {
        lock.lock();
        try {
            return events.containsKey(ip);
        } finally {
            lock.unlock();
        }
    }

    private void evictLocked(long now) {
        long cutoff = now - windowMillis;

        // First pass: drop fully stale entries.
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Deque<Long>> e : events.entrySet()) {
            Deque<Long> dq = e.getValue();
            if (dq.isEmpty() || dq.peekLast() < cutoff) {
                stale.add(e.getKey());
            }
        }
        for (String ip : stale) {
            events.remove(ip);
        }

        // Second pass if still tight: drop oldest half by last-event time.
        if (events.size() >= (int) (maxIps * 0.8)) {
            record IpAge(long lastSeen, String ip) {}
            List<IpAge> ages = new ArrayList<>(events.size());
            for (Map.Entry<String, Deque<Long>> e : events.entrySet()) {
                long last = e.getValue().isEmpty() ? 0L : e.getValue().peekLast();
                ages.add(new IpAge(last, e.getKey()));
            }
            ages.sort(Comparator.comparingLong(IpAge::lastSeen));
            int toDrop = ages.size() / 2;
            for (int i = 0; i < toDrop; i++) {
                events.remove(ages.get(i).ip());
            }
        }
    }
}
