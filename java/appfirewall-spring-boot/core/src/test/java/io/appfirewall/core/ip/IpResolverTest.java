package io.appfirewall.core.ip;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirrors {@code python/appfirewall-fastapi/tests/test_ip.py}.
 *
 * <p>The core security property under test: {@code cf-connecting-ip} and
 * {@code x-forwarded-for} are only trusted when the socket peer is an
 * allowed proxy. Spoofed headers from untrusted peers must be ignored.
 */
class IpResolverTest {

    // A real CF IP from the baked-in list. 104.16.0.0/13 → any 104.16.*.* works.
    private static final String CF_PEER = "104.16.0.1";
    private static final String NON_CF_PEER = "8.8.8.8";
    private static final String SPOOFED_CLIENT_IP = "1.2.3.4";

    private final CloudflareRangeRegistry cfRanges = new CloudflareRangeRegistry();

    private IpResolver resolver(String... entries) {
        return new IpResolver(cfRanges, new TrustedProxyConfig(List.of(entries)));
    }

    @Nested
    class CloudflareTrust {

        @Test
        void cfPeerWithHeaderIsTrusted() {
            String ip = resolver("cloudflare").resolveClientIp(
                    Map.of("cf-connecting-ip", SPOOFED_CLIENT_IP),
                    CF_PEER
            );
            assertEquals(SPOOFED_CLIENT_IP, ip);
        }

        @Test
        void nonCfPeerHeaderIsIgnored() {
            // Spoofing-protection: a non-CF peer sending cf-connecting-ip
            // MUST NOT be trusted.
            String ip = resolver("cloudflare").resolveClientIp(
                    Map.of("cf-connecting-ip", SPOOFED_CLIENT_IP),
                    NON_CF_PEER
            );
            assertEquals(NON_CF_PEER, ip);
        }

        @Test
        void cfNotInTrustedIgnoresHeader() {
            // If "cloudflare" isn't in trusted_proxies, we should not look
            // at cf-connecting-ip even from a real CF peer.
            String ip = resolver().resolveClientIp(
                    Map.of("cf-connecting-ip", SPOOFED_CLIENT_IP),
                    CF_PEER
            );
            assertEquals(CF_PEER, ip);
        }

        @Test
        void invalidCfHeaderFallsBackToPeer() {
            String ip = resolver("cloudflare").resolveClientIp(
                    Map.of("cf-connecting-ip", "not-an-ip"),
                    CF_PEER
            );
            assertEquals(CF_PEER, ip);
        }
    }

    @Nested
    class GenericProxy {

        @Test
        void trustedCidrUsesXff() {
            String ip = resolver("10.0.0.0/8").resolveClientIp(
                    Map.of("x-forwarded-for", SPOOFED_CLIENT_IP + ", 10.0.0.5"),
                    "10.0.0.5"
            );
            assertEquals(SPOOFED_CLIENT_IP, ip);
        }

        @Test
        void xffTakesLeftmost() {
            String ip = resolver("10.0.0.0/8").resolveClientIp(
                    Map.of("x-forwarded-for", "198.51.100.1, 10.0.0.5, 10.0.0.9"),
                    "10.0.0.5"
            );
            assertEquals("198.51.100.1", ip);
        }

        @Test
        void realIpFallback() {
            String ip = resolver("10.0.0.0/8").resolveClientIp(
                    Map.of("x-real-ip", SPOOFED_CLIENT_IP),
                    "10.0.0.5"
            );
            assertEquals(SPOOFED_CLIENT_IP, ip);
        }

        @Test
        void untrustedPeerIgnoresXff() {
            String ip = resolver("10.0.0.0/8").resolveClientIp(
                    Map.of("x-forwarded-for", SPOOFED_CLIENT_IP),
                    NON_CF_PEER
            );
            assertEquals(NON_CF_PEER, ip);
        }
    }

    @Nested
    class CloudflareMetadata {

        @Test
        void cfMetadataExtractedForCfPeer() {
            CfMetadata md = resolver("cloudflare").extractCfMetadata(
                    Map.of(
                            "cf-ipcountry", "US",
                            "cf-ray", "abc123-DFW",
                            "cf-asn", "13335"
                    ),
                    CF_PEER
            );
            assertEquals("US", md.country());
            assertEquals("abc123-DFW", md.ray());
            assertEquals("13335", md.asn());
        }

        @Test
        void cfMetadataRefusedForNonCfPeer() {
            // Non-CF peer sending fake CF headers → nothing trusted.
            CfMetadata md = resolver("cloudflare").extractCfMetadata(
                    Map.of(
                            "cf-ipcountry", "XX",
                            "cf-ray", "fake",
                            "cf-asn", "666"
                    ),
                    NON_CF_PEER
            );
            assertNull(md.country());
            assertNull(md.ray());
            assertNull(md.asn());
            assertTrue(md.isEmpty());
        }
    }

    @Nested
    class ConfigParsing {

        @Test
        void invalidEntriesCollectedNotRaised() {
            TrustedProxyConfig config = new TrustedProxyConfig(List.of(
                    "10.0.0.0/8",
                    "not-a-cidr",
                    "cloudflare",
                    "",
                    "999.999.999.999/32"
            ));
            assertTrue(config.trustCloudflare());
            assertTrue(config.invalid().contains("not-a-cidr"));
            assertTrue(config.invalid().contains("999.999.999.999/32"));
        }

        @Test
        void bareIpTreatedAsSingleHost() {
            String ip = resolver("192.168.1.5").resolveClientIp(
                    Map.of("x-real-ip", SPOOFED_CLIENT_IP),
                    "192.168.1.5"
            );
            assertEquals(SPOOFED_CLIENT_IP, ip);
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void noPeerFallsBackGracefully() {
            String ip = resolver("cloudflare").resolveClientIp(
                    Map.of(),
                    null
            );
            assertEquals("0.0.0.0", ip);
        }

        @Test
        void ipv6CfPeer() {
            // 2606:4700::/32 is Cloudflare.
            String ip = resolver("cloudflare").resolveClientIp(
                    Map.of("cf-connecting-ip", SPOOFED_CLIENT_IP),
                    "2606:4700:10::1"
            );
            assertEquals(SPOOFED_CLIENT_IP, ip);
        }
    }
}
