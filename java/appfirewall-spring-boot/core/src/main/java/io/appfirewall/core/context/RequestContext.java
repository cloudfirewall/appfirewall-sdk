package io.appfirewall.core.context;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-request mutable state.
 *
 * <p>Mirrors {@code python/appfirewall-fastapi/src/appfirewall_fastapi/_context.py}.
 * One instance is created at the top of each HTTP request, populated by the
 * filter (servlet or reactive), made available to user code via the
 * {@code RequestContextHolder}, and read by the post-response hook to emit
 * the HTTP event.
 *
 * <p>Not thread-safe; intended to live inside a single request scope.
 */
public final class RequestContext {

    private final String method;
    private final String path;
    private final String ip;
    private final String userAgent;
    private final Map<String, String> cfMetadata;
    private final long startNanos;

    private int status;
    private final Map<String, Object> customFields = new HashMap<>();

    public RequestContext(
            String method,
            String path,
            String ip,
            String userAgent,
            Map<String, String> cfMetadata,
            long startNanos
    ) {
        this.method = Objects.requireNonNull(method, "method");
        this.path = Objects.requireNonNull(path, "path");
        this.ip = ip;  // may be null on degraded paths
        this.userAgent = userAgent;
        this.cfMetadata = cfMetadata == null ? Map.of() : cfMetadata;
        this.startNanos = startNanos;
    }

    public String method() { return method; }
    public String path() { return path; }
    public String ip() { return ip; }
    public String userAgent() { return userAgent; }
    public Map<String, String> cfMetadata() { return cfMetadata; }
    public long startNanos() { return startNanos; }

    public int status() { return status; }
    public void setStatus(int status) { this.status = status; }

    /** Custom fields attached by {@code AppFirewall.record(...)}. */
    public Map<String, Object> customFields() { return customFields; }
}
