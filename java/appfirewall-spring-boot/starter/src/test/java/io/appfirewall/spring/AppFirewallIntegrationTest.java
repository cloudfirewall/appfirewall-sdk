package io.appfirewall.spring;

import io.appfirewall.core.Client;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the autoconfig. Starts a real Spring Boot servlet app
 * with the AppFirewall starter wired into {@code Mode.LOCAL}, drives
 * traffic, and asserts the JSONL output.
 */
@SpringBootTest(
        classes = AppFirewallIntegrationTest.TestApp.class,
        webEnvironment = WebEnvironment.RANDOM_PORT
)
class AppFirewallIntegrationTest {

    @TempDir
    static Path tmp;
    static Path logFile;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        logFile = tmp.resolve("events.jsonl");
        registry.add("appfirewall.mode", () -> "LOCAL");
        registry.add("appfirewall.local-log-path", () -> logFile.toString());
        registry.add("appfirewall.environment", () -> "test");
        registry.add("appfirewall.trusted-proxies", () -> "127.0.0.1/32");
    }

    @Autowired TestRestTemplate http;
    @Autowired Client client;

    @Test
    void httpEventsAreShippedToLocalLog() throws Exception {
        http.getForEntity("/healthz", String.class);
        http.getForEntity("/items/42", String.class);
        http.getForEntity("/wp-admin", String.class);     // 404 → scanner
        http.getForEntity("/favicon.ico", String.class);  // 404 → benign-miss
        http.getForEntity("/checkout", String.class);     // emits custom event

        // Wait for the shipper to flush. BATCH_MAX_AGE is 2s, so 4s is safe.
        long deadline = System.currentTimeMillis() + 4_000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(logFile)) {
                long lineCount = Files.lines(logFile).count();
                if (lineCount >= 6) break;  // 5 HTTP + 1 custom
            }
            Thread.sleep(100);
        }

        List<String> lines = Files.readAllLines(logFile);
        String all = String.join("\n", lines);

        assertThat(lines).hasSizeGreaterThanOrEqualTo(6);
        assertThat(all).contains("\"path\":\"/healthz\"")
                .contains("\"status\":200");
        assertThat(all).contains("\"path\":\"/items/42\"");
        assertThat(all).contains("\"path\":\"/wp-admin\"")
                .contains("\"classification\":\"scanner\"");
        assertThat(all).contains("\"path\":\"/favicon.ico\"")
                .contains("\"classification\":\"benign-miss\"");
        assertThat(all).contains("\"event\":\"checkout.completed\"");
        assertThat(all).contains("\"environment\":\"test\"");
    }

    @SpringBootApplication
    static class TestApp {
        public static void main(String[] args) {
            SpringApplication.run(TestApp.class, args);
        }

        @RestController
        static class TestController {
            @GetMapping("/healthz")
            String healthz() { return "ok"; }

            @GetMapping("/items/{id}")
            String item(@PathVariable int id) { return "item-" + id; }

            @GetMapping("/checkout")
            String checkout() {
                AppFirewall.record("checkout.completed", "amount_cents", 1234);
                return "ok";
            }
        }
    }
}
