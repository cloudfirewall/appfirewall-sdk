package io.appfirewall.core;

import io.appfirewall.core.breaker.CircuitBreaker;
import io.appfirewall.core.buffer.EventBuffer;
import io.appfirewall.core.buffer.Shipper;
import io.appfirewall.core.classifier.Classification;
import io.appfirewall.core.classifier.PathClassifier;
import io.appfirewall.core.config.ClientConfig;
import io.appfirewall.core.context.RequestContext;
import io.appfirewall.core.ip.CfMetadata;
import io.appfirewall.core.ip.CloudflareRangeRegistry;
import io.appfirewall.core.ip.IpResolver;
import io.appfirewall.core.ratelimit.RateLimiter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coordinator that owns the buffer, classifier, IP resolver, rate limiter,
 * circuit breaker, and shipper. Single instance per application.
 *
 * <p>Port of {@code python/appfirewall-fastapi/src/appfirewall_fastapi/_client.py}.
 * Deliberately thin: it doesn't <i>do</i> work, it delegates.
 */
public final class Client {

    private final ClientConfig config;
    private final CloudflareRangeRegistry cfRanges;
    private final IpResolver ipResolver;
    private final Map<String, RateLimiter> rateLimiters;
    private final EventBuffer buffer;
    private final CircuitBreaker breaker;
    private final Shipper shipper;

    public Client(
            ClientConfig config,
            CloudflareRangeRegistry cfRanges,
            IpResolver ipResolver,
            Map<String, RateLimiter> rateLimiters,
            EventBuffer buffer,
            CircuitBreaker breaker,
            Shipper shipper
    ) {
        this.config = config;
        this.cfRanges = cfRanges;
        this.ipResolver = ipResolver;
        this.rateLimiters = rateLimiters == null ? Map.of() : Map.copyOf(rateLimiters);
        this.buffer = buffer;
        this.breaker = breaker;
        this.shipper = shipper;
    }

    public ClientConfig config() { return config; }

    public IpResolver ipResolver() { return ipResolver; }

    public CfMetadata extractCfMetadata(Map<String, String> headers, String peer) {
        return ipResolver.extractCfMetadata(headers, peer);
    }

    public Classification classifyPath(String path) {
        return PathClassifier.classify(path);
    }

    /**
     * Feed the limiter for {@code classification} (e.g. {@code "scanner"}).
     * Returns {@code true} if the IP is over its limit and rate-limit
     * enforcement should kick in. {@code false} if there is no limiter for
     * that class or the IP is still under budget.
     */
    public boolean rateLimited(String ip, String classification) {
        RateLimiter rl = rateLimiters.get(classification);
        if (rl == null) return false;
        return rl.hit(ip);
    }

    public void recordHttpEvent(RequestContext ctx, Classification classification) {
        if (config.mode() == io.appfirewall.core.config.Mode.OFF) return;
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("event", "http");
        evt.put("ts_ms", System.currentTimeMillis());
        evt.put("method", ctx.method());
        evt.put("path", ctx.path());
        evt.put("status", ctx.status());
        if (ctx.ip() != null) evt.put("ip", ctx.ip());
        if (ctx.userAgent() != null) evt.put("user_agent", ctx.userAgent());
        if (classification != null) evt.put("classification", classification.wire());
        if (config.environment() != null) evt.put("environment", config.environment());
        if (!ctx.cfMetadata().isEmpty()) evt.put("cf", ctx.cfMetadata());
        if (!ctx.customFields().isEmpty()) evt.put("fields", ctx.customFields());
        buffer.emit(evt);
    }

    /**
     * Emit a custom event keyed off the active {@link RequestContext}, if
     * any. {@code ctx} may be {@code null} for sources outside a request
     * (where the upstream caller has already decided to silently no-op).
     */
    public void recordCustomEvent(RequestContext ctx, String name, Map<String, ?> fields) {
        if (config.mode() == io.appfirewall.core.config.Mode.OFF) return;
        if (ctx == null || name == null) return;
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("event", name);
        evt.put("ts_ms", System.currentTimeMillis());
        if (ctx.path() != null) evt.put("path", ctx.path());
        if (ctx.ip() != null) evt.put("ip", ctx.ip());
        if (config.environment() != null) evt.put("environment", config.environment());
        if (fields != null && !fields.isEmpty()) {
            evt.put("fields", new LinkedHashMap<>(fields));
        }
        buffer.emit(evt);
    }

    public void start() {
        if (shipper != null) shipper.start();
    }

    public void shutdown() {
        if (shipper != null) shipper.shutdown();
    }

    // Test/diagnostic accessors (package-private would be ideal but Spring
    // autoconfig in another module needs read access for the health indicator).
    public EventBuffer buffer() { return buffer; }
    public CircuitBreaker breaker() { return breaker; }
    public Shipper shipper() { return shipper; }
    public CloudflareRangeRegistry cfRanges() { return cfRanges; }
}
