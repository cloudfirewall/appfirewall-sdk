package io.appfirewall.core.buffer;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Bounded queue + drop-oldest overflow policy. Hot-path entry point: every
 * emitted event passes through {@link #emit(Map)}.
 *
 * <p>Port of {@code python/appfirewall-fastapi/src/appfirewall_fastapi/_buffer.py}
 * (the {@code EventBuffer} half &mdash; the shipper half lives in
 * {@link Shipper}). Capacity, batching constants, and ordering must match
 * the FastAPI reference exactly.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #emit(Map)} is non-blocking and never throws.</li>
 *   <li>On overflow we drop the OLDEST event, not the newest. Recent events
 *       are more valuable than stale ones (see spec §6.3 / FastAPI ARCHITECTURE).</li>
 * </ul>
 *
 * <p>TODO(v0.1): port the implementation.
 */
public final class EventBuffer {

    public static final int CAPACITY = 10_000;
    public static final int BATCH_MAX_SIZE = 500;
    public static final long BATCH_MAX_AGE_MILLIS = 2_000L;

    private final ArrayBlockingQueue<Map<String, Object>> queue = new ArrayBlockingQueue<>(CAPACITY);

    public void emit(Map<String, Object> event) {
        // Tag with environment downstream of here, in the Client coordinator.
        if (!queue.offer(event)) {
            // Drop oldest, retry once. If still full, drop this event.
            queue.poll();
            queue.offer(event);
            // TODO: rate-limit a single WARNING log on first overflow.
        }
    }

    /** Drain up to {@link #BATCH_MAX_SIZE} events into the supplied list. */
    public int drainTo(java.util.Collection<? super Map<String, Object>> out, int max) {
        return queue.drainTo(out, max);
    }

    public int size() { return queue.size(); }
}
