package io.appfirewall.core.buffer;

import io.appfirewall.core.breaker.CircuitBreaker;
import io.appfirewall.core.config.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the shipper writes JSONL to disk in {@link Mode#LOCAL}.
 *
 * <p>Ship-mode (HTTP POST) tests are deferred to v0.1+ &mdash; they need a
 * lightweight HTTP server to assert against. The encode/gzip path is
 * tested directly via {@link Shipper#encodeBatchGzip(List)}.
 */
class ShipperLocalModeTest {

    @Test
    void localModeWritesJsonl(@TempDir Path tmp) throws Exception {
        Path log = tmp.resolve("events.jsonl");
        EventBuffer buf = new EventBuffer();
        CircuitBreaker breaker = new CircuitBreaker(5, 30_000);
        Shipper shipper = new Shipper(buf, breaker, Mode.LOCAL, null, null, log);
        shipper.start();
        try {
            // Two events; the shipper should batch them and flush within a
            // few hundred ms (BATCH_MAX_AGE_MILLIS = 2000ms).
            buf.emit(orderedMap("event", "http", "status", 200, "path", "/health"));
            buf.emit(orderedMap("event", "http", "status", 404, "path", "/wp-admin"));

            waitForFile(log, 4_000L);
        } finally {
            shipper.shutdown();
        }

        List<String> lines = Files.readAllLines(log);
        assertEquals(2, lines.size(), "expected two NDJSON lines");
        assertTrue(lines.get(0).contains("\"status\":200"));
        assertTrue(lines.get(1).contains("\"path\":\"/wp-admin\""));
    }

    @Test
    void encodeBatchGzipProducesValidGzip() throws IOException {
        byte[] gz = Shipper.encodeBatchGzip(List.of(
                Map.of("event", "http"),
                Map.of("event", "http", "status", 200)
        ));
        assertNotNull(gz);
        // gzip magic 0x1f 0x8b
        assertEquals((byte) 0x1f, gz[0]);
        assertEquals((byte) 0x8b, gz[1]);
    }

    private static Map<String, Object> orderedMap(Object... kvs) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kvs.length; i += 2) {
            m.put((String) kvs[i], kvs[i + 1]);
        }
        return m;
    }

    private static void waitForFile(Path p, long timeoutMillis) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(p) && Files.size(p) > 0) return;
            Thread.sleep(50);
        }
    }
}
