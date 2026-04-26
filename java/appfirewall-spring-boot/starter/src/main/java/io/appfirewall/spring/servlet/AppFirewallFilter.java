package io.appfirewall.spring.servlet;

/**
 * Servlet-stack ASGI-equivalent filter. Mirrors the request lifecycle from
 * {@code python/appfirewall-fastapi/src/appfirewall_fastapi/_middleware.py}.
 *
 * <p>TODO(v0.1): extend {@code OncePerRequestFilter}, do:
 * <ol>
 *   <li>Build {@code RequestContext} from {@code HttpServletRequest}.</li>
 *   <li>Bind via {@link io.appfirewall.spring.context.RequestContextHolder}.</li>
 *   <li>Wrap response so we can capture the final status.</li>
 *   <li>Call {@code chain.doFilter}; do not swallow inner exceptions.</li>
 *   <li>Post-response: classify if 404, emit HTTP event.</li>
 *   <li>Always clear the {@link ThreadLocal} in {@code finally}
 *       (memory-leak-critical).</li>
 * </ol>
 *
 * <p>Fail-open: any {@link Throwable} from our code is caught and logged at
 * DEBUG; the filter chain continues. Only the customer's own exceptions
 * propagate.
 */
public final class AppFirewallFilter {
    // TODO
}
