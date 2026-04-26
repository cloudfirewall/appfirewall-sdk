package io.appfirewall.spring;

import io.appfirewall.core.Client;
import io.appfirewall.core.breaker.CircuitBreaker;
import io.appfirewall.core.buffer.EventBuffer;
import io.appfirewall.core.buffer.Shipper;
import io.appfirewall.core.config.ClientConfig;
import io.appfirewall.core.config.Mode;
import io.appfirewall.core.ip.CloudflareRangeRegistry;
import io.appfirewall.core.ip.IpResolver;
import io.appfirewall.core.ip.TrustedProxyConfig;
import io.appfirewall.core.ratelimit.RateLimiter;
import io.appfirewall.core.ratelimit.SlidingWindowLimiter;
import io.appfirewall.spring.context.RequestContextHolder;
import io.appfirewall.spring.context.ServletRequestContextHolderStrategy;
import io.appfirewall.spring.servlet.AppFirewallFilter;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Spring Boot autoconfiguration entry point.
 *
 * <p>Builds the {@link Client} from {@link AppFirewallProperties}, registers
 * the servlet filter when running on a servlet stack, and installs the
 * {@code AppFirewall} static facade and request-context strategy.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "appfirewall", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AppFirewallProperties.class)
public class AppFirewallAutoConfiguration {

    private static final Logger LOG = Logger.getLogger("appfirewall");

    @Bean(destroyMethod = "shutdown")
    public Client appFirewallClient(AppFirewallProperties props) {
        Mode mode = props.getMode() == null ? Mode.SHIP : props.getMode();
        String apiKey = props.getApiKey();

        // Degrade gracefully: in SHIP mode without a key, switch to OFF and
        // log a single warning. Mirrors FastAPI's behaviour.
        if (mode == Mode.SHIP && (apiKey == null || apiKey.isBlank())) {
            LOG.warning("appfirewall: no API key provided "
                    + "(set appfirewall.api-key or APPFIREWALL_API_KEY). "
                    + "Disabling event shipping.");
            mode = Mode.OFF;
            apiKey = "";
        }

        ClientConfig.OnError onError = mapOnError(props.getOnError());
        Map<String, ClientConfig.RateLimitWindow> windows = mapRateLimits(props.getRateLimit());

        ClientConfig config = new ClientConfig(
                apiKey == null ? "" : apiKey,
                props.getEndpoint(),
                props.getEnvironment(),
                mode,
                props.getLocalLogPath(),
                props.getTrustedProxies() == null ? List.of() : List.copyOf(props.getTrustedProxies()),
                props.isClassify404(),
                windows,
                props.isEnforceRateLimit(),
                onError
        );

        CloudflareRangeRegistry cfRanges = new CloudflareRangeRegistry();
        IpResolver ipResolver = new IpResolver(
                cfRanges,
                new TrustedProxyConfig(config.trustedProxies())
        );

        Map<String, RateLimiter> limiters = new LinkedHashMap<>();
        for (Map.Entry<String, ClientConfig.RateLimitWindow> e : windows.entrySet()) {
            ClientConfig.RateLimitWindow w = e.getValue();
            limiters.put(e.getKey(), new SlidingWindowLimiter(w.max(), w.windowSeconds()));
        }

        EventBuffer buffer = new EventBuffer();
        CircuitBreaker breaker = new CircuitBreaker(5, 30_000);
        Path logPath = (config.localLogPath() == null) ? null : Path.of(config.localLogPath());
        Shipper shipper = new Shipper(
                buffer, breaker, mode,
                config.endpoint(), config.apiKey(), logPath
        );

        Client client = new Client(config, cfRanges, ipResolver, limiters, buffer, breaker, shipper);
        client.start();
        return client;
    }

    @Bean
    public AppFirewallStaticInit appFirewallStaticInit(Client client) {
        return new AppFirewallStaticInit(client);
    }

    private static ClientConfig.OnError mapOnError(AppFirewallProperties.OnError v) {
        if (v == null) return ClientConfig.OnError.IGNORE;
        return switch (v) {
            case IGNORE -> ClientConfig.OnError.IGNORE;
            case WARN -> ClientConfig.OnError.WARN;
            case RAISE -> ClientConfig.OnError.RAISE;
        };
    }

    private static Map<String, ClientConfig.RateLimitWindow> mapRateLimits(
            Map<String, AppFirewallProperties.RateLimit> in
    ) {
        Map<String, ClientConfig.RateLimitWindow> out = new LinkedHashMap<>();
        if (in != null) {
            for (Map.Entry<String, AppFirewallProperties.RateLimit> e : in.entrySet()) {
                AppFirewallProperties.RateLimit r = e.getValue();
                out.put(e.getKey(), new ClientConfig.RateLimitWindow(r.getMax(), r.getWindowSeconds()));
            }
        }
        return out;
    }

    /**
     * Owns the static facade and holder-strategy lifecycle. A separate bean
     * so its initialization runs after the {@link Client} is fully constructed.
     */
    public static final class AppFirewallStaticInit implements InitializingBean, DisposableBean {
        private final Client client;

        AppFirewallStaticInit(Client client) {
            this.client = client;
        }

        @Override
        public void afterPropertiesSet() {
            AppFirewall.setClient(client);
            // Default to the servlet strategy. If the reactive web filter
            // is registered later, it will swap the strategy.
            RequestContextHolder.setStrategy(new ServletRequestContextHolderStrategy());
        }

        @Override
        public void destroy() {
            AppFirewall.clearClient();
            RequestContextHolder.clearStrategy();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({jakarta.servlet.Filter.class,
            org.springframework.web.filter.OncePerRequestFilter.class})
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public static class ServletConfig {

        @Bean
        public AppFirewallFilter appFirewallFilter(Client client) {
            return new AppFirewallFilter(client);
        }

        @Bean
        public FilterRegistrationBean<AppFirewallFilter> appFirewallFilterRegistration(
                AppFirewallFilter filter
        ) {
            FilterRegistrationBean<AppFirewallFilter> reg = new FilterRegistrationBean<>(filter);
            reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
            reg.addUrlPatterns("/*");
            return reg;
        }
    }
}
