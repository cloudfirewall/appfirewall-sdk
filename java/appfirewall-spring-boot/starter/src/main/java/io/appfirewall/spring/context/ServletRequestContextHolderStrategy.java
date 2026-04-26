package io.appfirewall.spring.context;

import io.appfirewall.core.context.RequestContext;

/**
 * Servlet-stack strategy. Backed by a {@link ThreadLocal}.
 *
 * <p>Memory-leak-critical: the filter MUST clear the holder in a
 * {@code finally} block. Spring's threadpool reuses worker threads, so a
 * leaked binding would leak the previous request's headers and IP into a
 * later, unrelated request.
 */
public final class ServletRequestContextHolderStrategy implements RequestContextHolderStrategy {

    private static final ThreadLocal<RequestContext> CTX = new ThreadLocal<>();

    @Override
    public RequestContext current() {
        return CTX.get();
    }

    @Override
    public AutoCloseable bind(RequestContext ctx) {
        RequestContext previous = CTX.get();
        CTX.set(ctx);
        return () -> {
            if (previous == null) {
                CTX.remove();
            } else {
                CTX.set(previous);
            }
        };
    }
}
