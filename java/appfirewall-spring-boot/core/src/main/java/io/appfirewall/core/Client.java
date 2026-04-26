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

import java.util.Map;

/**
 * Coordinator that owns the buffer, classifier, IP resolver, rate limiter,
 * circuit breaker, and shipper. Single instance per application.
 *
 * <p>Port of {@code python/appfirewall-fastapi/src/appfirewall_fastapi/_client.py}.
 * Deliberately thin: it doesn't <i>do</i> work, it delegates.
 *
 * <p>TODO(v0.1): wire the subsystems together once each is implemented.
 */
public final class Client {

    private final ClientConfig config;
    private final CloudflareRangeRegistry cfRanges;
    private final IpResolver ipResolver;
    private final RateLimiter rateLimiter;
    private final EventBuffer buffer;
    private final CircuitBreaker breaker;
    private final Shipper shipper;

    public Client(
            ClientConfig config,
            CloudflareRangeRegistry cfRanges,
            IpResolver ipResolver,
            RateLimiter rateLimiter,
            EventBuffer buffer,
            CircuitBreaker breaker,
            Shipper shipper
    ) {
        this.config = config;
        this.cfRanges = cfRanges;
        this.ipResolver = ipResolver;
        this.rateLimiter = rateLimiter;
        this.buffer = buffer;
        this.breaker = breaker;
        this.shipper = shipper;
    }

    public ClientConfig config() { return config; }

    public IpResolver ipResolver() { return ipResolver; }

    public CfMetadata extractCfMetadata(java.util.Map<String, String> headers, String peer) {
        return ipResolver.extractCfMetadata(headers, peer);
    }

    public Classification classifyPath(String path) {
        return PathClassifier.classify(path);
    }

    public void recordHttpEvent(RequestContext ctx, Classification classification) {
        // TODO: build event map from ctx + classification, tag with environment,
        // emit to buffer.
        throw new UnsupportedOperationException("v0.1 TODO");
    }

    public void recordCustomEvent(String name, Map<String, Object> fields) {
        // TODO: read RequestContext from holder, merge fields, emit to buffer.
        throw new UnsupportedOperationException("v0.1 TODO");
    }

    public void shutdown() {
        // TODO: shipper.shutdown() with 5-second grace.
    }
}
