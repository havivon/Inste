#!/usr/bin/env python3
"""Start the local Reels Lab server."""
from __future__ import annotations

import argparse
import os
import webbrowser


def main() -> None:
    parser = argparse.ArgumentParser(description="Instagram Reels Lab")
    parser.add_argument("--host", default=os.environ.get("REELS_LAB_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("REELS_LAB_PORT", "8777")))
    parser.add_argument(
        "--demo",
        action="store_true",
        help="run with local fake data - no Instagram login, nothing leaves the machine",
    )
    parser.add_argument("--reload", action="store_true", help="auto-reload on code changes")
    parser.add_argument("--open", action="store_true", help="open the browser once started")
    args = parser.parse_args()

    if args.demo:
        os.environ["REELS_LAB_DEMO"] = "1"

    import uvicorn

    url = f"http://{args.host}:{args.port}"
    print(f"Reels Lab -> {url}{'  (demo data)' if args.demo else ''}")
    if args.open:
        webbrowser.open(url)

    uvicorn.run("app.main:app", host=args.host, port=args.port, reload=args.reload)


if __name__ == "__main__":
    main()
