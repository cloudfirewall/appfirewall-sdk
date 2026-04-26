package io.appfirewall.core.ip;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Objects;
import java.util.Optional;

/**
 * IPv4 or IPv6 CIDR block.
 *
 * <p>Constructed from a string like {@code 10.0.0.0/8}, {@code 2001:db8::/32},
 * or a bare literal IP (treated as a {@code /32} or {@code /128}).
 * {@link #contains(InetAddress)} compares the first {@code prefixLength} bits
 * of the address &mdash; host bits in the network address are ignored, mirroring
 * Python's {@code ip_network(.., strict=False)} behaviour.
 */
record CidrBlock(byte[] network, int prefixLength, boolean ipv6) {

    /** Parse a CIDR or bare literal IP. Returns empty on any error. */
    static Optional<CidrBlock> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return Optional.empty();
        }
        int slash = s.indexOf('/');
        String addrPart = slash >= 0 ? s.substring(0, slash) : s;
        Optional<InetAddress> addr = IpLiteralParser.parse(addrPart);
        if (addr.isEmpty()) {
            return Optional.empty();
        }
        boolean ipv6 = addr.get() instanceof Inet6Address;
        int max = ipv6 ? 128 : 32;
        int prefix = max;
        if (slash >= 0) {
            String prefixStr = s.substring(slash + 1);
            try {
                prefix = Integer.parseInt(prefixStr);
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
            if (prefix < 0 || prefix > max) {
                return Optional.empty();
            }
        }
        return Optional.of(new CidrBlock(addr.get().getAddress(), prefix, ipv6));
    }

    boolean contains(InetAddress addr) {
        if (addr == null) {
            return false;
        }
        boolean addrV6 = addr instanceof Inet6Address;
        if (addrV6 != ipv6) {
            return false;
        }
        byte[] a = addr.getAddress();
        if (a.length != network.length) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (a[i] != network[i]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (a[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    // Records auto-generate equals/hashCode but byte[] uses identity, so override.
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CidrBlock other)) return false;
        return prefixLength == other.prefixLength
                && ipv6 == other.ipv6
                && java.util.Arrays.equals(network, other.network);
    }

    @Override
    public int hashCode() {
        return Objects.hash(java.util.Arrays.hashCode(network), prefixLength, ipv6);
    }
}
