from __future__ import annotations

from typing import Any

from . import store
from .config import DEFAULT_FETCH_AMOUNT, MAX_FETCH_AMOUNT
from .media import download_thumbnail
from .provider import get_provider


def sync_account(account_pk: str, amount: int | None = None) -> dict[str, Any]:
    account = store.get_account(account_pk)
    if account is None:
        raise ValueError("החשבון לא נמצא")

    amount = max(1, min(int(amount or DEFAULT_FETCH_AMOUNT), MAX_FETCH_AMOUNT))
    log_id = store.log_sync_start(account_pk, account["username"])
    provider = get_provider()
    try:
        reels = provider.fetch_reels(account_pk, amount)
    except Exception as exc:
        store.log_sync_end(log_id, "error", error=str(exc))
        raise

    new_count, _ = store.upsert_reels(account_pk, reels)
    for reel in reels:
        download_thumbnail(reel["pk"], reel.get("thumbnail_url") or "")

    store.mark_account_synced(account_pk)
    store.log_sync_end(log_id, "done", fetched=len(reels), new_count=new_count)
    return {
        "account": account["username"],
        "fetched": len(reels),
        "new": new_count,
    }


def add_account(username: str, amount: int | None = None) -> dict[str, Any]:
    provider = get_provider()
    profile = provider.fetch_profile(username)
    store.upsert_account(profile)
    result = sync_account(profile["pk"], amount)
    return {"profile": profile, **result}
