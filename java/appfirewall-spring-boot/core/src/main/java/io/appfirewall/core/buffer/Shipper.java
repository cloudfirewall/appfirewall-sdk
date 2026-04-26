package io.appfirewall.core.buffer;

import io.appfirewall.core.breaker.CircuitBreaker;

/**
 * Background shipper thread. Drains {@link EventBuffer}, batches up to
 * {@link EventBuffer#BATCH_MAX_SIZE} events or
 * {@link EventBuffer#BATCH_MAX_AGE_MILLIS} ms, gzips, and POSTs.
 *
 * <p>Port of the shipper half of
 * {@code python/appfirewall-fastapi/src/appfirewall_fastapi/_buffer.py}.
 *
 * <p>Threading: a single dedicated daemon thread (or virtual thread on
 * Java 21+, decided per spec §12 open question 3). Never use
 * {@code @Async} or the common ForkJoinPool &mdash; we own the lifecycle so
 * shutdown can join cleanly with a 5-second grace.
 *
 * <p>Failure handling (mirroring FastAPI's WARNING-level logs):
 * <ul>
 *   <li>2xx &rarr; {@code breaker.recordSuccess()}</li>
 *   <li>4xx (not 429) &rarr; drop batch, WARN with status + endpoint + count,
 *       {@code breaker.recordSuccess()} (don't open the breaker on client bugs)</li>
 *   <li>5xx / 429 &rarr; WARN, {@code breaker.recordFailure()}</li>
 *   <li>Exception &rarr; WARN with message, {@code breaker.recordFailure()}</li>
 * </ul>
 *
 * <p>TODO(v0.1): port the implementation using {@code java.net.http.HttpClient}.
 */
public final class Shipper {

    private final EventBuffer buffer;
    private final CircuitBreaker breaker;
    private final String endpoint;
    private final String apiKey;

    public Shipper(EventBuffer buffer, CircuitBreaker breaker, String endpoint, String apiKey) {
        this.buffer = buffer;
        this.breaker = breaker;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    public void start() {
        throw new UnsupportedOperationException("v0.1 TODO");
    }

    public void shutdown() {
        throw new UnsupportedOperationException("v0.1 TODO");
    }
}
