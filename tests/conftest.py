"""Shared pytest fixtures."""

from __future__ import annotations

import logging
from collections.abc import Iterator

import pytest


@pytest.fixture(autouse=True)
def _quiet_appfirewall_logger(caplog: pytest.LogCaptureFixture) -> Iterator[None]:
    """Silence the appfirewall logger during tests unless explicitly captured."""
    logging.getLogger("appfirewall").setLevel(logging.CRITICAL)
    yield
