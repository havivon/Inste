#!/usr/bin/env python3
"""Start the local Reels Lab server."""
from __future__ import annotations

import argparse
import os
import socket
import webbrowser


def lan_address() -> str | None:
    """Best guess at this machine's address on the local network."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # A connected UDP socket sends nothing; this just asks the routing
        # table which local address would be used to reach the internet.
        probe.connect(("8.8.8.8", 53))
        address = probe.getsockname()[0]
        return None if address.startswith("127.") else address
    except OSError:
        return None
    finally:
        probe.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Instagram Reels Lab")
    parser.add_argument("--host", default=os.environ.get("REELS_LAB_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("REELS_LAB_PORT", "8777")))
    parser.add_argument(
        "--lan",
        action="store_true",
        help="also serve to other devices on this Wi-Fi (phone, tablet); "
             "those devices must supply the printed access code",
    )
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

    host = "0.0.0.0" if args.lan else args.host

    # Imported after the environment is set, so the app reads the final config.
    from app.config import access_token

    import uvicorn

    local_url = f"http://127.0.0.1:{args.port}"
    print(f"Reels Lab -> {local_url}{'  (demo data)' if args.demo else ''}")

    if host == "0.0.0.0":
        token = access_token()
        address = lan_address()
        print()
        print("  פתוח גם למכשירים אחרים ברשת. מהפלאפון, באותו Wi-Fi:")
        if address:
            print(f"    http://{address}:{args.port}/?token={token}")
        else:
            print(f"    http://<כתובת-ה-IP-של-המחשב>:{args.port}/?token={token}")
        print(f"  קוד הגישה: {token}")
        print("  אל תריץ עם --lan ברשת ציבורית.")
        print()

    if args.open:
        webbrowser.open(local_url)

    uvicorn.run("app.main:app", host=host, port=args.port, reload=args.reload)


if __name__ == "__main__":
    main()
