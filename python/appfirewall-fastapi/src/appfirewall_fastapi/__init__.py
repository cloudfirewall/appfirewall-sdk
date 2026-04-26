"""appfirewall-fastapi — origin-side abuse signal middleware for FastAPI.

Part of the AppFirewall platform by Sireto. See https://appfirewall.io.

Public surface::

    from appfirewall_fastapi import AppFirewallMiddleware, appfirewall

    app.add_middleware(AppFirewallMiddleware, api_key="afw_live_...")

    # Anywhere inside a request:
    appfirewall.record("upload.parse_failed", reason="bad json")
"""

from importlib.metadata import PackageNotFoundError, version

from ._middleware import AppFirewallMiddleware
from ._shield import appfirewall

try:
    __version__ = version("appfirewall-fastapi")
except PackageNotFoundError:
    __version__ = "0.0.0+unknown"

__all__ = [
    "AppFirewallMiddleware",
    "appfirewall",
    "__version__",
]
