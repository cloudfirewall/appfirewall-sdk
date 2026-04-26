"""Client IP resolution.

The security-critical function in this module is ``resolve_client_ip``, which
must never trust a forwarded-for-style header unless the socket peer is a
trusted proxy. If we trusted ``cf-connecting-ip`` unconditionally, any external
client could spoof their source IP by sending that header.

Rules (first match wins):
  1. If ``trusted_proxies`` includes the literal ``"cloudflare"`` AND the peer
     is in Cloudflare's published IP ranges: trust ``cf-connecting-ip`` and the
     companion CF headers.
  2. If ``trusted_proxies`` includes a CIDR that contains the peer: trust the
     first entry of ``x-forwarded-for`` (falling back to ``x-real-ip``).
  3. Otherwise: return the raw socket peer IP. Headers are ignored.

In local dev (no proxy), the middleware will set ``trusted_proxies=[]`` which
makes the function degrade to returning the raw peer, which is what you want.
"""

from __future__ import annotations

from collections.abc import Sequence
from ipaddress import IPv4Network, IPv6Network, ip_address, ip_network
from typing import Union

from ._cf_ranges import CloudflareRanges

Network = Union[IPv4Network, IPv6Network]

_FALLBACK_IP = "0.0.0.0"


class TrustedProxyConfig:
    """Parses the user's ``trusted_proxies`` option once and holds the result.

    Accepts a list where each item is either:
      - The literal string ``"cloudflare"`` (special-cased, uses CF ranges)
      - A CIDR string like ``"10.0.0.0/8"`` or ``"2001:db8::/32"``
      - A bare IP like ``"192.168.1.5"`` (treated as a /32 or /128)

    Invalid entries are silently dropped at parse time and a warning is logged
    by the caller. We never raise from here because bad config shouldn't kill
    request processing — the worst case is we fail-closed to "no headers
    trusted", which is the safer default.
    """

    def __init__(self, entries: Sequence[str]) -> None:
        self.trust_cloudflare: bool = False
        self.cidrs: list[Network] = []
        self.invalid: list[str] = []

        for raw in entries:
            e = raw.strip().lower()
            if not e:
                continue
            if e == "cloudflare":
                self.trust_cloudflare = True
                continue
            parsed = _try_parse_network(raw.strip())
            if parsed is None:
                self.invalid.append(raw)
            else:
                self.cidrs.append(parsed)

    def peer_matches_generic_proxy(self, peer: str) -> bool:
        """Return True if ``peer`` is in any of the user-supplied CIDRs."""
        try:
            addr = ip_address(peer)
        except (ValueError, TypeError):
            return False
        return any(addr in net for net in self.cidrs)


def resolve_client_ip(
    *,
    headers: dict[str, str],
    peer: str | None,
    config: TrustedProxyConfig,
    cf_ranges: CloudflareRanges,
) -> str:
    """Return the resolved client IP as a string. Never raises.

    ``headers`` must be a case-insensitive-keyed mapping (the caller normalizes
    header keys to lowercase before calling).
    """
    peer_ip = peer or _FALLBACK_IP

    # Rule 1: Cloudflare peer → trust cf-connecting-ip
    if config.trust_cloudflare and cf_ranges.is_cloudflare(peer_ip):
        cf_ip = headers.get("cf-connecting-ip")
        if cf_ip and _is_valid_ip(cf_ip):
            return cf_ip

    # Rule 2: Generic trusted-proxy peer → trust XFF/Real-IP
    if config.peer_matches_generic_proxy(peer_ip):
        xff = headers.get("x-forwarded-for")
        if xff:
            # XFF can be comma-separated; the leftmost entry is the original
            # client (as added by the first trusted proxy).
            first = xff.split(",", 1)[0].strip()
            if first and _is_valid_ip(first):
                return first
        real_ip = headers.get("x-real-ip")
        if real_ip and _is_valid_ip(real_ip):
            return real_ip

    # Rule 3: untrusted → raw peer
    return peer_ip


def extract_cf_metadata(
    *,
    headers: dict[str, str],
    peer: str | None,
    config: TrustedProxyConfig,
    cf_ranges: CloudflareRanges,
) -> tuple[str | None, str | None, str | None]:
    """Extract (country, ray, asn) from CF headers — only if the peer is CF.

    Returns a triple of None if the request didn't come through a Cloudflare
    edge we trust. This mirrors the IP-resolution rule: we don't consume any
    CF-specific header unless the transport-level peer validates.
    """
    if not config.trust_cloudflare:
        return (None, None, None)
    peer_ip = peer or _FALLBACK_IP
    if not cf_ranges.is_cloudflare(peer_ip):
        return (None, None, None)
    return (
        headers.get("cf-ipcountry"),
        headers.get("cf-ray"),
        headers.get("cf-asn"),
    )


def _try_parse_network(raw: str) -> Network | None:
    """Parse a CIDR or bare IP. Returns None on failure."""
    try:
        return ip_network(raw, strict=False)
    except (ValueError, TypeError):
        return None


def _is_valid_ip(ip_str: str) -> bool:
    try:
        ip_address(ip_str)
        return True
    except (ValueError, TypeError):
        return False
