from __future__ import annotations

from urllib.parse import urlparse

import httpx

from .config import ALLOWED_MEDIA_HOST_SUFFIXES, BROWSER_UA, THUMB_DIR, ensure_dirs


def is_allowed_media_url(url: str) -> bool:
    if not url:
        return False
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname:
        return False
    host = parsed.hostname.lower()
    return any(
        host == suffix.lstrip(".") or host.endswith(suffix)
        for suffix in ALLOWED_MEDIA_HOST_SUFFIXES
    )


def thumb_path(reel_pk: str):
    return THUMB_DIR / f"{reel_pk}.jpg"


def download_thumbnail(reel_pk: str, url: str) -> bool:
    """Cache a reel's cover locally so the grid keeps working after the signed
    CDN URL expires."""
    if not is_allowed_media_url(url):
        return False
    target = thumb_path(reel_pk)
    if target.exists() and target.stat().st_size > 0:
        return True
    ensure_dirs()
    try:
        with httpx.Client(timeout=20, follow_redirects=True) as client:
            response = client.get(url, headers={"User-Agent": BROWSER_UA})
            response.raise_for_status()
            target.write_bytes(response.content)
        return True
    except Exception:
        target.unlink(missing_ok=True)
        return False
