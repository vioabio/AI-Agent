from __future__ import annotations

import warnings

try:
    from authlib.deprecate import AuthlibDeprecationWarning
except Exception:  # pragma: no cover
    AuthlibDeprecationWarning = Warning

warnings.filterwarnings(
    "ignore",
    category=AuthlibDeprecationWarning,
    module=r"cozepy\.auth\.__init__",
)
