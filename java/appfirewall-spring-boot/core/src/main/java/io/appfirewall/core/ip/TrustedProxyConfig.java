package io.appfirewall.core.ip;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Parses the user's {@code trustedProxies} option once and holds the result.
 *
 * <p>Mirrors {@code TrustedProxyConfig} in
 * {@code python/appfirewall-fastapi/src/appfirewall_fastapi/_ip.py}.
 *
 * <p>Each entry may be:
 * <ul>
 *   <li>The literal string {@code "cloudflare"} (special-cased; uses
 *       {@link CloudflareRangeRegistry}).</li>
 *   <li>A CIDR like {@code "10.0.0.0/8"} or {@code "2001:db8::/32"}.</li>
 *   <li>A bare IP literal (treated as a single host: {@code /32} or {@code /128}).</li>
 * </ul>
 *
 * <p>Invalid entries are <b>silently dropped</b> at parse time and collected
 * in {@link #invalid()} for the caller to log. We never raise: bad config
 * shouldn't kill request processing &mdash; the worst case is fail-closed to
 * "no headers trusted", which is the safer default.
 */
public final class TrustedProxyConfig {

    private final boolean trustCloudflare;
    private final List<CidrBlock> cidrs;
    private final List<String> invalid;

    public TrustedProxyConfig(List<String> entries) {
        boolean trustCf = false;
        List<CidrBlock> blocks = new ArrayList<>();
        List<String> bad = new ArrayList<>();

        if (entries != null) {
            for (String raw : entries) {
                if (raw == null) continue;
                String stripped = raw.strip();
                if (stripped.isEmpty()) continue;
                if (stripped.toLowerCase(java.util.Locale.ROOT).equals("cloudflare")) {
                    trustCf = true;
                    continue;
                }
                Optional<CidrBlock> parsed = CidrBlock.parse(stripped);
                if (parsed.isPresent()) {
                    blocks.add(parsed.get());
                } else {
                    bad.add(raw);
                }
            }
        }

        this.trustCloudflare = trustCf;
        this.cidrs = Collections.unmodifiableList(blocks);
        this.invalid = Collections.unmodifiableList(bad);
    }

    public boolean trustCloudflare() {
        return trustCloudflare;
    }

    public List<String> invalid() {
        return invalid;
    }

    /** Return {@code true} if {@code peer} is in any of the user-supplied CIDRs. */
    public boolean peerMatchesGenericProxy(InetAddress peer) {
        if (peer == null) {
            return false;
        }
        for (CidrBlock b : cidrs) {
            if (b.contains(peer)) {
                return true;
            }
        }
        return false;
    }

    /** Convenience overload taking a literal IP string. */
    public boolean peerMatchesGenericProxy(String peerLiteral) {
        Optional<InetAddress> addr = IpLiteralParser.parse(peerLiteral);
        return addr.isPresent() && peerMatchesGenericProxy(addr.get());
    }
}
