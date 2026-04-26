package io.appfirewall.core.buffer;

import io.appfirewall.core.breaker.CircuitBreaker;
import io.appfirewall.core.config.Mode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

/**
 * Background shipper. Drains {@link EventBuffer}, batches up to
 * {@link EventBuffer#BATCH_MAX_SIZE} events or
 * {@link EventBuffer#BATCH_MAX_AGE_MILLIS} ms, and either gzips + POSTs to
 * the ingest service ({@link Mode#SHIP}) or appends NDJSON to a local file
 * ({@link Mode#LOCAL}).
 *
 * <p>Mirrors the shipper half of
 * {@code python/appfirewall-fastapi/src/appfirewall_fastapi/_buffer.py},
 * including the WARNING-level log contract on ingest failures (4xx, 5xx,
 * exception). The shipper thread must never die; any error in the loop is
 * caught and logged and the loop continues.
 *
 * <p>Threading: a single dedicated daemon thread, started by
 * {@link #start()} and joined (with grace) by {@link #shutdown()}. We own
 * the lifecycle deliberately so shutdown can flush pending events before
 * returning.
 */
public final class Shipper {

    private static final Logger LOG = Logger.getLogger("appfirewall");

    private static final long SHUTDOWN_GRACE_MILLIS = 5_000L;
    private static final long POST_TIMEOUT_MILLIS = 5_000L;
    private static final String USER_AGENT = "appfirewall-spring-boot/0.1";

    private final EventBuffer buffer;
    private final CircuitBreaker breaker;
    private final Mode mode;
    private final String endpoint;
    private final String apiKey;
    private final Path localLogPath;
    private final HttpClient http;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private final AtomicLong lastShipMillis = new AtomicLong(0L);
    private volatile int lastShipStatus = 0;
    private Thread thread;

    public Shipper(
            EventBuffer buffer,
            CircuitBreaker breaker,
            Mode mode,
            String endpoint,
            String apiKey,
            Path localLogPath
    ) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.breaker = Objects.requireNonNull(breaker, "breaker");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.localLogPath = localLogPath;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(POST_TIMEOUT_MILLIS))
                .build();
    }

    /** Start the shipper thread. Idempotent. */
    public void start() {
        if (mode == Mode.OFF) return;
        if (!running.compareAndSet(false, true)) return;
        thread = new Thread(this::run, "appfirewall-shipper");
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((t, e) ->
                LOG.log(Level.WARNING, "appfirewall: shipper thread died unexpectedly", e));
        thread.start();
    }

    /** Signal shutdown; flush pending events; join with a 5-second grace. */
    public void shutdown() {
        if (!running.get()) return;
        shutdownRequested.set(true);
        if (thread != null) {
            try {
                thread.join(SHUTDOWN_GRACE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                thread.interrupt();
            }
        }
    }

    public int lastShipStatus() { return lastShipStatus; }
    public long lastShipMillis() { return lastShipMillis.get(); }

    private void run() {
        List<Map<String, Object>> batch = new ArrayList<>(EventBuffer.BATCH_MAX_SIZE);
        long lastFlush = System.currentTimeMillis();

        while (true) {
            try {
                long age = System.currentTimeMillis() - lastFlush;
                long timeoutMillis = Math.max(10L, EventBuffer.BATCH_MAX_AGE_MILLIS - age);
                Map<String, Object> evt = buffer.poll(timeoutMillis, TimeUnit.MILLISECONDS);
                if (evt != null) {
                    batch.add(evt);
                    // Greedy drain to amortize the wakeup cost.
                    if (batch.size() < EventBuffer.BATCH_MAX_SIZE) {
                        buffer.drainTo(batch, EventBuffer.BATCH_MAX_SIZE - batch.size());
                    }
                }

                age = System.currentTimeMillis() - lastFlush;
                boolean shouldFlush = batch.size() >= EventBuffer.BATCH_MAX_SIZE
                        || (!batch.isEmpty() && age >= EventBuffer.BATCH_MAX_AGE_MILLIS)
                        || (shutdownRequested.get() && !batch.isEmpty());

                if (shouldFlush) {
                    try {
                        flush(batch);
                    } catch (Throwable t) {
                        // Shipper must never die. Swallow + log at FINE.
                        LOG.log(Level.FINE, "appfirewall: flush error", t);
                    }
                    batch.clear();
                    lastFlush = System.currentTimeMillis();
                }

                if (shutdownRequested.get() && batch.isEmpty()) {
                    return;
                }
            } catch (InterruptedException e) {
                // Shutdown path: drain whatever we have once and return.
                if (!batch.isEmpty()) {
                    try { flush(batch); } catch (Throwable ignored) {}
                }
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // Defense in depth.
                LOG.log(Level.FINE, "appfirewall: shipper loop iteration failed", t);
            }
        }
    }

    private void flush(List<Map<String, Object>> batch) {
        if (batch.isEmpty()) return;

        if (mode == Mode.LOCAL) {
            flushLocal(batch);
            return;
        }

        // mode == SHIP
        if (!breaker.allow()) {
            // Breaker open; drop this batch.
            return;
        }

        byte[] body = encodeBatchGzip(batch);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(POST_TIMEOUT_MILLIS))
                    .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                    .header("Content-Type", "application/x-ndjson")
                    .header("Content-Encoding", "gzip")
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            int status = resp.statusCode();
            lastShipStatus = status;
            lastShipMillis.set(System.currentTimeMillis());

            if (status >= 200 && status < 300) {
                breaker.recordSuccess();
            } else if (status >= 400 && status < 500 && status != 429) {
                // Client bug (bad API key, bad request shape) — retry won't help.
                LOG.warning(String.format(
                        "appfirewall: ingest at %s returned %d; dropping %d events",
                        endpoint, status, batch.size()));
                breaker.recordSuccess();  // don't open the breaker on client bugs
            } else {
                LOG.warning(String.format(
                        "appfirewall: ingest at %s returned %d; %d events will be retried",
                        endpoint, status, batch.size()));
                breaker.recordFailure();
            }
        } catch (Exception e) {
            LOG.warning(String.format(
                    "appfirewall: failed to ship %d events to %s: %s",
                    batch.size(), endpoint, e.getMessage()));
            breaker.recordFailure();
        }
    }

    private void flushLocal(List<Map<String, Object>> batch) {
        if (localLogPath == null) return;
        try {
            if (localLogPath.getParent() != null) {
                Files.createDirectories(localLogPath.getParent());
            }
            StringBuilder sb = new StringBuilder(batch.size() * 128);
            for (Map<String, Object> e : batch) {
                sb.append(JsonEncoder.encode(e)).append('\n');
            }
            Files.writeString(
                    localLogPath,
                    sb.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            LOG.log(Level.FINE, "appfirewall: local log write failed", e);
        }
    }

    static byte[] encodeBatchGzip(List<Map<String, Object>> batch) {
        StringBuilder sb = new StringBuilder(batch.size() * 128);
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(JsonEncoder.encode(batch.get(i)));
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Should never happen for in-memory streams.
            throw new IllegalStateException("gzip encoding failed", e);
        }
        return baos.toByteArray();
    }
}
