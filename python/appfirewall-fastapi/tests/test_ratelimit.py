"""Tests for the sliding-window rate limiter."""

from __future__ import annotations

from unittest.mock import patch

import pytest

from appfirewall_fastapi._ratelimit import SlidingWindowLimiter


class TestBasicThreshold:
    def test_allows_up_to_limit(self) -> None:
        lim = SlidingWindowLimiter(max_events=3, window_seconds=60)
        assert lim.hit("1.1.1.1") is False
        assert lim.hit("1.1.1.1") is False
        assert lim.hit("1.1.1.1") is False

    def test_trips_on_fourth_hit(self) -> None:
        lim = SlidingWindowLimiter(max_events=3, window_seconds=60)
        for _ in range(3):
            lim.hit("1.1.1.1")
        assert lim.hit("1.1.1.1") is True

    def test_independent_ips_dont_interfere(self) -> None:
        lim = SlidingWindowLimiter(max_events=2, window_seconds=60)
        # IP A spends its budget.
        lim.hit("1.1.1.1")
        lim.hit("1.1.1.1")
        assert lim.hit("1.1.1.1") is True
        # IP B still has its budget.
        assert lim.hit("2.2.2.2") is False
        assert lim.hit("2.2.2.2") is False
        assert lim.hit("2.2.2.2") is True


class TestWindowBehavior:
    def test_expired_events_drop_out(self) -> None:
        # Use patch to control time without sleeping in tests.
        with patch("appfirewall_fastapi._ratelimit.time") as mock_time:
            now = 1000.0
            mock_time.monotonic.return_value = now

            lim = SlidingWindowLimiter(max_events=2, window_seconds=60)
            lim.hit("1.1.1.1")
            lim.hit("1.1.1.1")
            assert lim.hit("1.1.1.1") is True  # Third hit trips.

            # Advance past the window.
            mock_time.monotonic.return_value = now + 61

            # Now the limiter is fresh for this IP.
            assert lim.hit("1.1.1.1") is False

    def test_partial_window_expiry(self) -> None:
        with patch("appfirewall_fastapi._ratelimit.time") as mock_time:
            now = 1000.0
            mock_time.monotonic.return_value = now

            lim = SlidingWindowLimiter(max_events=3, window_seconds=60)
            lim.hit("1.1.1.1")  # t=1000

            mock_time.monotonic.return_value = now + 30
            lim.hit("1.1.1.1")  # t=1030
            lim.hit("1.1.1.1")  # t=1030

            mock_time.monotonic.return_value = now + 61
            # The t=1000 hit has expired but the t=1030 hits haven't.
            # Count should be 2; a new hit brings it to 3 (allowed), fourth trips.
            assert lim.hit("1.1.1.1") is False
            assert lim.hit("1.1.1.1") is True


class TestCurrentCount:
    def test_count_matches_hits(self) -> None:
        lim = SlidingWindowLimiter(max_events=10, window_seconds=60)
        for _ in range(5):
            lim.hit("1.1.1.1")
        assert lim.current_count("1.1.1.1") == 5
        assert lim.current_count("unknown") == 0


class TestValidation:
    def test_rejects_nonpositive_max(self) -> None:
        with pytest.raises(ValueError):
            SlidingWindowLimiter(max_events=0, window_seconds=60)

    def test_rejects_nonpositive_window(self) -> None:
        with pytest.raises(ValueError):
            SlidingWindowLimiter(max_events=10, window_seconds=0)


class TestEviction:
    def test_evicts_stale_ips_when_cap_hit(self) -> None:
        lim = SlidingWindowLimiter(max_events=10, window_seconds=1)
        lim._max_ips = 10  # Shrink cap for test.

        with patch("appfirewall_fastapi._ratelimit.time") as mock_time:
            mock_time.monotonic.return_value = 1000.0

            # Fill the limiter with 10 IPs.
            for i in range(10):
                lim.hit(f"1.0.0.{i}")
            assert len(lim._events) == 10

            # Advance past the window so those are all stale.
            mock_time.monotonic.return_value = 1002.0

            # One more IP triggers eviction of the stale entries.
            lim.hit("2.0.0.1")

            # After eviction, the 10 stale IPs are gone; only the new one remains.
            assert "2.0.0.1" in lim._events
            assert len(lim._events) < 10
