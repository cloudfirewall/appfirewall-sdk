package io.appfirewall.spring;

import io.appfirewall.core.Client;
import io.appfirewall.spring.context.RequestContextHolder;

import java.util.HashMap;
import java.util.Map;

/**
 * Static facade for app-layer signals. Mirrors FastAPI's
 * {@code appfirewall.record(event, fields)} ergonomics.
 *
 * <pre>{@code
 * import static io.appfirewall.spring.AppFirewall.record;
 * record("upload.parse_failed", "reason", "bad_magic", "size", bytes.length);
 * }</pre>
 *
 * <p>Contract (identical to FastAPI):
 * <ul>
 *   <li>Synchronous; never blocks; never awaits.</li>
 *   <li>Outside a request scope, silent no-op.</li>
 *   <li>Never throws. Any internal {@link Throwable} is caught and logged
 *       at DEBUG.</li>
 * </ul>
 */
public final class AppFirewall {

    private static volatile Client client;

    private AppFirewall() {}

    /** Set by the autoconfiguration on startup. Package-private. */
    static void setClient(Client c) {
        client = c;
    }

    public static void record(String event) {
        record(event, Map.of());
    }

    public static void record(String event, Map<String, ?> fields) {
        try {
            Client c = client;
            if (c == null) return;
            if (RequestContextHolder.current() == null) return;
            // TODO: forward to c.recordCustomEvent(event, fields)
        } catch (Throwable t) {
            // TODO: DEBUG log; never propagate.
        }
    }

    public static void record(String event, String k1, Object v1) {
        Map<String, Object> fields = new HashMap<>(2);
        fields.put(k1, v1);
        record(event, fields);
    }

    public static void record(String event, String k1, Object v1, String k2, Object v2) {
        Map<String, Object> fields = new HashMap<>(4);
        fields.put(k1, v1);
        fields.put(k2, v2);
        record(event, fields);
    }

    public static void record(
            String event,
            String k1, Object v1,
            String k2, Object v2,
            String k3, Object v3
    ) {
        Map<String, Object> fields = new HashMap<>(6);
        fields.put(k1, v1);
        fields.put(k2, v2);
        fields.put(k3, v3);
        record(event, fields);
    }
}
