"""Per-IP sliding-window rate limiter.

Fixed purpose in v0.1: count ``scanner``-classified requests per IP over a
60-second window. When an IP crosses the threshold, its subsequent
scanner-class requests return 429 for the remainder of the window.

Benign-miss and unknown requests never feed this limiter. That's deliberate —
it means a real user hammering ``/favicon.ico`` can never trip 429, and a
legitimate tool crawling your site making lots of "unknown" requests won't
either. Only probe-pattern traffic burns budget.

This implementation is in-process (a dict). It's explicitly not correct for
multi-process or multi-worker deployments — a hot scanner IP hitting four
uvicorn workers will be checked against four independent counters. For v0.1
that's acceptable: the observation pipeline captures *all* scanner hits
regardless of which worker saw them, so the platform-level decisions
(block-at-edge, alert) are unaffected.

Distributed coordination (Redis / database) is a v0.2 concern.
"""

from __future__ import annotations

import time
from collections import deque
from threading import Lock


class SlidingWindowLimiter:
    """Fixed window size, fixed threshold.

    Thread-safe because the middleware can be called from multiple threads in
    some ASGI deployments (uvicorn with thread workers, or sync route handlers
    offloaded to a threadpool).
    """

    def __init__(self, max_events: int, window_seconds: float) -> None:
        if max_events <= 0:
            raise ValueError("max_events must be positive")
        if window_seconds <= 0:
            raise ValueError("window_seconds must be positive")
        self._max = max_events
        self._window = window_seconds
        # IP -> deque of event timestamps (monotonic seconds)
        self._events: dict[str, deque[float]] = {}
        self._lock = Lock()

        # Bound the number of tracked IPs to prevent memory blowup under a
        # distributed scan (millions of distinct IPs each hitting once).
        # When we hit the cap, we evict half of the oldest entries.
        self._max_ips = 50_000

    def hit(self, ip: str) -> bool:
        """Record an event for ``ip`` and return True if over the threshold.

        "Over the threshold" means this event brought the count in the window
        strictly above ``max_events``. The IP's first ``max_events`` events
        in the window are allowed; the (max_events + 1)th and beyond return
        True.
        """
        now = time.monotonic()
        cutoff = now - self._window

        with self._lock:
            dq = self._events.get(ip)
            if dq is None:
                if len(self._events) >= self._max_ips:
                    self._evict_locked()
                dq = deque()
                self._events[ip] = dq

            # Drop expired events from the left.
            while dq and dq[0] < cutoff:
                dq.popleft()

            dq.append(now)

            # If the deque is empty after popleft (shouldn't happen since we
            # just appended) or only has our event, we're fine. The limiter
            # trips when count exceeds max_events.
            return len(dq) > self._max

    def current_count(self, ip: str) -> int:
        """For introspection / tests. Returns the IP's count in the window."""
        now = time.monotonic()
        cutoff = now - self._window
        with self._lock:
            dq = self._events.get(ip)
            if dq is None:
                return 0
            while dq and dq[0] < cutoff:
                dq.popleft()
            return len(dq)

    def _evict_locked(self) -> None:
        """Called with ``self._lock`` held. Drops stale and oldest entries.

        First sweep: any IP whose entire deque is expired. Second sweep (if
        we're still over): drop the oldest half by last-event time.
        """
        cutoff = time.monotonic() - self._window

        # First, drop IPs whose deque is fully stale. Cheap and effective.
        stale = [ip for ip, dq in self._events.items() if not dq or dq[-1] < cutoff]
        for ip in stale:
            self._events.pop(ip, None)

        # If we're still above 80% of the cap, evict the oldest half by
        # last-event timestamp. This gives bursty-but-recent IPs room.
        if len(self._events) >= int(self._max_ips * 0.8):
            by_recency: list[tuple[float, str]] = [
                (dq[-1] if dq else 0.0, ip) for ip, dq in self._events.items()
            ]
            by_recency.sort()
            to_drop = len(by_recency) // 2
            for _, ip in by_recency[:to_drop]:
                self._events.pop(ip, None)
