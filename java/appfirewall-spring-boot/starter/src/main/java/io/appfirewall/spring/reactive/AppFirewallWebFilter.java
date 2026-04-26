package io.appfirewall.spring.reactive;

/**
 * Reactive-stack {@code WebFilter} equivalent of the servlet filter.
 *
 * <p>TODO(v0.1): implement {@code WebFilter}, write the
 * {@code RequestContext} into Reactor's {@code Context} via
 * {@code chain.filter(exchange).contextWrite(...)}; restore on completion.
 *
 * <p>v0.1 caveat documented in spec §6.4: synchronous calls to
 * {@code AppFirewall.record()} from a {@code Mono}/{@code Flux} operator
 * will only resolve the context if the operator carries Reactor's
 * {@code Context}. Document; don't try to magic-thread it across
 * {@code flatMap} boundaries.
 */
public final class AppFirewallWebFilter {
    // TODO
}
