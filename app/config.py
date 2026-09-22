from __future__ import annotations

import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = Path(os.environ.get("REELS_LAB_DATA") or BASE_DIR / "data")
THUMB_DIR = DATA_DIR / "thumbs"
DB_PATH = DATA_DIR / "reels.db"
SESSION_PATH = DATA_DIR / "ig_session.json"

DEMO_MODE = os.environ.get("REELS_LAB_DEMO") == "1"

HOST = os.environ.get("REELS_LAB_HOST", "127.0.0.1")
PORT = int(os.environ.get("REELS_LAB_PORT", "8777"))

# Pacing. instagrapi sleeps a random amount inside this range between private
# API calls; the defaults are deliberately slow so a session looks like a person
# scrolling rather than a scraper.
DELAY_MIN = float(os.environ.get("REELS_LAB_DELAY_MIN", "2"))
DELAY_MAX = float(os.environ.get("REELS_LAB_DELAY_MAX", "6"))

DEFAULT_FETCH_AMOUNT = int(os.environ.get("REELS_LAB_FETCH_AMOUNT", "24"))
MAX_FETCH_AMOUNT = int(os.environ.get("REELS_LAB_MAX_FETCH_AMOUNT", "150"))

# Instagram media URLs are signed and expire; anything older than this is
# re-resolved through the API before we try to stream it.
MEDIA_URL_TTL_SECONDS = int(os.environ.get("REELS_LAB_MEDIA_TTL", str(60 * 60 * 3)))

BROWSER_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
)

# Only Instagram's own CDN may be fetched by the media proxy, so a poisoned URL
# in the database can never turn the proxy into a general-purpose SSRF gadget.
ALLOWED_MEDIA_HOST_SUFFIXES = (".cdninstagram.com", ".fbcdn.net", ".instagram.com")


def ensure_dirs() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    THUMB_DIR.mkdir(parents=True, exist_ok=True)
