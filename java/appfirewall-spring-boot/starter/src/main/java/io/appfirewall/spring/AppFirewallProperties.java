package io.appfirewall.spring;

import io.appfirewall.core.config.Mode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration bound to {@code appfirewall.*}. Defaults match the FastAPI
 * SDK exactly; see spec §4.1.
 */
@ConfigurationProperties("appfirewall")
public class AppFirewallProperties {

    /** Enable/disable the entire SDK. Defaults to enabled. */
    private boolean enabled = true;

    private String apiKey;
    private String endpoint = "https://ingest.appfirewall.io/v1/events";
    private String environment;
    private Mode mode = Mode.SHIP;
    private String localLogPath;
    private List<String> trustedProxies = List.of("cloudflare");
    private boolean classify404 = true;
    private Map<String, RateLimit> rateLimit = defaultRateLimit();
    private boolean enforceRateLimit = false;
    private OnError onError = OnError.IGNORE;

    private static Map<String, RateLimit> defaultRateLimit() {
        Map<String, RateLimit> m = new LinkedHashMap<>();
        m.put("scanner", new RateLimit(10, 60.0));
        return m;
    }

    public static final class RateLimit {
        private int max;
        private double windowSeconds;

        public RateLimit() {}
        public RateLimit(int max, double windowSeconds) {
            this.max = max;
            this.windowSeconds = windowSeconds;
        }
        public int getMax() { return max; }
        public void setMax(int max) { this.max = max; }
        public double getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(double windowSeconds) { this.windowSeconds = windowSeconds; }
    }

    public enum OnError { IGNORE, WARN, RAISE }

    // ----- getters/setters -----------------------------------------------
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public String getLocalLogPath() { return localLogPath; }
    public void setLocalLogPath(String localLogPath) { this.localLogPath = localLogPath; }
    public List<String> getTrustedProxies() { return trustedProxies; }
    public void setTrustedProxies(List<String> trustedProxies) { this.trustedProxies = trustedProxies; }
    public boolean isClassify404() { return classify404; }
    public void setClassify404(boolean classify404) { this.classify404 = classify404; }
    public Map<String, RateLimit> getRateLimit() { return rateLimit; }
    public void setRateLimit(Map<String, RateLimit> rateLimit) { this.rateLimit = rateLimit; }
    public boolean isEnforceRateLimit() { return enforceRateLimit; }
    public void setEnforceRateLimit(boolean enforceRateLimit) { this.enforceRateLimit = enforceRateLimit; }
    public OnError getOnError() { return onError; }
    public void setOnError(OnError onError) { this.onError = onError; }
}
