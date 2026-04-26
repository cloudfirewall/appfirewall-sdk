package io.appfirewall.core.ip;

/**
 * Cloudflare-supplied metadata extracted from request headers, only when the
 * peer is a real CF edge. Any field may be {@code null}.
 *
 * <p>Mirrors the {@code (country, ray, asn)} tuple returned by FastAPI's
 * {@code extract_cf_metadata}.
 */
public record CfMetadata(String country, String ray, String asn) {

    public static final CfMetadata EMPTY = new CfMetadata(null, null, null);

    public boolean isEmpty() {
        return country == null && ray == null && asn == null;
    }
}
