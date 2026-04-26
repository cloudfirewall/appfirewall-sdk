package io.appfirewall.spring.context;

import io.appfirewall.core.context.RequestContext;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Static facade that delegates to the active
 * {@link RequestContextHolderStrategy}. The autoconfiguration installs a
 * strategy at startup based on which Spring stack is on the classpath.
 *
 * <p>If no strategy is installed (e.g. the consumer wired the SDK manually
 * without going through Spring autoconfiguration), {@link #current()}
 * returns {@code null} and {@link #bind(RequestContext)} returns a no-op
 * token. {@code AppFirewall.record(...)} treats that as "outside a request
 * scope" and silently no-ops.
 */
public final class RequestContextHolder {

    private static final AutoCloseable NOOP_TOKEN = () -> {};
    private static final AtomicReference<RequestContextHolderStrategy> STRATEGY = new AtomicReference<>();

    private RequestContextHolder() {}

    public static void setStrategy(RequestContextHolderStrategy strategy) {
        STRATEGY.set(strategy);
    }

    /** Test/teardown helper. */
    public static void clearStrategy() {
        STRATEGY.set(null);
    }

    public static RequestContext current() {
        RequestContextHolderStrategy s = STRATEGY.get();
        return s == null ? null : s.current();
    }

    public static AutoCloseable bind(RequestContext ctx) {
        RequestContextHolderStrategy s = STRATEGY.get();
        return s == null ? NOOP_TOKEN : s.bind(ctx);
    }
}
