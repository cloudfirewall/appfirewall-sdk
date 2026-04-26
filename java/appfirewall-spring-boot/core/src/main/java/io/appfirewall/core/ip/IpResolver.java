package io.appfirewall.core.ip;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the real client IP, validating forwarded-for-style headers against
 * the socket peer.
 *
 * <p><b>Security-critical.</b> Port of
 * {@code python/appfirewall-fastapi/src/appfirewall_fastapi/_ip.py}.
 * Behaviour and test cases must stay in sync with the Python module.
 *
 * <p>Resolution rules (first match wins):
 * <ol>
 *   <li>{@code "cloudflare"} is in trusted proxies AND the peer is in the
 *       Cloudflare range set &rarr; trust {@code cf-connecting-ip}.</li>
 *   <li>The peer is in any user-supplied CIDR &rarr; trust the leftmost
 *       {@code X-Forwarded-For} entry, falling back to {@code X-Real-IP}.</li>
 *   <li>Otherwise &rarr; return the raw socket peer.</li>
 * </ol>
 *
 * <p>Headers are passed in as a {@code Map<String, String>} with
 * lowercase-normalized keys (the caller &mdash; servlet filter or web filter
 * &mdash; does this normalization).
 *
 * <p>This class is thread-safe and never throws.
 */
public final class IpResolver {

    private static final String FALLBACK_IP = "0.0.0.0";

    private final CloudflareRangeRegistry cfRanges;
    private final TrustedProxyConfig config;

    public IpResolver(CloudflareRangeRegistry cfRanges, TrustedProxyConfig config) {
        this.cfRanges = Objects.requireNonNull(cfRanges, "cfRanges");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Convenience overload that builds the config from a raw entry list. */
    public IpResolver(CloudflareRangeRegistry cfRanges, java.util.List<String> trustedProxies) {
        this(cfRanges, new TrustedProxyConfig(trustedProxies));
    }

    /**
     * @param headers lowercase-keyed headers from the request
     * @param peer    socket peer literal IP, or {@code null} when not available
     * @return the resolved client IP as a string. Never {@code null}.
     */
    public String resolveClientIp(Map<String, String> headers, String peer) {
        String peerIp = (peer == null || peer.isEmpty()) ? FALLBACK_IP : peer;

        // Rule 1: Cloudflare peer → trust cf-connecting-ip
        if (config.trustCloudflare() && cfRanges.isCloudflare(peerIp)) {
            String cfIp = lower(headers, "cf-connecting-ip");
            if (cfIp != null && IpLiteralParser.parse(cfIp).isPresent()) {
                return cfIp;
            }
        }

        // Rule 2: Generic trusted-proxy peer → trust XFF / X-Real-IP
        Optional<InetAddress> peerAddr = IpLiteralParser.parse(peerIp);
        if (peerAddr.isPresent() && config.peerMatchesGenericProxy(peerAddr.get())) {
            String xff = lower(headers, "x-forwarded-for");
            if (xff != null) {
                int comma = xff.indexOf(',');
                String first = (comma >= 0 ? xff.substring(0, comma) : xff).strip();
                if (!first.isEmpty() && IpLiteralParser.parse(first).isPresent()) {
                    return first;
                }
            }
            String realIp = lower(headers, "x-real-ip");
            if (realIp != null && IpLiteralParser.parse(realIp).isPresent()) {
                return realIp;
            }
        }

        // Rule 3: untrusted → raw peer
        return peerIp;
    }

    /**
     * Extract {@code (country, ray, asn)} from CF headers, but only when the
     * peer is in the CF range set and {@code "cloudflare"} is in trusted
     * proxies. Otherwise returns {@link CfMetadata#EMPTY}.
     */
    public CfMetadata extractCfMetadata(Map<String, String> headers, String peer) {
        if (!config.trustCloudflare()) {
            return CfMetadata.EMPTY;
        }
        String peerIp = (peer == null || peer.isEmpty()) ? FALLBACK_IP : peer;
        if (!cfRanges.isCloudflare(peerIp)) {
            return CfMetadata.EMPTY;
        }
        return new CfMetadata(
                lower(headers, "cf-ipcountry"),
                lower(headers, "cf-ray"),
                lower(headers, "cf-asn")
        );
    }

    /** Header lookup that tolerates already-lowercased and mixed-case maps. */
    private static String lower(Map<String, String> headers, String key) {
        if (headers == null) return null;
        String v = headers.get(key);
        if (v != null) return v;
        // Defensive: caller is supposed to lowercase, but if they didn't,
        // fall back to a case-insensitive scan. O(n) but n is small.
        String target = key.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(target)) {
                return e.getValue();
            }
        }
        return null;
    }
}
