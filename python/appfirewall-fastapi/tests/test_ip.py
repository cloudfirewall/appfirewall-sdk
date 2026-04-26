"""Tests for IP resolution.

The core security property is: ``cf-connecting-ip`` and ``x-forwarded-for``
are only trusted when the socket peer is an allowed proxy. Spoofed headers
from untrusted peers must be ignored.
"""

from __future__ import annotations

import pytest

from appfirewall_fastapi._cf_ranges import CloudflareRanges
from appfirewall_fastapi._ip import (
    TrustedProxyConfig,
    extract_cf_metadata,
    resolve_client_ip,
)


@pytest.fixture
def cf_ranges() -> CloudflareRanges:
    # Use the baked-in list. No network needed.
    return CloudflareRanges()


@pytest.fixture
def cf_config() -> TrustedProxyConfig:
    return TrustedProxyConfig(["cloudflare"])


@pytest.fixture
def none_config() -> TrustedProxyConfig:
    return TrustedProxyConfig([])


# A real CF IP from the baked-in list. 104.16.0.0/13 → any 104.16.*.* works.
CF_PEER = "104.16.0.1"
NON_CF_PEER = "8.8.8.8"
SPOOFED_CLIENT_IP = "1.2.3.4"


class TestCloudflareTrust:
    def test_cf_peer_with_header_is_trusted(
        self, cf_ranges: CloudflareRanges, cf_config: TrustedProxyConfig
    ) -> None:
        ip = resolve_client_ip(
            headers={"cf-connecting-ip": SPOOFED_CLIENT_IP},
            peer=CF_PEER,
            config=cf_config,
            cf_ranges=cf_ranges,
        )
        assert ip == SPOOFED_CLIENT_IP

    def test_non_cf_peer_header_is_ignored(
        self, cf_ranges: CloudflareRanges, cf_config: TrustedProxyConfig
    ) -> None:
        # This is the spoofing-protection test. A non-CF peer sending
        # cf-connecting-ip MUST NOT be trusted.
        ip = resolve_client_ip(
            headers={"cf-connecting-ip": SPOOFED_CLIENT_IP},
            peer=NON_CF_PEER,
            config=cf_config,
            cf_ranges=cf_ranges,
        )
        assert ip == NON_CF_PEER

    def test_cf_not_in_trusted_ignores_header(
        self, cf_ranges: CloudflareRanges, none_config: TrustedProxyConfig
    ) -> None:
        # If "cloudflare" isn't in trusted_proxies, we should not look at
        # cf-connecting-ip even from a real CF peer.
        ip = resolve_client_ip(
            headers={"cf-connecting-ip": SPOOFED_CLIENT_IP},
            peer=CF_PEER,
            config=none_config,
            cf_ranges=cf_ranges,
        )
        assert ip == CF_PEER

    def test_invalid_cf_header_falls_back_to_peer(
        self, cf_ranges: CloudflareRanges, cf_config: TrustedProxyConfig
    ) -> None:
        ip = resolve_client_ip(
            headers={"cf-connecting-ip": "not-an-ip"},
            peer=CF_PEER,
            config=cf_config,
            cf_ranges=cf_ranges,
        )
        assert ip == CF_PEER


class TestGenericProxy:
    def test_trusted_cidr_uses_xff(self, cf_ranges: CloudflareRanges) -> None:
        config = TrustedProxyConfig(["10.0.0.0/8"])
        ip = resolve_client_ip(
            headers={"x-forwarded-for": f"{SPOOFED_CLIENT_IP}, 10.0.0.5"},
            peer="10.0.0.5",
            config=config,
            cf_ranges=cf_ranges,
        )
        assert ip == SPOOFED_CLIENT_IP

    def test_xff_takes_leftmost(self, cf_ranges: CloudflareRanges) -> None:
        config = TrustedProxyConfig(["10.0.0.0/8"])
        ip = resolve_client_ip(
            headers={"x-forwarded-for": "198.51.100.1, 10.0.0.5, 10.0.0.9"},
            peer="10.0.0.5",
            config=config,
            cf_ranges=cf_ranges,
        )
        assert ip == "198.51.100.1"

    def test_real_ip_fallback(self, cf_ranges: CloudflareRanges) -> None:
        config = TrustedProxyConfig(["10.0.0.0/8"])
        ip = resolve_client_ip(
            headers={"x-real-ip": SPOOFED_CLIENT_IP},
            peer="10.0.0.5",
            config=config,
            cf_ranges=cf_ranges,
        )
        assert ip == SPOOFED_CLIENT_IP

    def test_untrusted_peer_ignores_xff(self, cf_ranges: CloudflareRanges) -> None:
        config = TrustedProxyConfig(["10.0.0.0/8"])
        ip = resolve_client_ip(
            headers={"x-forwarded-for": SPOOFED_CLIENT_IP},
            peer=NON_CF_PEER,
            config=config,
            cf_ranges=cf_ranges,
        )
        assert ip == NON_CF_PEER


class TestCloudflareMetadata:
    def test_cf_metadata_extracted_for_cf_peer(
        self, cf_ranges: CloudflareRanges, cf_config: TrustedProxyConfig
    ) -> None:
        country, ray, asn = extract_cf_metadata(
            headers={
                "cf-ipcountry": "US",
                "cf-ray": "abc123-DFW",
                "cf-asn": "13335",
            },
            peer=CF_PEER,
            config=cf_config,
            cf_ranges=cf_ranges,
        )
        assert country == "US"
        assert ray == "abc123-DFW"
        assert asn == "13335"

    def test_cf_metadata_refused_for_non_cf_peer(
        self, cf_ranges: CloudflareRanges, cf_config: TrustedProxyConfig
    ) -> None:
        # Non-CF peer sending fake CF headers → nothing trusted.
        country, ray, asn = extract_cf_metadata(
            headers={
                "cf-ipcountry": "XX",
                "cf-ray": "fake",
                "cf-asn": "666",
            },
            peer=NON_CF_PEER,
            config=cf_config,
            cf_ranges=cf_ranges,
        )
        assert country is None
        assert ray is None
        assert asn is None


class TestConfigParsing:
    def test_invalid_entries_collected_not_raised(self) -> None:
        config = TrustedProxyConfig([
            "10.0.0.0/8",
            "not-a-cidr",
            "cloudflare",
            "",
            "999.999.999.999/32",
        ])
        assert config.trust_cloudflare is True
        assert len(config.cidrs) == 1
        assert "not-a-cidr" in config.invalid
        assert "999.999.999.999/32" in config.invalid

    def test_bare_ip_treated_as_single_host(self, cf_ranges: CloudflareRanges) -> None:
        config = TrustedProxyConfig(["192.168.1.5"])
        ip = resolve_client_ip(
            headers={"x-real-ip": SPOOFED_CLIENT_IP},
            peer="192.168.1.5",
            config=config,
            cf_ranges=cf_ranges,
        )
        assert ip == SPOOFED_CLIENT_IP


class TestEdgeCases:
    def test_no_peer_falls_back_gracefully(
        self, cf_ranges: CloudflareRanges, cf_config: TrustedProxyConfig
    ) -> None:
        ip = resolve_client_ip(
            headers={},
            peer=None,
            config=cf_config,
            cf_ranges=cf_ranges,
        )
        assert ip == "0.0.0.0"

    def test_ipv6_cf_peer(
        self, cf_ranges: CloudflareRanges, cf_config: TrustedProxyConfig
    ) -> None:
        # 2606:4700::/32 is Cloudflare.
        ip = resolve_client_ip(
            headers={"cf-connecting-ip": SPOOFED_CLIENT_IP},
            peer="2606:4700:10::1",
            config=cf_config,
            cf_ranges=cf_ranges,
        )
        assert ip == SPOOFED_CLIENT_IP
