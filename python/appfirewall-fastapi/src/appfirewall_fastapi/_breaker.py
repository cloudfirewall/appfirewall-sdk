"""A small three-state circuit breaker for the shipper.

States:
  - CLOSED:    normal operation, ``allow()`` returns True.
  - OPEN:      recent failures exceeded threshold; ``allow()`` returns False
               until ``reset_after`` seconds have passed.
  - HALF_OPEN: after the cooldown, we let exactly one call through; if it
               succeeds we go back to CLOSED, if it fails we reset the timer.

The breaker does not do retries or timing itself — callers observe the outcome
of their own calls and feed ``record_success`` / ``record_failure`` back in.
"""

from __future__ import annotations

import time
from enum import Enum
from threading import Lock


class BreakerState(Enum):
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


class CircuitBreaker:
    def __init__(self, fail_threshold: int = 5, reset_after: float = 30.0) -> None:
        if fail_threshold <= 0:
            raise ValueError("fail_threshold must be positive")
        if reset_after <= 0:
            raise ValueError("reset_after must be positive")
        self._threshold = fail_threshold
        self._reset_after = reset_after
        self._state = BreakerState.CLOSED
        self._consecutive_failures = 0
        self._opened_at: float = 0.0
        self._lock = Lock()

    def allow(self) -> bool:
        """Return True if a call should be attempted. Advances state as needed."""
        with self._lock:
            if self._state is BreakerState.CLOSED:
                return True
            if self._state is BreakerState.OPEN:
                if time.monotonic() - self._opened_at >= self._reset_after:
                    self._state = BreakerState.HALF_OPEN
                    return True
                return False
            # HALF_OPEN: only one probe call is allowed. The first caller that
            # reads this state has already been given permission; subsequent
            # concurrent callers see OPEN until success/failure is recorded.
            # To keep this simple (and since the shipper is single-task), we
            # treat HALF_OPEN like OPEN for the second caller: returning True
            # once is the caller's job to coordinate via record_*.
            return False

    def record_success(self) -> None:
        with self._lock:
            self._state = BreakerState.CLOSED
            self._consecutive_failures = 0

    def record_failure(self) -> None:
        with self._lock:
            self._consecutive_failures += 1
            if self._consecutive_failures >= self._threshold:
                if self._state is not BreakerState.OPEN:
                    self._state = BreakerState.OPEN
                    self._opened_at = time.monotonic()

    @property
    def state(self) -> BreakerState:
        return self._state
