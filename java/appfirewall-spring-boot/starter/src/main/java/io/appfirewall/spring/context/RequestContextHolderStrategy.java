package io.appfirewall.spring.context;

import io.appfirewall.core.context.RequestContext;

/**
 * Strategy for binding and looking up a {@link RequestContext} for the
 * current request scope. One implementation per stack (servlet
 * {@link ThreadLocal}, reactive Reactor {@code Context}).
 */
public interface RequestContextHolderStrategy {

    /** @return the active context, or {@code null} when no request is in scope. */
    RequestContext current();

    /**
     * Bind {@code ctx} to the current scope. The returned token MUST be
     * closed in a {@code finally} block so the scope clears even on
     * exceptional control flow.
     */
    AutoCloseable bind(RequestContext ctx);
}
