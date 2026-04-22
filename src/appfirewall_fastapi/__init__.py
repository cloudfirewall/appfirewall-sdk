"""appfirewall-fastapi — origin-side abuse signal middleware for FastAPI.

Part of the AppFirewall platform by Sireto. See https://appfirewall.io.

Public surface::

    from appfirewall_fastapi import AppFirewallMiddleware, appfirewall

    app.add_middleware(AppFirewallMiddleware, api_key="afw_live_...")

    # Anywhere inside a request:
    appfirewall.record("upload.parse_failed", reason="bad json")
"""

from ._middleware import AppFirewallMiddleware
from ._shield import appfirewall

__version__ = "0.1.0"

__all__ = [
    "AppFirewallMiddleware",
    "appfirewall",
    "__version__",
]
