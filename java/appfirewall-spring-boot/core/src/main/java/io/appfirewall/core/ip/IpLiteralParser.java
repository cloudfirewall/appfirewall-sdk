package io.appfirewall.core.ip;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pure-literal IP parser. Refuses anything that would trigger DNS resolution
 * &mdash; we are on the request hot path and {@link InetAddress#getByName}
 * with a non-literal string will block on the resolver.
 *
 * <p>Package-private; only callers inside this package are expected to use it.
 */
final class IpLiteralParser {

    private IpLiteralParser() {}

    // Strict dotted-quad: each octet 0-255.
    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)"
                    + "(\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)){3}$"
    );

    // Loose v6 character set; final shape validation deferred to InetAddress.
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");

    /**
     * Parse an IP literal. Returns empty for {@code null}, empty, or anything
     * that isn't a numeric literal. Never triggers DNS.
     */
    static Optional<InetAddress> parse(String s) {
        if (s == null || s.isEmpty()) {
            return Optional.empty();
        }
        boolean v4 = IPV4.matcher(s).matches();
        boolean v6 = !v4 && s.indexOf(':') >= 0 && IPV6_CHARS.matcher(s).matches();
        if (!v4 && !v6) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(s));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }
}
