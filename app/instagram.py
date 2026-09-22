from __future__ import annotations

import threading
from typing import Any

from .config import DATA_DIR, DELAY_MAX, DELAY_MIN, SESSION_PATH, ensure_dirs

# The session file holds no readable username, so the handle we log in with is
# kept beside it purely so the UI can say who is connected.
USERNAME_PATH = DATA_DIR / "ig_username.txt"


def _read_saved_username() -> str | None:
    try:
        return USERNAME_PATH.read_text(encoding="utf-8").strip() or None
    except OSError:
        return None


def _write_saved_username(username: str) -> None:
    try:
        ensure_dirs()
        USERNAME_PATH.write_text(username, encoding="utf-8")
    except OSError:
        pass


class InstagramError(Exception):
    """Anything the UI should show the user verbatim."""


class NotLoggedIn(InstagramError):
    pass


class TwoFactorNeeded(InstagramError):
    pass


class ChallengeNeeded(InstagramError):
    pass


def _clips_music(media: Any) -> tuple[str | None, str | None]:
    try:
        meta = media.clips_metadata or {}
        info = (meta.get("music_info") or {}).get("music_asset_info") or {}
        title = info.get("title") or (meta.get("original_sound_info") or {}).get(
            "original_audio_title"
        )
        artist = info.get("display_artist")
        return (title or None, artist or None)
    except Exception:
        return (None, None)


def media_to_reel(media: Any) -> dict[str, Any]:
    """instagrapi Media -> the flat shape the database and API speak."""
    views = getattr(media, "play_count", None) or getattr(media, "view_count", None)
    location = getattr(media, "location", None)
    music_title, music_artist = _clips_music(media)
    taken_at = getattr(media, "taken_at", None)
    return {
        "pk": str(media.pk),
        "code": getattr(media, "code", None),
        "taken_at": taken_at.isoformat() if taken_at else None,
        "caption": getattr(media, "caption_text", "") or "",
        "play_count": int(views or 0),
        "like_count": int(getattr(media, "like_count", 0) or 0),
        "comment_count": int(getattr(media, "comment_count", 0) or 0),
        "duration": float(getattr(media, "video_duration", 0) or 0),
        "thumbnail_url": str(getattr(media, "thumbnail_url", "") or ""),
        "video_url": str(getattr(media, "video_url", "") or ""),
        "music_title": music_title,
        "music_artist": music_artist,
        "location_name": getattr(location, "name", None) if location else None,
    }


class InstagramClient:
    """Serialized wrapper around instagrapi.

    Every private-API call goes through one lock: two browser tabs hammering
    the API in parallel is exactly the traffic shape that gets an account
    flagged, so requests queue instead.
    """

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._client: Any = None
        self._username: str | None = None
        self._challenge_code: str | None = None
        self._loaded = False

    # -- session -----------------------------------------------------------
    def _build(self) -> Any:
        from instagrapi import Client  # imported lazily: demo mode needs no instagrapi

        client = Client()
        client.delay_range = [DELAY_MIN, DELAY_MAX]
        client.challenge_code_handler = self._challenge_handler
        return client

    def _challenge_handler(self, username: str, choice: Any) -> str:
        code = self._challenge_code
        self._challenge_code = None
        if not code:
            raise ChallengeNeeded(
                "אינסטגרם ביקשה אימות נוסף. בדוק את המייל/SMS של החשבון "
                "והתחבר שוב עם הקוד בשדה 'קוד אימות'."
            )
        return code

    def _restore_settings(self, client: Any) -> None:
        client.load_settings(SESSION_PATH)
        if not getattr(client, "bloks_versioning_id", None):
            # A session file written by an older app profile can't drive the
            # CAA login flow; fall back to the bundled known-good profile.
            client.load_settings(SESSION_PATH, override_app_version=True)

    def load_session(self) -> bool:
        """Restore a previous login from disk. Returns True if usable."""
        with self._lock:
            if self._loaded:
                return self._client is not None
            self._loaded = True
            if not SESSION_PATH.exists():
                return False
            try:
                client = self._build()
                self._restore_settings(client)
                client.get_timeline_feed()  # cheap call that proves the session is alive
                self._client = client
                self._username = client.username or _read_saved_username()
                return True
            except Exception:
                self._client = None
                return False

    def login(
        self,
        username: str,
        password: str,
        verification_code: str | None = None,
        challenge_code: str | None = None,
    ) -> str:
        from instagrapi.exceptions import TwoFactorRequired

        with self._lock:
            ensure_dirs()
            client = self._build()
            if SESSION_PATH.exists():
                # Reusing the previous device fingerprint keeps the login from
                # looking like a brand-new device every time; instagrapi
                # revalidates the stored session and only re-authenticates if
                # Instagram has dropped it.
                try:
                    self._restore_settings(client)
                except Exception:
                    client = self._build()
            self._challenge_code = (challenge_code or "").strip() or None
            try:
                client.login(
                    username,
                    password,
                    verification_code=(verification_code or "").strip(),
                )
            except TwoFactorRequired as exc:
                raise TwoFactorNeeded(
                    "החשבון מוגן באימות דו-שלבי. הזן את הקוד מהאפליקציה בשדה "
                    "'קוד אימות' ונסה שוב."
                ) from exc
            finally:
                self._challenge_code = None
            client.dump_settings(SESSION_PATH)
            try:
                SESSION_PATH.chmod(0o600)
            except OSError:
                pass
            self._client = client
            self._username = client.username or username
            _write_saved_username(self._username)
            self._loaded = True
            return self._username

    def logout(self) -> None:
        with self._lock:
            self._client = None
            self._username = None
            self._loaded = True
            SESSION_PATH.unlink(missing_ok=True)
            USERNAME_PATH.unlink(missing_ok=True)

    @property
    def is_logged_in(self) -> bool:
        self.load_session()
        return self._client is not None

    @property
    def username(self) -> str | None:
        self.load_session()
        return self._username

    def _require(self) -> Any:
        self.load_session()
        if self._client is None:
            raise NotLoggedIn("אין חיבור פעיל לאינסטגרם. התחבר כדי למשוך נתונים.")
        return self._client

    # -- data --------------------------------------------------------------
    def fetch_profile(self, username: str) -> dict[str, Any]:
        with self._lock:
            client = self._require()
            user = client.user_info_by_username(username.strip().lstrip("@"))
            return {
                "pk": str(user.pk),
                "username": user.username,
                "full_name": user.full_name or "",
                "biography": getattr(user, "biography", "") or "",
                "is_private": bool(user.is_private),
                "is_verified": bool(getattr(user, "is_verified", False)),
                "follower_count": int(getattr(user, "follower_count", 0) or 0),
                "media_count": int(getattr(user, "media_count", 0) or 0),
            }

    def fetch_reels(self, account_pk: str, amount: int) -> list[dict[str, Any]]:
        with self._lock:
            client = self._require()
            medias = client.user_clips(str(account_pk), amount=amount)
            return [media_to_reel(m) for m in medias]

    def refresh_media(self, reel_pk: str) -> dict[str, Any]:
        with self._lock:
            client = self._require()
            return media_to_reel(client.media_info(str(reel_pk)))
