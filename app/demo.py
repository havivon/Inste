from __future__ import annotations

import hashlib
import random
from datetime import datetime, timedelta, timezone
from typing import Any

PROFILES = {
    "demo.cook": ("מתכונים ב-60 שניות", 184_000, 312),
    "demo.travel": ("יומן מסעות", 51_300, 198),
    "demo.diy": ("סדנת עץ ביתית", 9_420, 87),
}

TOPICS = [
    "שלוש טעויות שכולם עושים",
    "הטריק ששינה לי את השבוע",
    "לפני ואחרי",
    "עניתי לכם בתגובות",
    "הכלי הכי שימושי שקניתי",
    "סיור מאחורי הקלעים",
    "התחלה חדשה",
    "מה שלא מספרים לכם",
    "יום בחיי",
    "שאלתם - עניתי",
]


class DemoClient:
    """Offline stand-in for InstagramClient.

    Lets the whole UI - filters, sorting, watch tracking, stats - be driven
    without touching a real account.
    """

    is_logged_in = True
    username = "demo"

    def load_session(self) -> bool:
        return True

    def login(self, username: str, password: str, **_: Any) -> str:
        return "demo"

    def logout(self) -> None:
        return None

    def fetch_profile(self, username: str) -> dict[str, Any]:
        name = username.strip().lstrip("@").lower()
        full_name, followers, media_count = PROFILES.get(
            name, ("חשבון הדגמה", 12_000, 64)
        )
        return {
            "pk": _stable_id(name),
            "username": name,
            "full_name": full_name,
            "biography": "נתוני הדגמה מקומיים - לא נמשכו מאינסטגרם.",
            "is_private": False,
            "is_verified": followers > 100_000,
            "follower_count": followers,
            "media_count": media_count,
        }

    def fetch_reels(self, account_pk: str, amount: int) -> list[dict[str, Any]]:
        rng = random.Random(f"{account_pk}")
        now = datetime.now(timezone.utc)
        reels = []
        for index in range(amount):
            taken = now - timedelta(days=index * rng.uniform(1.5, 6.0))
            views = int(rng.lognormvariate(10.2, 1.25))
            like_rate = rng.uniform(0.02, 0.14)
            likes = int(views * like_rate)
            reels.append(
                {
                    "pk": _stable_id(f"{account_pk}:{index}"),
                    "code": f"DEMO{index:04d}",
                    "taken_at": taken.isoformat(),
                    "caption": f"{rng.choice(TOPICS)} #demo #reels",
                    "play_count": views,
                    "like_count": likes,
                    "comment_count": int(likes * rng.uniform(0.01, 0.09)),
                    "duration": round(rng.uniform(7, 88), 1),
                    "thumbnail_url": "",
                    "video_url": "",
                    "music_title": rng.choice(["Original audio", "Summer Loop", None]),
                    "music_artist": None,
                    "location_name": rng.choice([None, "תל אביב", "ירושלים"]),
                }
            )
        return reels

    def refresh_media(self, reel_pk: str) -> dict[str, Any]:
        raise RuntimeError("מצב הדגמה: אין וידאו אמיתי להשמעה.")


def _stable_id(seed: str) -> str:
    return str(int(hashlib.sha1(seed.encode()).hexdigest()[:12], 16))


def thumbnail_svg(reel_pk: str, caption: str) -> str:
    rng = random.Random(reel_pk)
    hue = rng.randint(0, 359)
    label = (caption or "").split("#")[0].strip()[:28] or "Demo reel"
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="360" height="640" '
        'viewBox="0 0 360 640" role="img">'
        f'<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">'
        f'<stop offset="0" stop-color="hsl({hue} 55% 32%)"/>'
        f'<stop offset="1" stop-color="hsl({(hue + 48) % 360} 60% 16%)"/>'
        "</linearGradient></defs>"
        '<rect width="360" height="640" fill="url(#g)"/>'
        '<text x="180" y="320" text-anchor="middle" fill="#ffffff" '
        'font-family="system-ui, sans-serif" font-size="20">'
        f"{_escape(label)}</text>"
        '<text x="180" y="352" text-anchor="middle" fill="rgba(255,255,255,0.6)" '
        'font-family="system-ui, sans-serif" font-size="13">DEMO</text>'
        "</svg>"
    )


def _escape(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )
