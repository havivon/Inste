from __future__ import annotations

from typing import Any

from .db import connect, utcnow

REEL_COLUMNS = """
    r.pk, r.account_pk, r.code, r.taken_at, r.caption, r.play_count,
    r.like_count, r.comment_count, r.duration, r.music_title, r.music_artist,
    r.location_name, r.first_seen_at,
    a.username AS account_username, a.full_name AS account_full_name,
    w.watched_at, w.watch_seconds, w.is_favorite, w.is_hidden, w.rating, w.note
"""

ENGAGEMENT = (
    "((COALESCE(r.like_count,0) + COALESCE(r.comment_count,0)) * 1.0 "
    "/ MAX(COALESCE(r.play_count,0), 1))"
)

SORTS: dict[str, str] = {
    "newest": "r.taken_at DESC",
    "oldest": "r.taken_at ASC",
    "discovered": "r.first_seen_at DESC",
    "views": "COALESCE(r.play_count,0) DESC",
    "views_asc": "COALESCE(r.play_count,0) ASC",
    "likes": "COALESCE(r.like_count,0) DESC",
    "comments": "COALESCE(r.comment_count,0) DESC",
    "engagement": f"{ENGAGEMENT} DESC",
    "hidden_gems": f"{ENGAGEMENT} DESC, COALESCE(r.play_count,0) ASC",
    "shortest": "COALESCE(r.duration,0) ASC",
    "longest": "COALESCE(r.duration,0) DESC",
    "rating": "COALESCE(w.rating,0) DESC, r.taken_at DESC",
    "random": "RANDOM()",
}

STATUSES = ("all", "unseen", "seen", "favorites", "rated", "hidden")


# -- accounts --------------------------------------------------------------
def list_accounts() -> list[dict[str, Any]]:
    sql = """
        SELECT a.*,
               COUNT(r.pk) AS reel_count,
               SUM(CASE WHEN w.watched_at IS NULL THEN 1 ELSE 0 END) AS unseen_count,
               SUM(COALESCE(w.is_favorite, 0)) AS favorite_count,
               MAX(r.taken_at) AS latest_reel_at
        FROM accounts a
        LEFT JOIN reels r ON r.account_pk = a.pk
        LEFT JOIN watch_state w ON w.reel_pk = r.pk
        GROUP BY a.pk
        ORDER BY LOWER(a.username)
    """
    with connect() as conn:
        return [dict(row) for row in conn.execute(sql)]


def get_account(pk: str) -> dict[str, Any] | None:
    with connect() as conn:
        row = conn.execute("SELECT * FROM accounts WHERE pk = ?", (pk,)).fetchone()
        return dict(row) if row else None


def find_account_by_username(username: str) -> dict[str, Any] | None:
    with connect() as conn:
        row = conn.execute(
            "SELECT * FROM accounts WHERE LOWER(username) = LOWER(?)", (username,)
        ).fetchone()
        return dict(row) if row else None


def upsert_account(profile: dict[str, Any]) -> None:
    now = utcnow()
    with connect() as conn:
        conn.execute(
            """
            INSERT INTO accounts (pk, username, full_name, biography, is_private,
                                  is_verified, follower_count, media_count,
                                  added_at, profile_synced_at)
            VALUES (:pk, :username, :full_name, :biography, :is_private,
                    :is_verified, :follower_count, :media_count, :now, :now)
            ON CONFLICT(pk) DO UPDATE SET
                username = excluded.username,
                full_name = excluded.full_name,
                biography = excluded.biography,
                is_private = excluded.is_private,
                is_verified = excluded.is_verified,
                follower_count = excluded.follower_count,
                media_count = excluded.media_count,
                profile_synced_at = excluded.profile_synced_at
            """,
            {**profile, "now": now},
        )


def delete_account(pk: str) -> None:
    with connect() as conn:
        conn.execute("DELETE FROM accounts WHERE pk = ?", (pk,))


def set_account_group(pk: str, group_name: str | None) -> None:
    with connect() as conn:
        conn.execute(
            "UPDATE accounts SET group_name = ? WHERE pk = ?",
            (group_name or None, pk),
        )


def mark_account_synced(pk: str) -> None:
    with connect() as conn:
        conn.execute(
            "UPDATE accounts SET last_synced_at = ? WHERE pk = ?", (utcnow(), pk)
        )


# -- reels -----------------------------------------------------------------
def upsert_reels(account_pk: str, reels: list[dict[str, Any]]) -> tuple[int, list[str]]:
    """Returns (new_count, pks_of_new_reels)."""
    now = utcnow()
    new_pks: list[str] = []
    with connect() as conn:
        known = {
            row["pk"]
            for row in conn.execute(
                "SELECT pk FROM reels WHERE account_pk = ?", (account_pk,)
            )
        }
        for reel in reels:
            is_new = reel["pk"] not in known
            if is_new:
                new_pks.append(reel["pk"])
            conn.execute(
                """
                INSERT INTO reels (pk, account_pk, code, taken_at, caption,
                    play_count, like_count, comment_count, duration,
                    thumbnail_url, video_url, media_urls_at, music_title,
                    music_artist, location_name, first_seen_at, updated_at)
                VALUES (:pk, :account_pk, :code, :taken_at, :caption,
                    :play_count, :like_count, :comment_count, :duration,
                    :thumbnail_url, :video_url, :now, :music_title,
                    :music_artist, :location_name, :now, :now)
                ON CONFLICT(pk) DO UPDATE SET
                    caption = excluded.caption,
                    play_count = excluded.play_count,
                    like_count = excluded.like_count,
                    comment_count = excluded.comment_count,
                    duration = excluded.duration,
                    thumbnail_url = excluded.thumbnail_url,
                    video_url = excluded.video_url,
                    media_urls_at = excluded.media_urls_at,
                    music_title = excluded.music_title,
                    music_artist = excluded.music_artist,
                    location_name = excluded.location_name,
                    updated_at = excluded.updated_at
                """,
                {**reel, "account_pk": account_pk, "now": now},
            )
    return len(new_pks), new_pks


def update_media_urls(reel_pk: str, thumbnail_url: str, video_url: str) -> None:
    with connect() as conn:
        conn.execute(
            "UPDATE reels SET thumbnail_url = ?, video_url = ?, media_urls_at = ? "
            "WHERE pk = ?",
            (thumbnail_url, video_url, utcnow(), reel_pk),
        )


def get_reel(pk: str) -> dict[str, Any] | None:
    with connect() as conn:
        row = conn.execute(
            f"""SELECT {REEL_COLUMNS}, r.thumbnail_url, r.video_url, r.media_urls_at
                FROM reels r
                JOIN accounts a ON a.pk = r.account_pk
                LEFT JOIN watch_state w ON w.reel_pk = r.pk
                WHERE r.pk = ?""",
            (pk,),
        ).fetchone()
        return dict(row) if row else None


def build_reel_filters(params: dict[str, Any]) -> tuple[str, list[Any]]:
    clauses: list[str] = []
    args: list[Any] = []

    account = params.get("account")
    if account and account != "all":
        clauses.append("r.account_pk = ?")
        args.append(account)

    group = params.get("group")
    if group:
        clauses.append("a.group_name = ?")
        args.append(group)

    status = params.get("status") or "all"
    if status == "unseen":
        clauses.append("w.watched_at IS NULL")
    elif status == "seen":
        clauses.append("w.watched_at IS NOT NULL")
    elif status == "favorites":
        clauses.append("COALESCE(w.is_favorite, 0) = 1")
    elif status == "rated":
        clauses.append("COALESCE(w.rating, 0) > 0")
    elif status == "hidden":
        clauses.append("COALESCE(w.is_hidden, 0) = 1")

    if status != "hidden" and not params.get("include_hidden"):
        clauses.append("COALESCE(w.is_hidden, 0) = 0")

    query = (params.get("q") or "").strip()
    if query:
        clauses.append("(r.caption LIKE ? OR r.music_title LIKE ? OR a.username LIKE ?)")
        args.extend([f"%{query}%"] * 3)

    for keyword in params.get("blocked_keywords") or []:
        keyword = str(keyword).strip()
        if keyword:
            clauses.append("COALESCE(r.caption, '') NOT LIKE ?")
            args.append(f"%{keyword}%")

    numeric_filters = (
        ("min_views", "COALESCE(r.play_count,0) >= ?"),
        ("max_views", "COALESCE(r.play_count,0) <= ?"),
        ("min_likes", "COALESCE(r.like_count,0) >= ?"),
        ("min_duration", "COALESCE(r.duration,0) >= ?"),
        ("max_duration", "COALESCE(r.duration,0) <= ?"),
    )
    for key, clause in numeric_filters:
        value = params.get(key)
        if value not in (None, ""):
            clauses.append(clause)
            args.append(float(value))

    since = params.get("since")
    if since:
        clauses.append("r.taken_at >= ?")
        args.append(str(since))
    until = params.get("until")
    if until:
        clauses.append("r.taken_at <= ?")
        args.append(str(until) + "T23:59:59+00:00")

    where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
    return where, args


def query_reels(params: dict[str, Any]) -> dict[str, Any]:
    where, args = build_reel_filters(params)
    sort_key = params.get("sort") or "newest"
    order = SORTS.get(sort_key, SORTS["newest"])
    if sort_key != "random":
        order += ", r.pk DESC"

    limit = max(1, min(int(params.get("limit") or 60), 500))
    offset = max(0, int(params.get("offset") or 0))

    base = f"""
        FROM reels r
        JOIN accounts a ON a.pk = r.account_pk
        LEFT JOIN watch_state w ON w.reel_pk = r.pk
        {where}
    """
    with connect() as conn:
        total = conn.execute(f"SELECT COUNT(*) {base}", args).fetchone()[0]
        rows = conn.execute(
            f"SELECT {REEL_COLUMNS} {base} ORDER BY {order} LIMIT ? OFFSET ?",
            [*args, limit, offset],
        ).fetchall()
    return {
        "total": total,
        "limit": limit,
        "offset": offset,
        "items": [dict(row) for row in rows],
    }


def all_matching_pks(params: dict[str, Any]) -> list[str]:
    where, args = build_reel_filters(params)
    with connect() as conn:
        rows = conn.execute(
            f"""SELECT r.pk FROM reels r
                JOIN accounts a ON a.pk = r.account_pk
                LEFT JOIN watch_state w ON w.reel_pk = r.pk
                {where}""",
            args,
        ).fetchall()
    return [row["pk"] for row in rows]


# -- watch state -----------------------------------------------------------
def _ensure_watch_row(conn, reel_pk: str) -> None:
    conn.execute(
        "INSERT OR IGNORE INTO watch_state (reel_pk) VALUES (?)", (reel_pk,)
    )


def set_watched(reel_pk: str, watched: bool, seconds: float | None = None) -> None:
    with connect() as conn:
        _ensure_watch_row(conn, reel_pk)
        if watched:
            conn.execute(
                """UPDATE watch_state
                   SET watched_at = COALESCE(watched_at, ?),
                       watch_seconds = MAX(COALESCE(watch_seconds,0), ?),
                       play_count_local = COALESCE(play_count_local,0) + 1
                   WHERE reel_pk = ?""",
                (utcnow(), float(seconds or 0), reel_pk),
            )
        else:
            conn.execute(
                "UPDATE watch_state SET watched_at = NULL WHERE reel_pk = ?",
                (reel_pk,),
            )


def bulk_watch(reel_pks: list[str], watched: bool) -> int:
    if not reel_pks:
        return 0
    now = utcnow()
    with connect() as conn:
        conn.executemany(
            "INSERT OR IGNORE INTO watch_state (reel_pk) VALUES (?)",
            [(pk,) for pk in reel_pks],
        )
        placeholders = ",".join("?" for _ in reel_pks)
        if watched:
            conn.execute(
                f"UPDATE watch_state SET watched_at = COALESCE(watched_at, ?) "
                f"WHERE reel_pk IN ({placeholders})",
                [now, *reel_pks],
            )
        else:
            conn.execute(
                f"UPDATE watch_state SET watched_at = NULL "
                f"WHERE reel_pk IN ({placeholders})",
                reel_pks,
            )
    return len(reel_pks)


def set_flag(reel_pk: str, field: str, value: bool) -> None:
    if field not in ("is_favorite", "is_hidden"):
        raise ValueError(field)
    with connect() as conn:
        _ensure_watch_row(conn, reel_pk)
        conn.execute(
            f"UPDATE watch_state SET {field} = ? WHERE reel_pk = ?",
            (1 if value else 0, reel_pk),
        )


def set_annotation(reel_pk: str, rating: int | None, note: str | None) -> None:
    with connect() as conn:
        _ensure_watch_row(conn, reel_pk)
        if rating is not None:
            conn.execute(
                "UPDATE watch_state SET rating = ? WHERE reel_pk = ?",
                (max(0, min(int(rating), 5)), reel_pk),
            )
        if note is not None:
            conn.execute(
                "UPDATE watch_state SET note = ? WHERE reel_pk = ?",
                (note[:2000], reel_pk),
            )


# -- stats -----------------------------------------------------------------
def stats(account_pk: str | None) -> dict[str, Any]:
    where = "WHERE r.account_pk = ?" if account_pk and account_pk != "all" else ""
    args = [account_pk] if where else []
    with connect() as conn:
        totals = conn.execute(
            f"""
            SELECT COUNT(*) AS reels,
                   SUM(CASE WHEN w.watched_at IS NULL THEN 1 ELSE 0 END) AS unseen,
                   SUM(COALESCE(w.is_favorite,0)) AS favorites,
                   AVG(COALESCE(r.play_count,0)) AS avg_views,
                   AVG(COALESCE(r.duration,0)) AS avg_duration,
                   MAX(COALESCE(r.play_count,0)) AS max_views,
                   SUM(COALESCE(r.play_count,0)) AS total_views,
                   AVG({ENGAGEMENT}) AS avg_engagement
            FROM reels r
            LEFT JOIN watch_state w ON w.reel_pk = r.pk
            {where}
            """,
            args,
        ).fetchone()

        monthly = conn.execute(
            f"""
            SELECT substr(r.taken_at, 1, 7) AS month,
                   COUNT(*) AS reels,
                   AVG(COALESCE(r.play_count,0)) AS avg_views
            FROM reels r
            {where}
            GROUP BY month
            ORDER BY month
            """,
            args,
        ).fetchall()

        top = conn.execute(
            f"""
            SELECT {REEL_COLUMNS}
            FROM reels r
            JOIN accounts a ON a.pk = r.account_pk
            LEFT JOIN watch_state w ON w.reel_pk = r.pk
            {where}
            ORDER BY COALESCE(r.play_count,0) DESC
            LIMIT 5
            """,
            args,
        ).fetchall()

        watched_recently = conn.execute(
            f"""
            SELECT substr(w.watched_at, 1, 10) AS day, COUNT(*) AS watched
            FROM watch_state w
            JOIN reels r ON r.pk = w.reel_pk
            {where + ' AND ' if where else 'WHERE '} w.watched_at IS NOT NULL
            GROUP BY day
            ORDER BY day DESC
            LIMIT 30
            """,
            args,
        ).fetchall()

    return {
        "totals": dict(totals) if totals else {},
        "monthly": [dict(row) for row in monthly if row["month"]],
        "top": [dict(row) for row in top],
        "watch_days": [dict(row) for row in reversed(watched_recently)],
    }


def recent_syncs(limit: int = 15) -> list[dict[str, Any]]:
    with connect() as conn:
        rows = conn.execute(
            "SELECT * FROM sync_log ORDER BY id DESC LIMIT ?", (limit,)
        ).fetchall()
    return [dict(row) for row in rows]


def log_sync_start(account_pk: str, username: str) -> int:
    with connect() as conn:
        cursor = conn.execute(
            "INSERT INTO sync_log (account_pk, username, started_at, status) "
            "VALUES (?, ?, ?, 'running')",
            (account_pk, username, utcnow()),
        )
        return int(cursor.lastrowid)


def log_sync_end(
    log_id: int, status: str, fetched: int = 0, new_count: int = 0, error: str = ""
) -> None:
    with connect() as conn:
        conn.execute(
            "UPDATE sync_log SET finished_at = ?, status = ?, fetched = ?, "
            "new_count = ?, error = ? WHERE id = ?",
            (utcnow(), status, fetched, new_count, error or None, log_id),
        )
