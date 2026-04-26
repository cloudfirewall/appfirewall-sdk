package io.appfirewall.spring;

import io.appfirewall.core.Client;
import io.appfirewall.core.context.RequestContext;
import io.appfirewall.spring.context.RequestContextHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Static facade for app-layer signals. Mirrors FastAPI's
 * {@code appfirewall.record(event, fields)} ergonomics.
 *
 * <pre>{@code
 * import static io.appfirewall.spring.AppFirewall.record;
 *
 * record("upload.parse_failed", "reason", "bad_magic", "size", bytes.length);
 * }</pre>
 *
 * <p>Contract (identical to FastAPI):
 * <ul>
 *   <li>Synchronous; never blocks; never awaits.</li>
 *   <li>Outside a request scope, silent no-op.</li>
 *   <li>Never throws. Any internal {@link Throwable} is caught and logged
 *       at FINE.</li>
 * </ul>
 */
public final class AppFirewall {

    private static final Logger LOG = Logger.getLogger("appfirewall");
    private static volatile Client client;

    private AppFirewall() {}

    /** Set by the autoconfiguration on startup. */
    public static void setClient(Client c) {
        client = c;
    }

    /** Test/teardown helper. */
    public static void clearClient() {
        client = null;
    }

    public static void record(String event) {
        record(event, Map.of());
    }

    public static void record(String event, Map<String, ?> fields) {
        try {
            Client c = client;
            if (c == null || event == null) return;
            RequestContext ctx = RequestContextHolder.current();
            if (ctx == null) return;
            c.recordCustomEvent(ctx, event, fields);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "appfirewall: record() failed", t);
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

    public static void record(
            String event,
            String k1, Object v1,
            String k2, Object v2,
            String k3, Object v3,
            String k4, Object v4
    ) {
        Map<String, Object> fields = new HashMap<>(8);
        fields.put(k1, v1);
        fields.put(k2, v2);
        fields.put(k3, v3);
        fields.put(k4, v4);
        record(event, fields);
    }
}
