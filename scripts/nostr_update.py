#!/usr/bin/env python3
"""Publish the Anilili update manifest to Nostr (kind 30078, d-tag "anilili-update").

This is the kill-proof update channel: the app checks GitHub Releases first and
falls back to this signed Nostr manifest when GitHub is unreachable or the repo is
gone. Publishing a new manifest with an apkUrl pointing at ANY mirror is enough to
move every installed app to the new host — no app update required.

Setup (once):
    python -m venv scripts/.venv
    scripts/.venv/Scripts/pip install -r scripts/requirements.txt   # Windows
    scripts/.venv/bin/pip install -r scripts/requirements.txt       # Unix

The signing key is read from nostr-update-key.properties at the repo root
(gitignored) or from the NOSTR_UPDATE_SECRET env var (hex secret key).

Commands:
    keygen                                  Generate a new keypair (only if you must rotate!)
    publish --version 0.1.56 --apk-url URL [--tv-apk-url URL] [--changelog TEXT]
            [--size-bytes N] [--tv-size-bytes N] [--dry-run]
    fetch                                   Show the manifest relays currently return
    announce --id ID --title T --message M [--url https://...] [--emergency]
             [--expires-in-days N] [--min-version V] [--max-version V] [--dry-run]
    announce-clear                          Retract the live announcement
    announce-fetch                          Show the announcement relays currently return

Every version bump: publish right after the GitHub release so both channels agree.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from pathlib import Path

try:
    from coincurve import PrivateKey, PublicKeyXOnly
except ImportError:
    sys.exit("Missing dependency: pip install -r scripts/requirements.txt (use scripts/.venv)")

KIND = 30078
D_TAG = "anilili-update"
# Same key, same kind, different d-tag: relays keep the newest event per (pubkey, kind,
# d-tag), so the announcement replaces itself without ever touching the manifest beside it.
ANNOUNCE_D_TAG = "anilili-announcement"
RELAYS = [
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://relay.nostr.band",
    "wss://relay.primal.net",
]
KEY_FILE = Path(__file__).resolve().parent.parent / "nostr-update-key.properties"


def load_secret_hex() -> str:
    import os

    env = os.environ.get("NOSTR_UPDATE_SECRET")
    if env:
        return env.strip()
    if KEY_FILE.exists():
        for line in KEY_FILE.read_text().splitlines():
            if line.startswith("secretKeyHex="):
                return line.split("=", 1)[1].strip()
    sys.exit(f"No key found. Set NOSTR_UPDATE_SECRET or create {KEY_FILE} (see keygen).")


def load_public_hex() -> str | None:
    if KEY_FILE.exists():
        for line in KEY_FILE.read_text().splitlines():
            if line.startswith("publicKeyHex="):
                return line.split("=", 1)[1].strip()
    return None


def event_id(pubkey: str, created_at: int, kind: int, tags: list, content: str) -> str:
    preimage = json.dumps(
        [0, pubkey, created_at, kind, tags, content],
        separators=(",", ":"),
        ensure_ascii=False,
    )
    return hashlib.sha256(preimage.encode("utf-8")).hexdigest()


def sign_event(
    sk: PrivateKey, content: str, created_at: int | None = None, d_tag: str = D_TAG
) -> dict:
    pubkey = sk.public_key_xonly.format().hex()
    created_at = created_at or int(time.time())
    tags = [["d", d_tag]]
    eid = event_id(pubkey, created_at, KIND, tags, content)
    return {
        "id": eid,
        "pubkey": pubkey,
        "created_at": created_at,
        "kind": KIND,
        "tags": tags,
        "content": content,
        "sig": sk.sign_schnorr(bytes.fromhex(eid)).hex(),
    }


def verify_event(event: dict) -> bool:
    expected = event_id(event["pubkey"], event["created_at"], event["kind"], event["tags"], event["content"])
    if expected != event["id"]:
        return False
    return PublicKeyXOnly(bytes.fromhex(event["pubkey"])).verify(
        bytes.fromhex(event["sig"]), bytes.fromhex(event["id"])
    )


def cmd_keygen(_args: argparse.Namespace) -> None:
    if KEY_FILE.exists():
        sys.exit(
            f"{KEY_FILE} already exists. Rotating the key means the pubkey baked into "
            "shipped APKs no longer matches — only rotate together with an app release. "
            "Delete the file manually if you really must."
        )
    import secrets

    sk = PrivateKey(secrets.token_bytes(32))
    KEY_FILE.write_text(
        "# Nostr update-manifest signing key. BACK UP with the release keystore. NEVER commit.\n"
        f"secretKeyHex={sk.secret.hex()}\n"
        f"publicKeyHex={sk.public_key_xonly.format().hex()}\n"
    )
    print(f"Wrote {KEY_FILE}")
    print(f"Bake this pubkey into NostrUpdateSource.MANIFEST_PUBKEY_HEX:\n  {sk.public_key_xonly.format().hex()}")


def _variant(url: str, size_bytes: int | None, mirrors: list[str] | None = None) -> dict:
    entry: dict = {"url": url}
    if size_bytes is not None:
        entry["sizeBytes"] = size_bytes
    # Alternative hosts for the same file, tried in order after `url`. A release otherwise lives
    # or dies with one host, and the host is the part someone else can take away. Apps published
    # before this field existed ignore it and keep using `url`.
    extra = [m for m in (mirrors or []) if m and m != url]
    if extra:
        entry["mirrors"] = extra
    return entry


def cmd_publish(args: argparse.Namespace) -> None:
    # --apk-url stays the phone build: every install published before the mobile/TV flavor split
    # reads only this field, and pointing it at a TV APK would push the wrong build to phones.
    manifest = {"version": args.version, "changelog": args.changelog or "", "apkUrl": args.apk_url}
    if args.size_bytes is not None:
        manifest["sizeBytes"] = args.size_bytes
    # The internal build number (versionCode). Apps that read it treat a higher number under the
    # same version name as a rebuild worth offering; older apps ignore it and compare names.
    if args.version_code is not None:
        manifest["versionCode"] = args.version_code
    else:
        print(
            "WARNING: no --version-code given. A rebuild published under this same version name "
            "will not be offered to installs that already have it.",
            file=sys.stderr,
        )
    # Newer apps read "variants" and pick by form factor; older ones ignore it and use apkUrl.
    variants = {}
    if args.apk_url:
        variants["mobile"] = _variant(args.apk_url, args.size_bytes, args.apk_mirror)
    if args.tv_apk_url:
        variants["tv"] = _variant(args.tv_apk_url, args.tv_size_bytes, args.tv_apk_mirror)
    # A rebuild worth interrupting for: apps from this field on pop it up like a new version
    # instead of waiting in Settings. Per form factor, since TVs are the ones that never look.
    # A new version pops up anyway; older apps ignore the field.
    if args.important == "all":
        manifest["important"] = True
    elif args.important:
        if args.important not in variants:
            sys.exit(f"--important {args.important} needs that form factor's APK URL")
        variants[args.important]["important"] = True
    if variants:
        manifest["variants"] = variants
    if not args.tv_apk_url:
        print(
            "WARNING: no --tv-apk-url given. Android TV installs will fall back to the phone "
            "build, which carries Cast and its Play Services dependency.",
            file=sys.stderr,
        )
    content = json.dumps(manifest, separators=(",", ":"), ensure_ascii=False)
    event = sign_event(PrivateKey(bytes.fromhex(load_secret_hex())), content)
    if not verify_event(event):
        sys.exit("Internal error: freshly signed event failed verification")
    print(json.dumps(event, indent=2))
    # The same signed event, written out so it can be served as a file. Apps read this first
    # because one CDN request beats waiting on a relay scan, and it verifies identically —
    # it is the event, not a convenience copy of its contents.
    if args.write_event:
        Path(args.write_event).write_text(json.dumps(event), encoding="utf-8")
        print(f"Wrote signed event to {args.write_event}")
    if args.dry_run:
        print("Dry run — not published.")
        return
    broadcast(event)


def cmd_announce(args: argparse.Namespace) -> None:
    """Push one signed message to every install.

    This is the only channel that still works when the domain, the GitHub account and the APK
    host are all gone, because the address it uses is the pubkey compiled into every shipped APK.
    Use it to say where things moved, that a release is bad, or that an outage is not the
    viewer own device.
    """
    if args.url and not args.url.startswith("https://"):
        sys.exit("--url must be https:// - the app refuses every other scheme, so it would be dropped.")
    announcement = {"id": args.id, "title": args.title, "message": args.message}
    if args.url:
        announcement["url"] = args.url
    if args.url_label:
        announcement["urlLabel"] = args.url_label
    if args.emergency:
        # Routes to the high-importance notification channel - the one that makes a sound.
        announcement["severity"] = "emergency"
    if args.expires_in_days:
        announcement["expiresAt"] = int(time.time()) + args.expires_in_days * 86400
    # Version bounds are how "0.1.76 is broken, stay put" reaches only the affected installs.
    if args.min_version:
        announcement["minVersion"] = args.min_version
    if args.max_version:
        announcement["maxVersion"] = args.max_version

    content = json.dumps(announcement, separators=(",", ":"), ensure_ascii=False)
    event = sign_event(PrivateKey(bytes.fromhex(load_secret_hex())), content, d_tag=ANNOUNCE_D_TAG)
    if not verify_event(event):
        sys.exit("Internal error: freshly signed event failed verification")
    print(json.dumps(event, indent=2))
    if args.write_event:
        Path(args.write_event).write_text(json.dumps(event), encoding="utf-8")
        print(f"Wrote signed event to {args.write_event}")
    if args.dry_run:
        print("Dry run - not published.")
        return
    print(
        "Publishing to every install. Devices that already showed this id stay quiet; "
        "change --id to notify again.",
        file=sys.stderr,
    )
    broadcast(event)


def cmd_announce_clear(args: argparse.Namespace) -> None:
    """Replace the live announcement with an already-expired one.

    A relay has no delete anyone can rely on, so retracting a message means publishing a newer one
    that every device discards: same d-tag, expiry in the past. Devices that already showed the
    old message keep it in their tray - this only stops it reaching anyone new.
    """
    announcement = {
        "id": "retracted",
        "title": "",
        "message": "retracted",
        "expiresAt": int(time.time()) - 60,
    }
    content = json.dumps(announcement, separators=(",", ":"), ensure_ascii=False)
    event = sign_event(PrivateKey(bytes.fromhex(load_secret_hex())), content, d_tag=ANNOUNCE_D_TAG)
    if args.dry_run:
        print(json.dumps(event, indent=2))
        print("Dry run - not published.")
        return
    broadcast(event)


def cmd_announce_fetch(_args: argparse.Namespace) -> None:
    pubkey = load_public_hex()
    if not pubkey:
        sys.exit(f"publicKeyHex not found in {KEY_FILE}")
    request = json.dumps([
        "REQ",
        "anilili-announcement-check",
        {"kinds": [KIND], "authors": [pubkey], "#d": [ANNOUNCE_D_TAG], "limit": 3},
    ])
    events: list[dict] = []
    query_relays(request, events.append)
    if not events:
        print("No announcement found on any relay.")
        return
    newest = max(events, key=lambda e: e["created_at"])
    print(f"Newest announcement (valid signature: {verify_event(newest)}):")
    print(json.dumps(json.loads(newest["content"]), indent=2))


def cmd_fetch(_args: argparse.Namespace) -> None:
    pubkey = load_public_hex()
    if not pubkey:
        sys.exit(f"publicKeyHex not found in {KEY_FILE}")
    request = json.dumps([
        "REQ",
        "anilili-update-check",
        {"kinds": [KIND], "authors": [pubkey], "#d": [D_TAG], "limit": 3},
    ])
    events: list[dict] = []

    def on_event(event: dict) -> None:
        events.append(event)

    query_relays(request, on_event)
    if not events:
        print("No manifest found on any relay.")
        return
    newest = max(events, key=lambda e: e["created_at"])
    valid = verify_event(newest)
    print(f"Newest manifest (valid signature: {valid}):")
    print(json.dumps(json.loads(newest["content"]), indent=2))


def query_relays(request: str, on_event) -> None:
    import websocket

    for relay in RELAYS:
        try:
            ws = websocket.create_connection(relay, timeout=10)
            ws.send(request)
            deadline = time.time() + 10
            while time.time() < deadline:
                message = json.loads(ws.recv())
                if message[0] == "EVENT":
                    on_event(message[2])
                elif message[0] == "EOSE":
                    break
            ws.close()
        except Exception as error:  # noqa: BLE001 - relays are unreliable by nature
            print(f"  {relay}: {error}")


def broadcast(event: dict) -> None:
    import websocket

    message = json.dumps(["EVENT", event])
    for relay in RELAYS:
        try:
            ws = websocket.create_connection(relay, timeout=10)
            ws.send(message)
            reply = json.loads(ws.recv())
            ok = reply[0] == "OK" and reply[2] is True
            print(f"  {relay}: {'accepted' if ok else f'rejected: {reply}'}")
            ws.close()
        except Exception as error:  # noqa: BLE001
            print(f"  {relay}: {error}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("keygen", help="generate a new keypair").set_defaults(func=cmd_keygen)

    publish = commands.add_parser("publish", help="sign and broadcast a new update manifest")
    publish.add_argument("--version", required=True, help="e.g. 0.1.54 — must sort newer than the installed version")
    publish.add_argument("--apk-url", required=True, help="direct download URL of the APK asset")
    publish.add_argument("--changelog", default="", help="release notes text")
    publish.add_argument("--size-bytes", type=int, default=None, help="APK size; used only for progress display")
    publish.add_argument(
        "--version-code",
        type=int,
        default=None,
        help="the build's versionCode; lets a same-version rebuild reach installs that have the version",
    )
    publish.add_argument("--tv-apk-url", default=None, help="direct download URL of the Android TV APK (Anilili_tv.apk)")
    publish.add_argument("--tv-size-bytes", type=int, default=None, help="TV APK size; used only for progress display")
    publish.add_argument(
        "--apk-mirror",
        action="append",
        default=[],
        help="extra host for the phone APK, tried after --apk-url; repeatable",
    )
    publish.add_argument(
        "--tv-apk-mirror",
        action="append",
        default=[],
        help="extra host for the TV APK, tried after --tv-apk-url; repeatable",
    )
    publish.add_argument(
        "--important",
        choices=["tv", "mobile", "all"],
        default=None,
        help="pop this rebuild up on launch for tv, mobile or all installs instead of offering it quietly",
    )
    publish.add_argument("--dry-run", action="store_true", help="print the signed event without broadcasting")
    publish.add_argument(
        "--write-event",
        default=None,
        help="also write the signed event to this path, for serving as a file alongside the APKs",
    )
    publish.set_defaults(func=cmd_publish)

    commands.add_parser("fetch", help="show the manifest relays currently return").set_defaults(func=cmd_fetch)

    announce = commands.add_parser("announce", help="push a signed message to every install")
    announce.add_argument("--id", required=True, help="stable id; re-using it corrects the message silently, a new one re-notifies")
    announce.add_argument("--title", required=True, help="notification title")
    announce.add_argument("--message", required=True, help="notification body")
    announce.add_argument("--url", default=None, help="https link offered as a button (no other scheme is accepted)")
    announce.add_argument("--url-label", default=None, help="button text, e.g. Open the new site")
    announce.add_argument("--emergency", action="store_true", help="use the high-importance channel")
    announce.add_argument("--expires-in-days", type=int, default=None, help="stop showing it after N days")
    announce.add_argument("--min-version", default=None, help="only installs at or above this version")
    announce.add_argument("--max-version", default=None, help="only installs at or below this version")
    announce.add_argument("--dry-run", action="store_true", help="print the signed event without broadcasting")
    announce.add_argument("--write-event", default=None, help="also write the signed event for serving as announcement.json")
    announce.set_defaults(func=cmd_announce)

    retract = commands.add_parser("announce-clear", help="retract the live announcement")
    retract.add_argument("--dry-run", action="store_true", help="print the signed event without broadcasting")
    retract.set_defaults(func=cmd_announce_clear)

    commands.add_parser(
        "announce-fetch", help="show the announcement relays currently return"
    ).set_defaults(func=cmd_announce_fetch)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
