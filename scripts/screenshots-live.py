#!/usr/bin/env python3
"""Real visual evidence for PR #94: screenshots against a live Java Manager with
real Minecraft 26.3 icons (no /icon mocks).

Starts a throwaway Manager on :8767 with isolated data, runs owner setup, sets the
server, defines two stashes, pushes a fake stock observation via the signed addon
protocol, pre-warms the icon renders, then drives Chromium (app served by the
Manager itself, same CSP/nonce pipeline as production) through Overview,
Inventory (grid + table + details), Orders, Bots, Operations, Settings and the
checkout confirmation at 390/768/1440/1920 px.

Usage: python3 scripts/screenshots-live.py [--keep]
Requires WESORT_EULA=true (downloads the official client for icons, like production).
Playwright chromium must be installed.
"""

import hashlib
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import urllib.error

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR = os.path.join(ROOT, "stash-manager-server", "build", "libs", "wesort-manager.jar")
OUT = os.path.join(ROOT, "docs", "screenshots")
CHECKS = []


def ok(name):
    CHECKS.append(name)
    print("ok - " + name, flush=True)


def fail(msg):
    print("FAIL: " + msg, flush=True)
    raise SystemExit(1)


class Client:
    def __init__(self, base):
        self.base = base
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor())

    def request(self, path, body=None, method=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(
            self.base + path,
            data=data,
            method=method or ("POST" if body is not None else "GET"),
        )
        if body is not None:
            req.add_header("Content-Type", "application/json")
            req.add_header("X-Stash-Manager", "1")
        try:
            with self.opener.open(req, timeout=30) as r:
                raw = r.read()
                try:
                    return r.status, json.loads(raw.decode()), r.headers
                except ValueError:
                    return r.status, raw, r.headers
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                return e.code, json.loads(raw.decode()), e.headers
            except ValueError:
                return e.code, raw, e.headers


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


ITEMS = [
    ("minecraft:cobblestone", "Cobblestone", 110592, 64, "building_blocks"),
    ("minecraft:oak_planks", "Oak Planks", 5904, 64, "building_blocks"),
    ("minecraft:torch", "Torch", 4096, 64, "functional_blocks"),
    ("minecraft:diamond", "Diamond", 64, 64, "ingredients"),
    ("minecraft:iron_ingot", "Iron Ingot", 2304, 64, "ingredients"),
    ("minecraft:ender_pearl", "Ender Pearl", 160, 16, "ingredients"),
    ("minecraft:golden_apple", "Golden Apple", 37, 64, "food_and_drinks"),
    ("minecraft:netherite_sword", "Netherite Sword", 3, 1, "combat"),
]
BOX = [{"slot": i, "key": "minecraft:diamond", "count": 64} for i in range(27)]


def main():
    keep = "--keep" in sys.argv
    if os.environ.get("WESORT_EULA") != "true":
        fail("set WESORT_EULA=true (official client download, like production)")
    if not os.path.exists(JAR):
        fail("build the jar first: ./gradlew -p stash-manager-server jar")
    data = tempfile.mkdtemp(prefix="wesort-shots-")
    port = free_port()
    # A pre-seeded installation: marker first (like a real install), then settings.
    with open(os.path.join(data, ".wesort-installation"), "w") as f:
        f.write("wesort-manager:1\n")
    with open(os.path.join(data, "config.properties"), "w") as f:
        f.write(f"port={port}\nbind=127.0.0.1\neula=true\n")
    proc = subprocess.Popen(
        ["java", "-jar", JAR, "serve", "--data", data],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        env={**os.environ, "WESORT_EULA": "true"},
    )
    base = f"http://127.0.0.1:{port}"
    try:
        anon = Client(base)
        for _ in range(120):
            try:
                status, state, _ = anon.request("/api/v1/auth/state")
                if status == 200 and state.get("setupNeeded") is True:
                    break
            except OSError:
                pass
            time.sleep(0.5)
        else:
            fail(
                "manager did not start\n"
                + (proc.stdout.read().decode(errors="replace") if proc.stdout else "")
            )
        ok("throwaway manager up")

        code = (
            subprocess.run(
                ["java", "-jar", JAR, "bootstrap", "--data", data],
                capture_output=True,
                text=True,
                timeout=60,
            )
            .stdout.strip()
            .splitlines()[-1]
        )
        password = "screenshot-pw-12345"
        status, setup, _ = anon.request(
            "/api/v1/auth/setup", {"code": code, "password": password}
        )
        assert status == 200 and setup.get("ok"), f"setup failed: {setup}"
        owner = Client(base)
        status, _, _ = owner.request("/api/v1/auth/login", {"password": password})
        assert status == 200, "owner login failed"
        ok("owner setup + login")

        status, srv, _ = owner.request("/api/v1/server", {"server": "Survival"})
        assert status == 200 and srv.get("configured"), f"server: {srv}"
        # A scoped member + a second role so the users-admin screenshots show
        # real scope chips (not raw JSON) on desktop and mobile.
        status, scoped, _ = owner.request(
            "/api/v1/users",
            {
                "name": "hall-member",
                "password": "member-pw-12345",
                "mustChange": False,
                "perms": {
                    "view": True,
                    "order": True,
                    "activity": False,
                    "stashes": [{"stash": "main-hall"}],
                    "bots": ["fake-bot"],
                },
            },
        )
        assert status == 200, f"scoped user: {scoped}"
        # Strict Owner/Member (#26/#43): no viewer preset exists; the second
        # seeded user is a plain member so the users-admin screenshots still show
        # two accounts with distinct scope chips.
        status, viewer, _ = owner.request(
            "/api/v1/users",
            {"name": "hall-member-2", "password": "viewer-pw-12345", "preset": "member"},
        )
        assert status == 200, f"second member: {viewer}"
        ok("2 seeded users (scoped member + member)")
        for name, dim, p1, p2 in [
            (
                "main-hall",
                "minecraft:overworld",
                {"x": 0, "y": -60, "z": 0},
                {"x": 31, "y": 20, "z": 31},
            ),
            (
                "nether-depot",
                "minecraft:the_nether",
                {"x": 0, "y": 30, "z": 0},
                {"x": 15, "y": 50, "z": 15},
            ),
        ]:
            status, created, _ = owner.request(
                "/api/v1/stash-defs",
                {
                    "action": "create",
                    "name": name,
                    "dimension": dim,
                    "pos1": p1,
                    "pos2": p2,
                },
            )
            assert status == 200, f"stash-def {name}: {created}"
        ok("server + 2 stash definitions")

        key = subprocess.run(
            ["java", "-jar", JAR, "agent-key", "--data", data],
            capture_output=True,
            text=True,
            timeout=60,
        ).stdout.strip()
        assert key, "no agent key"
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            "fake", os.path.join(ROOT, "stash-manager-server", "dev", "fake-addon.py")
        )
        fake = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fake)
        client = fake.Client(base, key)
        items = [
            {"key": k, "id": k, "name": n, "count": c, "maxStack": m, "tab": t}
            for k, n, c, m, t in ITEMS
        ]
        index = {
            "version": 1,
            "server": "Survival",
            "stash": "main-hall",
            "player": "ShotBot",
            "mcVersion": "26.3",
            "items": items,
            "shulkers": [
                {
                    "key": "diamond-box",
                    "id": "minecraft:shulker_box",
                    "itemKey": "minecraft:diamond",
                    "count": 2,
                    "contents": BOX,
                }
            ],
        }
        status, body = client.request(
            "POST", "/api/v1/index", fake.protocol_observation(index)
        )
        assert status == 200, f"index: {status} {body}"
        status, body = client.request(
            "POST",
            "/api/v1/heartbeat",
            {"server": "Survival", "stash": "main-hall", "player": "ShotBot"},
        )
        assert (
            status == 200 and body.get("directive", {}).get("stash") == "main-hall"
        ), f"heartbeat/directive: {status} {body}"
        ok("fake stock observation + heartbeat directive")

        # A bot presence + one queued order, so Bots/Orders/Overview show real states.
        status, body = client.request(
            "POST", "/api/v1/bot/hello", {"botId": "shot-bot", "caps": []}
        )
        assert status == 200, f"bot hello: {status} {body}"
        status, placed, _ = owner.request(
            "/api/v1/orders",
            {
                "stash": "main-hall",
                "lines": [{"type": "item", "key": "minecraft:diamond", "count": 64}],
            },
        )
        assert status == 200, f"seed order: {status} {placed}"
        ok("bot presence + queued order")

        # Pre-warm every icon render so screenshots show game art, not letter tiles.
        for k, _, _, _, _ in ITEMS:
            ns, _, path = k.partition(":")
            for _ in range(60):
                status, _, _ = anon.request(f"/icon/{ns}/{path}.png?v=26.3-g1")
                if status == 200:
                    break
                time.sleep(1)
            assert status == 200, f"icon never rendered: {k}"
        ok("Minecraft 26.3 icons rendered")

        os.makedirs(OUT, exist_ok=True)
        shots = run_browser_capture(base, password)
        for name in shots:
            ok("screenshot " + name)
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
        if keep:
            print("kept data dir: " + data, flush=True)
        else:
            shutil.rmtree(data, ignore_errors=True)
    print(f"\nPASS: {len(CHECKS)} screenshot checks", flush=True)


def run_browser_capture(base, password):
    """Drive the production-served SPA with node Playwright. Returns screenshot names."""
    script = os.path.join(ROOT, "web", "screenshots", "live.mjs")
    out = subprocess.run(
        ["node", script, base, password, OUT],
        cwd=os.path.join(ROOT, "web"),
        capture_output=True,
        text=True,
        timeout=900,
        env={
            **os.environ,
            "PLAYWRIGHT_BROWSERS_PATH": os.environ.get(
                "PLAYWRIGHT_BROWSERS_PATH", os.path.expanduser("~/.cache/ms-playwright")
            ),
        },
    )
    print(out.stdout[-3000:], flush=True)
    if out.returncode != 0:
        print(out.stderr[-3000:], flush=True)
        fail("browser capture failed")
    return sorted(f for f in os.listdir(OUT) if f.endswith(".png"))


if __name__ == "__main__":
    main()
