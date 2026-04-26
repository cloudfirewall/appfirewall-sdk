package io.appfirewall.spring;

/**
 * Spring Boot autoconfiguration entry point.
 *
 * <p>TODO(v0.1): once Spring is on the runtime classpath, annotate with:
 * <pre>{@code
 * @AutoConfiguration
 * @ConditionalOnProperty(prefix = "appfirewall", name = "enabled",
 *                        havingValue = "true", matchIfMissing = true)
 * @EnableConfigurationProperties(AppFirewallProperties.class)
 * }</pre>
 *
 * <p>Beans to expose (per spec §7):
 * <ul>
 *   <li>{@code Client appFirewallClient(AppFirewallProperties)}</li>
 *   <li>{@code AppFirewallFilter} (servlet, conditional on
 *       {@code WebApplicationType.SERVLET})</li>
 *   <li>{@code AppFirewallWebFilter} (reactive, conditional on
 *       {@code WebApplicationType.REACTIVE})</li>
 *   <li>{@code AppFirewallHealthIndicator} (conditional on
 *       {@code HealthIndicator} on classpath)</li>
 *   <li>{@code AppFirewallMetrics} (conditional on {@code MeterRegistry}
 *       on classpath)</li>
 * </ul>
 *
 * <p>Filter ordering: {@code Ordered.HIGHEST_PRECEDENCE + 100}, so we run
 * before the customer's filters but after Spring Security's logging filter.
 *
 * <p>The autoconfiguration also calls
 * {@code AppFirewall.setClient(client)} in a {@code @PostConstruct} so the
 * static facade resolves to the active client.
 */
public class AppFirewallAutoConfiguration {
    // intentionally empty stub
}
