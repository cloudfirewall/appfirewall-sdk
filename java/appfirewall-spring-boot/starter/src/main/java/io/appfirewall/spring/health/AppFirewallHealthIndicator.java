package io.appfirewall.spring.health;

/**
 * Spring Boot Actuator health indicator. Status is {@code UP} when the
 * shipper's circuit breaker is CLOSED or HALF_OPEN, {@code OUT_OF_SERVICE}
 * when OPEN. <b>Never {@code DOWN}</b> &mdash; our outage must not flip the
 * customer's pod (spec §4.3).
 *
 * <p>TODO(v0.1): implement {@code HealthIndicator} once Spring Boot Actuator
 * is on the classpath.
 */
public final class AppFirewallHealthIndicator {
    // TODO
}
