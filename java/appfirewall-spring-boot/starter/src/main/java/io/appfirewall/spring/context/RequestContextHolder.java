package io.appfirewall.spring.context;

import io.appfirewall.core.context.RequestContext;

/**
 * Abstracts request-scope context lookup over servlet ({@link ThreadLocal})
 * and reactive (Reactor {@code Context}) stacks.
 *
 * <p>One implementation is selected at startup based on which stack is on
 * the classpath. See spec §6.4.
 *
 * <p>TODO(v0.1): wire {@code ServletRequestContextHolder} and
 * {@code ReactiveRequestContextHolder} as alternatives selected by the
 * autoconfiguration.
 */
public final class RequestContextHolder {

    private RequestContextHolder() {}

    /**
     * @return the active {@link RequestContext}, or {@code null} if not in a
     *         request scope. Never throws.
     */
    public static RequestContext current() {
        // TODO: delegate to the active strategy.
        return null;
    }

    /**
     * Bind a context to the current scope. Returned {@link AutoCloseable}
     * MUST be closed in a {@code finally} block.
     */
    public static AutoCloseable bind(RequestContext ctx) {
        // TODO: delegate to the active strategy.
        return () -> {};
    }
}
