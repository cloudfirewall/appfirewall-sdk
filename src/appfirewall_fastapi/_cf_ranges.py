"""Cloudflare IP range registry.

The package ships a baked-in snapshot of Cloudflare's published IP ranges, which
is used immediately at startup so the SDK works offline and without warm-up. A
background task (started by the client on first use) refreshes the list from
``https://api.cloudflare.com/client/v4/ips`` every 24 hours.

If the refresh fails, the baked-in list keeps working — the SDK must never block
or fail-closed because of a network issue fetching ranges.

Snapshot source: https://www.cloudflare.com/ips/
Last refreshed: 2026-04-22
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import Iterable
from ipaddress import IPv4Network, IPv6Network, ip_address, ip_network
from typing import TYPE_CHECKING, Union

if TYPE_CHECKING:
    import httpx

# Published by Cloudflare; stable keys, add/remove is rare.
# This is a point-in-time snapshot. The background refresh keeps them current.
_BAKED_V4: tuple[str, ...] = (
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
    "131.0.72.0/22",
)

_BAKED_V6: tuple[str, ...] = (
    "2400:cb00::/32",
    "2606:4700::/32",
    "2803:f800::/32",
    "2405:b500::/32",
    "2405:8100::/32",
    "2a06:98c0::/29",
    "2c0f:f248::/32",
)

_REFRESH_INTERVAL_SEC = 24 * 60 * 60  # 24 hours
_CF_IPS_URL = "https://api.cloudflare.com/client/v4/ips"

Network = Union[IPv4Network, IPv6Network]


class CloudflareRanges:
    """Holds the current CF IP range list; thread-safe reads, async refresh writes.

    Reads are frequent (one per request) and must be fast, so the parsed network
    objects are kept in a list and iterated linearly. The CF list is small
    (roughly 22 ranges), so this is fine — no need for a fancy trie.

    The refresh task is started lazily by the client on first use and runs
    until the shutdown hook cancels it.
    """

    def __init__(self) -> None:
        # ip_network returns a union type, so we build the lists with
        # explicit narrowing. Every entry in _BAKED_V4 is a valid IPv4 CIDR
        # and every entry in _BAKED_V6 is a valid IPv6 CIDR.
        self._v4: list[IPv4Network] = [IPv4Network(r) for r in _BAKED_V4]
        self._v6: list[IPv6Network] = [IPv6Network(r) for r in _BAKED_V6]
        self._last_refresh: float = 0.0
        self._refresh_task: asyncio.Task[None] | None = None

    def is_cloudflare(self, ip_str: str) -> bool:
        """Return True if the given IP literal is within a Cloudflare range.

        Never raises — a malformed IP is treated as "not Cloudflare". This is
        deliberately lenient because this function sits on the hot request path
        and a parse error in a header shouldn't be able to break a request.
        """
        try:
            addr = ip_address(ip_str)
        except (ValueError, TypeError):
            return False
        if addr.version == 6:
            return any(addr in net for net in self._v6)
        return any(addr in net for net in self._v4)

    async def start_refresh(
        self, http_client: httpx.AsyncClient | None = None
    ) -> None:
        """Start the background refresh task. Idempotent."""
        if self._refresh_task is not None and not self._refresh_task.done():
            return
        self._refresh_task = asyncio.create_task(
            self._refresh_loop(http_client), name="appfirewall-cf-refresh"
        )

    async def stop_refresh(self) -> None:
        """Cancel the background task, if running."""
        task = self._refresh_task
        if task is None:
            return
        task.cancel()
        try:
            await task
        except (asyncio.CancelledError, Exception):  # noqa: BLE001
            pass
        self._refresh_task = None

    async def _refresh_loop(
        self, http_client: httpx.AsyncClient | None
    ) -> None:
        # One attempt soon after startup (to pick up any ranges added since
        # release), then every _REFRESH_INTERVAL_SEC.
        await asyncio.sleep(5.0)
        while True:
            try:
                await self._refresh_once(http_client)
            except Exception:  # noqa: BLE001
                # Never let a refresh failure kill the task or surface to user
                # code. The baked-in list keeps serving.
                pass
            await asyncio.sleep(_REFRESH_INTERVAL_SEC)

    async def _refresh_once(
        self, http_client: httpx.AsyncClient | None
    ) -> None:
        # Lazy-import httpx; the caller may have already created a client and
        # passed it in (preferred, reuses connection pool).
        if http_client is None:
            import httpx

            async with httpx.AsyncClient(timeout=5.0) as client:
                resp = await client.get(_CF_IPS_URL)
        else:
            resp = await http_client.get(_CF_IPS_URL, timeout=5.0)

        resp.raise_for_status()
        payload = resp.json()
        result = payload.get("result", {})
        v4 = result.get("ipv4_cidrs", [])
        v6 = result.get("ipv6_cidrs", [])

        # Only replace if we got something that looks valid. If CF returns an
        # empty list due to an API hiccup, keep the existing ranges.
        if v4 and v6:
            parsed_v4 = _safe_parse_networks(v4, 4)
            parsed_v6 = _safe_parse_networks(v6, 6)
            if parsed_v4 and parsed_v6:
                self._v4 = parsed_v4  # type: ignore[assignment]
                self._v6 = parsed_v6  # type: ignore[assignment]
                self._last_refresh = time.time()


def _safe_parse_networks(raw: Iterable[str], version: int) -> list[Network]:
    """Parse a list of CIDR strings, dropping any that fail to parse."""
    out: list[Network] = []
    for cidr in raw:
        try:
            net = ip_network(cidr)
        except (ValueError, TypeError):
            continue
        if net.version == version:
            out.append(net)
    return out
