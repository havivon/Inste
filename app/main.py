from __future__ import annotations

import csv
import io
import json
from contextlib import asynccontextmanager
from typing import Any

import httpx
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import (
    FileResponse,
    JSONResponse,
    PlainTextResponse,
    Response,
    StreamingResponse,
)
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import store, sync
from .config import BASE_DIR, BROWSER_UA, DEMO_MODE
from .db import get_settings, init_db, save_settings
from .instagram import InstagramError
from .jobs import runner
from .media import is_allowed_media_url, thumb_path
from .provider import get_provider

STATIC_DIR = BASE_DIR / "app" / "static"

@asynccontextmanager
async def lifespan(_: FastAPI):
    init_db()
    yield


app = FastAPI(
    title="Instagram Reels Lab", docs_url=None, redoc_url=None, lifespan=lifespan
)


@app.exception_handler(InstagramError)
def _instagram_error(_: Request, exc: InstagramError) -> JSONResponse:
    return JSONResponse({"detail": str(exc)}, status_code=400)


# -- models ----------------------------------------------------------------
class LoginBody(BaseModel):
    username: str
    password: str
    verification_code: str | None = None
    challenge_code: str | None = None


class AccountBody(BaseModel):
    username: str
    amount: int | None = None


class AccountPatch(BaseModel):
    group_name: str | None = None


class SyncBody(BaseModel):
    amount: int | None = None


class WatchBody(BaseModel):
    watched: bool = True
    seconds: float | None = None


class FlagBody(BaseModel):
    value: bool = True


class AnnotationBody(BaseModel):
    rating: int | None = Field(default=None, ge=0, le=5)
    note: str | None = None


class BulkBody(BaseModel):
    action: str
    pks: list[str] | None = None
    filters: dict[str, Any] | None = None


class SettingsBody(BaseModel):
    blocked_keywords: list[str] | None = None
    auto_watch_ratio: float | None = Field(default=None, ge=0.05, le=1.0)
    auto_watch_min_seconds: float | None = Field(default=None, ge=0, le=120)
    default_sort: str | None = None
    grid_size: str | None = None
    autoplay_next: bool | None = None


# -- session ---------------------------------------------------------------
@app.get("/api/status")
def status() -> dict[str, Any]:
    provider = get_provider()
    return {
        "demo": DEMO_MODE,
        "logged_in": bool(provider.is_logged_in),
        "username": provider.username,
        "active_jobs": runner.active_count(),
        "settings": get_settings(),
    }


@app.post("/api/login")
def login(body: LoginBody) -> dict[str, Any]:
    username = get_provider().login(
        body.username.strip(),
        body.password,
        verification_code=body.verification_code,
        challenge_code=body.challenge_code,
    )
    return {"logged_in": True, "username": username}


@app.post("/api/logout")
def logout() -> dict[str, bool]:
    get_provider().logout()
    return {"logged_in": False}


# -- accounts --------------------------------------------------------------
@app.get("/api/accounts")
def accounts() -> dict[str, Any]:
    rows = store.list_accounts()
    groups = sorted({row["group_name"] for row in rows if row["group_name"]})
    return {"items": rows, "groups": groups}


@app.post("/api/accounts")
def add_account(body: AccountBody) -> dict[str, Any]:
    username = body.username.strip().lstrip("@")
    if not username:
        raise HTTPException(400, "חסר שם משתמש")
    existing = store.find_account_by_username(username)
    if existing:
        job_id = runner.submit(
            f"סנכרון @{username}",
            lambda: sync.sync_account(existing["pk"], body.amount),
        )
        return {"account": existing, "job_id": job_id, "created": False}
    job_id = runner.submit(
        f"הוספת @{username}", lambda: sync.add_account(username, body.amount)
    )
    return {"job_id": job_id, "created": True}


@app.patch("/api/accounts/{pk}")
def patch_account(pk: str, body: AccountPatch) -> dict[str, Any]:
    if store.get_account(pk) is None:
        raise HTTPException(404, "החשבון לא נמצא")
    store.set_account_group(pk, body.group_name)
    return {"account": store.get_account(pk)}


@app.delete("/api/accounts/{pk}")
def remove_account(pk: str) -> dict[str, bool]:
    store.delete_account(pk)
    return {"deleted": True}


@app.post("/api/accounts/{pk}/sync")
def sync_one(pk: str, body: SyncBody) -> dict[str, Any]:
    account = store.get_account(pk)
    if account is None:
        raise HTTPException(404, "החשבון לא נמצא")
    job_id = runner.submit(
        f"סנכרון @{account['username']}",
        lambda: sync.sync_account(pk, body.amount),
    )
    return {"job_id": job_id}


@app.post("/api/accounts/sync-all")
def sync_all(body: SyncBody) -> dict[str, Any]:
    job_ids = []
    for account in store.list_accounts():
        pk = account["pk"]
        job_ids.append(
            runner.submit(
                f"סנכרון @{account['username']}",
                lambda pk=pk: sync.sync_account(pk, body.amount),
            )
        )
    return {"job_ids": job_ids}


@app.get("/api/jobs")
def jobs() -> dict[str, Any]:
    return {"items": runner.snapshot(), "active": runner.active_count()}


@app.get("/api/sync-log")
def sync_log() -> dict[str, Any]:
    return {"items": store.recent_syncs()}


# -- reels -----------------------------------------------------------------
def _filter_params(
    account: str | None,
    group: str | None,
    status_: str,
    q: str | None,
    sort: str,
    limit: int,
    offset: int,
    min_views: float | None,
    max_views: float | None,
    min_likes: float | None,
    min_duration: float | None,
    max_duration: float | None,
    since: str | None,
    until: str | None,
    include_hidden: bool,
    apply_blocklist: bool,
) -> dict[str, Any]:
    params: dict[str, Any] = {
        "account": account,
        "group": group,
        "status": status_ if status_ in store.STATUSES else "all",
        "q": q,
        "sort": sort,
        "limit": limit,
        "offset": offset,
        "min_views": min_views,
        "max_views": max_views,
        "min_likes": min_likes,
        "min_duration": min_duration,
        "max_duration": max_duration,
        "since": since,
        "until": until,
        "include_hidden": include_hidden,
    }
    if apply_blocklist:
        params["blocked_keywords"] = get_settings().get("blocked_keywords") or []
    return params


@app.get("/api/reels")
def reels(
    account: str | None = None,
    group: str | None = None,
    status: str = "all",
    q: str | None = None,
    sort: str = "newest",
    limit: int = 60,
    offset: int = 0,
    min_views: float | None = None,
    max_views: float | None = None,
    min_likes: float | None = None,
    min_duration: float | None = None,
    max_duration: float | None = None,
    since: str | None = None,
    until: str | None = None,
    include_hidden: bool = False,
    apply_blocklist: bool = True,
) -> dict[str, Any]:
    params = _filter_params(
        account, group, status, q, sort, limit, offset, min_views, max_views,
        min_likes, min_duration, max_duration, since, until, include_hidden,
        apply_blocklist,
    )
    return store.query_reels(params)


@app.get("/api/reels/sorts")
def sorts() -> dict[str, Any]:
    return {"sorts": list(store.SORTS), "statuses": list(store.STATUSES)}


@app.post("/api/reels/bulk")
def bulk(body: BulkBody) -> dict[str, Any]:
    pks = body.pks or []
    if body.filters is not None:
        params = dict(body.filters)
        params.setdefault("status", "all")
        pks = store.all_matching_pks(params)
    if body.action == "mark_watched":
        return {"affected": store.bulk_watch(pks, True)}
    if body.action == "mark_unwatched":
        return {"affected": store.bulk_watch(pks, False)}
    raise HTTPException(400, "פעולה לא מוכרת")


@app.get("/api/reels/{pk}")
def reel_detail(pk: str) -> dict[str, Any]:
    reel = store.get_reel(pk)
    if reel is None:
        raise HTTPException(404, "הסרטון לא נמצא")
    reel.pop("video_url", None)
    return reel


@app.post("/api/reels/{pk}/watch")
def watch(pk: str, body: WatchBody) -> dict[str, Any]:
    if store.get_reel(pk) is None:
        raise HTTPException(404, "הסרטון לא נמצא")
    store.set_watched(pk, body.watched, body.seconds)
    return {"pk": pk, "watched": body.watched}


@app.post("/api/reels/{pk}/favorite")
def favorite(pk: str, body: FlagBody) -> dict[str, Any]:
    store.set_flag(pk, "is_favorite", body.value)
    return {"pk": pk, "is_favorite": body.value}


@app.post("/api/reels/{pk}/hide")
def hide(pk: str, body: FlagBody) -> dict[str, Any]:
    store.set_flag(pk, "is_hidden", body.value)
    return {"pk": pk, "is_hidden": body.value}


@app.post("/api/reels/{pk}/annotate")
def annotate(pk: str, body: AnnotationBody) -> dict[str, Any]:
    store.set_annotation(pk, body.rating, body.note)
    return store.get_reel(pk) or {}


@app.get("/api/reels/{pk}/thumb")
def thumb(pk: str) -> Response:
    reel = store.get_reel(pk)
    if reel is None:
        raise HTTPException(404, "הסרטון לא נמצא")
    if DEMO_MODE:
        from .demo import thumbnail_svg

        return Response(
            thumbnail_svg(pk, reel.get("caption") or ""),
            media_type="image/svg+xml",
            headers={"Cache-Control": "public, max-age=86400"},
        )
    path = thumb_path(pk)
    if not path.exists():
        raise HTTPException(404, "אין תמונה ממוזערת שמורה")
    return FileResponse(
        path, media_type="image/jpeg", headers={"Cache-Control": "public, max-age=86400"}
    )


PASS_THROUGH_HEADERS = ("content-type", "content-length", "content-range", "etag")


@app.get("/api/reels/{pk}/video")
def video(pk: str, request: Request) -> Response:
    if DEMO_MODE:
        raise HTTPException(
            409, "מצב הדגמה: אין וידאו אמיתי. הפעל בלי REELS_LAB_DEMO כדי לנגן."
        )
    reel = store.get_reel(pk)
    if reel is None:
        raise HTTPException(404, "הסרטון לא נמצא")

    url = reel.get("video_url") or ""
    range_header = request.headers.get("range")

    client, upstream = _open_upstream(url, range_header)
    if upstream is None or upstream.status_code in (403, 404, 410):
        if upstream is not None:
            upstream.close()
        if client is not None:
            client.close()
        fresh = get_provider().refresh_media(pk)
        store.update_media_urls(
            pk, fresh.get("thumbnail_url") or "", fresh.get("video_url") or ""
        )
        client, upstream = _open_upstream(fresh.get("video_url") or "", range_header)

    if upstream is None:
        raise HTTPException(502, "לא ניתן למשוך את הווידאו מהשרת של אינסטגרם")

    headers = {
        key: value
        for key, value in upstream.headers.items()
        if key.lower() in PASS_THROUGH_HEADERS
    }
    headers["Accept-Ranges"] = "bytes"

    def stream():
        try:
            for chunk in upstream.iter_raw():
                yield chunk
        finally:
            upstream.close()
            client.close()

    return StreamingResponse(
        stream(),
        status_code=upstream.status_code,
        headers=headers,
        media_type=upstream.headers.get("content-type", "video/mp4"),
    )


def _open_upstream(url: str, range_header: str | None):
    if not is_allowed_media_url(url):
        return None, None
    client = httpx.Client(timeout=httpx.Timeout(30.0, read=60.0), follow_redirects=True)
    headers = {"User-Agent": BROWSER_UA}
    if range_header:
        headers["Range"] = range_header
    try:
        request = client.build_request("GET", url, headers=headers)
        response = client.send(request, stream=True)
        return client, response
    except Exception:
        client.close()
        return None, None


# -- stats, export, settings ----------------------------------------------
@app.get("/api/stats")
def stats(account: str | None = None) -> dict[str, Any]:
    return store.stats(account)


@app.get("/api/export")
def export(
    fmt: str = Query("csv", pattern="^(csv|json)$"),
    account: str | None = None,
    group: str | None = None,
    status: str = "all",
    q: str | None = None,
    sort: str = "newest",
    include_hidden: bool = True,
) -> Response:
    params = _filter_params(
        account, group, status, q, sort, 500, 0, None, None, None, None, None,
        None, None, include_hidden, False,
    )
    rows: list[dict[str, Any]] = []
    offset = 0
    while True:
        params["offset"] = offset
        page = store.query_reels(params)
        rows.extend(page["items"])
        offset += page["limit"]
        if offset >= page["total"]:
            break

    for row in rows:
        row["instagram_url"] = (
            f"https://www.instagram.com/reel/{row['code']}/" if row.get("code") else ""
        )

    if fmt == "json":
        return Response(
            json.dumps(rows, ensure_ascii=False, indent=2),
            media_type="application/json",
            headers={"Content-Disposition": 'attachment; filename="reels.json"'},
        )

    buffer = io.StringIO()
    if rows:
        writer = csv.DictWriter(buffer, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    return Response(
        "﻿" + buffer.getvalue(),
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": 'attachment; filename="reels.csv"'},
    )


@app.get("/api/settings")
def read_settings() -> dict[str, Any]:
    return get_settings()


@app.put("/api/settings")
def write_settings(body: SettingsBody) -> dict[str, Any]:
    patch = {k: v for k, v in body.model_dump().items() if v is not None}
    return save_settings(patch)


# -- static ----------------------------------------------------------------
@app.get("/")
def index() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/healthz")
def healthz() -> PlainTextResponse:
    return PlainTextResponse("ok")


app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
