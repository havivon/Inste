from __future__ import annotations

import json
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from typing import Any, Iterator

from .config import DB_PATH, ensure_dirs

SCHEMA = """
CREATE TABLE IF NOT EXISTS accounts (
    pk              TEXT PRIMARY KEY,
    username        TEXT NOT NULL UNIQUE,
    full_name       TEXT,
    biography       TEXT,
    is_private      INTEGER DEFAULT 0,
    is_verified     INTEGER DEFAULT 0,
    follower_count  INTEGER DEFAULT 0,
    media_count     INTEGER DEFAULT 0,
    group_name      TEXT,
    added_at        TEXT NOT NULL,
    profile_synced_at TEXT,
    last_synced_at  TEXT
);

CREATE TABLE IF NOT EXISTS reels (
    pk              TEXT PRIMARY KEY,
    account_pk      TEXT NOT NULL REFERENCES accounts(pk) ON DELETE CASCADE,
    code            TEXT,
    taken_at        TEXT,
    caption         TEXT,
    play_count      INTEGER,
    like_count      INTEGER,
    comment_count   INTEGER,
    duration        REAL,
    thumbnail_url   TEXT,
    video_url       TEXT,
    media_urls_at   TEXT,
    music_title     TEXT,
    music_artist    TEXT,
    location_name   TEXT,
    first_seen_at   TEXT NOT NULL,
    updated_at      TEXT
);

CREATE INDEX IF NOT EXISTS idx_reels_account ON reels(account_pk);
CREATE INDEX IF NOT EXISTS idx_reels_taken_at ON reels(taken_at);

CREATE TABLE IF NOT EXISTS watch_state (
    reel_pk       TEXT PRIMARY KEY REFERENCES reels(pk) ON DELETE CASCADE,
    watched_at    TEXT,
    watch_seconds REAL DEFAULT 0,
    play_count_local INTEGER DEFAULT 0,
    is_favorite   INTEGER DEFAULT 0,
    is_hidden     INTEGER DEFAULT 0,
    rating        INTEGER,
    note          TEXT
);

CREATE INDEX IF NOT EXISTS idx_watch_watched_at ON watch_state(watched_at);

CREATE TABLE IF NOT EXISTS sync_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    account_pk  TEXT,
    username    TEXT,
    started_at  TEXT NOT NULL,
    finished_at TEXT,
    status      TEXT NOT NULL,
    fetched     INTEGER DEFAULT 0,
    new_count   INTEGER DEFAULT 0,
    error       TEXT
);

CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""

DEFAULT_SETTINGS: dict[str, Any] = {
    "blocked_keywords": [],
    "auto_watch_ratio": 0.6,
    "auto_watch_min_seconds": 3,
    "default_sort": "newest",
    "grid_size": "medium",
    "autoplay_next": True,
}


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


@contextmanager
def connect() -> Iterator[sqlite3.Connection]:
    ensure_dirs()
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def init_db() -> None:
    ensure_dirs()
    conn = sqlite3.connect(DB_PATH, timeout=30)
    try:
        conn.execute("PRAGMA journal_mode = WAL")
        conn.executescript(SCHEMA)
        conn.commit()
    finally:
        conn.close()


def get_settings() -> dict[str, Any]:
    values = dict(DEFAULT_SETTINGS)
    with connect() as conn:
        for row in conn.execute("SELECT key, value FROM settings"):
            if row["key"] in values:
                try:
                    values[row["key"]] = json.loads(row["value"])
                except json.JSONDecodeError:
                    pass
    return values


def save_settings(patch: dict[str, Any]) -> dict[str, Any]:
    with connect() as conn:
        for key, value in patch.items():
            if key not in DEFAULT_SETTINGS:
                continue
            conn.execute(
                "INSERT INTO settings(key, value) VALUES(?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (key, json.dumps(value)),
            )
    return get_settings()
