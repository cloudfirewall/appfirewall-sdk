package io.appfirewall.core.buffer;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bounded event queue with drop-oldest overflow.
 *
 * <p>Mirrors {@code python/appfirewall-fastapi/src/appfirewall_fastapi/_buffer.py}'s
 * queue half. Capacity, batch size, and batch age constants must match the
 * FastAPI reference exactly.
 *
 * <p>Hot-path contract:
 * <ul>
 *   <li>{@link #emit(Map)} is non-blocking and never throws.</li>
 *   <li>On overflow the OLDEST event is dropped, not the newest. Recent
 *       events are more valuable than stale ones.</li>
 * </ul>
 *
 * <p>The {@link Shipper} drains via {@link #poll(long, TimeUnit)}.
 */
public final class EventBuffer {

    public static final int CAPACITY = 10_000;
    public static final int BATCH_MAX_SIZE = 500;
    public static final long BATCH_MAX_AGE_MILLIS = 2_000L;

    private static final Logger LOG = Logger.getLogger("appfirewall");

    private final BlockingQueue<Map<String, Object>> queue;
    private final AtomicBoolean warnedOverflow = new AtomicBoolean(false);
    private final AtomicLong droppedOverflow = new AtomicLong(0L);
    private final AtomicLong emitted = new AtomicLong(0L);

    public EventBuffer() {
        this(CAPACITY);
    }

    /** Test-only ctor; production code should use the no-arg form. */
    EventBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /**
     * Enqueue an event. Never blocks; never throws.
     */
    public void emit(Map<String, Object> event) {
        if (event == null) return;
        try {
            if (queue.offer(event)) {
                emitted.incrementAndGet();
                return;
            }
            // Drop oldest, retry once. If still full (race), give up on this event.
            queue.poll();
            droppedOverflow.incrementAndGet();
            if (!queue.offer(event)) {
                droppedOverflow.incrementAndGet();
            } else {
                emitted.incrementAndGet();
            }
            if (warnedOverflow.compareAndSet(false, true)) {
                LOG.warning("appfirewall: event buffer overflowed; dropping oldest events");
            }
        } catch (Throwable t) {
            // Belt-and-braces: never propagate from the hot path.
            LOG.log(Level.FINE, "appfirewall: emit() failed", t);
        }
    }

    /**
     * Block up to {@code timeout} for the next event; return {@code null} on
     * timeout. Used by the {@link Shipper} loop.
     */
    public Map<String, Object> poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    /** Drain up to {@code max} events into {@code out}. Returns count drained. */
    public int drainTo(java.util.Collection<? super Map<String, Object>> out, int max) {
        return queue.drainTo(out, max);
    }

    public int size() { return queue.size(); }
    public int capacity() { return ((ArrayBlockingQueue<?>) queue).remainingCapacity() + queue.size(); }
    public long emittedCount() { return emitted.get(); }
    public long droppedOverflowCount() { return droppedOverflow.get(); }
}
