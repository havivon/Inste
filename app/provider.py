from __future__ import annotations

from typing import Any

from .config import DEMO_MODE

_instance: Any = None


def get_provider() -> Any:
    """The single data source: real instagrapi client, or the offline demo."""
    global _instance
    if _instance is None:
        if DEMO_MODE:
            from .demo import DemoClient

            _instance = DemoClient()
        else:
            from .instagram import InstagramClient

            _instance = InstagramClient()
    return _instance
