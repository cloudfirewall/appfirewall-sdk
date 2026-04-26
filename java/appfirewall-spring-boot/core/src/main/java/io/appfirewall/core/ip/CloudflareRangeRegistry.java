package io.appfirewall.core.ip;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

/**
 * Cloudflare IP range registry.
 *
 * <p>Port of {@code python/appfirewall-fastapi/src/appfirewall_fastapi/_cf_ranges.py}.
 * Ships a baked snapshot of Cloudflare's published IPv4 and IPv6 ranges so the
 * SDK works offline and without warm-up. The 24-hour background refresh from
 * {@code https://api.cloudflare.com/client/v4/ips} is a v0.1 TODO &mdash;
 * once implemented, refreshes that fail must be silent (the baked snapshot
 * keeps serving).
 *
 * <p>{@link #isCloudflare(InetAddress)} is the hot-path entry point. The CF
 * list has roughly 22 ranges, so a linear scan is fine; no trie needed.
 *
 * <p>Snapshot date: 2026-04-22 (matches the FastAPI baked snapshot).
 */
public final class CloudflareRangeRegistry {

    private static final List<String> BAKED_V4 = List.of(
            "173.245.48.0/20",
            "103.21.244.0/22",
            "103.22.200.0/22",
            "103.31.4.0/22",
            "141.101.64.0/18",
            "108.162.192.0/18",
            "190.93.240.0/20",
            "188.114.96.0/20",
            "197.234.240.0/22",
            "198.41.128.0/17",
            "162.158.0.0/15",
            "104.16.0.0/13",
            "104.24.0.0/14",
            "172.64.0.0/13",
            "131.0.72.0/22"
    );

    private static final List<String> BAKED_V6 = List.of(
            "2400:cb00::/32",
            "2606:4700::/32",
            "2803:f800::/32",
            "2405:b500::/32",
            "2405:8100::/32",
            "2a06:98c0::/29",
            "2c0f:f248::/32"
    );

    private volatile List<CidrBlock> v4;
    private volatile List<CidrBlock> v6;

    public CloudflareRangeRegistry() {
        this.v4 = parseAll(BAKED_V4);
        this.v6 = parseAll(BAKED_V6);
    }

    /**
     * Return {@code true} if the given address is within a published CF range.
     * Never throws.
     */
    public boolean isCloudflare(InetAddress addr) {
        if (addr == null) {
            return false;
        }
        List<CidrBlock> blocks = (addr instanceof Inet6Address) ? v6 : v4;
        for (CidrBlock b : blocks) {
            if (b.contains(addr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * String overload for callers that haven't parsed the literal yet.
     * Returns {@code false} for malformed input (deliberately lenient: this
     * sits on the hot path and a parse error in a header must not break the
     * request).
     */
    public boolean isCloudflare(String ipLiteral) {
        Optional<InetAddress> addr = IpLiteralParser.parse(ipLiteral);
        return addr.isPresent() && isCloudflare(addr.get());
    }

    private static List<CidrBlock> parseAll(List<String> cidrs) {
        return cidrs.stream()
                .map(CidrBlock::parse)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }
}
