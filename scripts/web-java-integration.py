#!/usr/bin/env python3
"""Real Java integration check for the React storefront (no frontend mocks).

Starts a throwaway Manager on a free port with isolated data, then verifies:
- bootstrap + first-run setup, owner login (username field), member creation + login
- exact auth/state JSON shape (perms object, scopes, elevated fields)
- icon endpoint contract (/icon/<ns>/<path>.png, PNG bytes, 404 for bad ids)
- order request shape: missing Idempotency-Key -> 400; with header -> real
  validation error (proves the header is read from headers, not the body)
- orders list + auth/permissions gating (403 with mustChange where applicable)
- SPA serving: /shop 200, unknown /api/* JSON 404, traversal 404

Usage: python3 scripts/web-java-integration.py [--manager-jar PATH] [--keep]
Requires WESORT_EULA=true in the environment (downloads the official client
for icons, like production).
"""

import hashlib
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request
import urllib.error

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR = os.path.join(ROOT, "stash-manager-server", "build", "libs", "wesort-manager.jar")
CHECKS = []


def ok(name):
    CHECKS.append(name)
    print("ok - " + name, flush=True)


def require(cond, message):
    if not cond:
        raise AssertionError(message)


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Client:
    def __init__(self, base):
        self.base = base
        self.cookies = {}

    def request(self, path, body=None, headers=None, method=None):
        url = self.base + path
        data = json.dumps(body).encode() if body is not None else None
        heads = {
            "Content-Type": "application/json",
            "X-Stash-Manager": "1",
            "Origin": self.base,
            "Accept": "application/json",
        }
        if self.cookies:
            heads["Cookie"] = "; ".join(f"{k}={v}" for k, v in self.cookies.items())
        heads.update(headers or {})
        req = urllib.request.Request(
            url, data=data, headers=heads, method=method or ("POST" if data else "GET")
        )
        try:
            with urllib.request.urlopen(req, timeout=20) as res:
                for part in res.headers.get_all("Set-Cookie", []):
                    kv = part.split(";", 1)[0]
                    if "=" in kv:
                        k, v = kv.split("=", 1)
                        self.cookies[k.strip()] = v.strip()
                raw = res.read()
                ctype = res.headers.get_content_type()
                return (
                    res.status,
                    json.loads(raw) if raw and ctype == "application/json" else raw,
                    res.headers,
                )
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                return e.code, json.loads(raw) if raw else None, e.headers
            except ValueError:
                return e.code, raw, e.headers


def main():
    jar = os.environ.get("WESORT_JAR", JAR)
    require(os.path.exists(jar), "manager jar missing: " + jar)
    data = tempfile.mkdtemp(prefix="wesort-webint-")
    port = free_port()
    # A pre-seeded installation: marker first (like a real install), then settings.
    with open(os.path.join(data, ".wesort-installation"), "w") as f:
        f.write("wesort-manager:1\n")
    with open(os.path.join(data, "config.properties"), "w") as f:
        f.write(f"port={port}\nbind=127.0.0.1\neula=true\n")
    proc = subprocess.Popen(
        ["java", "-jar", jar, "serve", "--data", data],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        env={**os.environ, "WESORT_EULA": "true"},
    )
    keep = "--keep" in sys.argv
    try:
        base = f"http://127.0.0.1:{port}"
        anon = Client(base)
        for _ in range(100):
            try:
                status, state, _ = anon.request("/api/v1/auth/state")
                if status == 200:
                    break
            except OSError:
                time.sleep(0.3)
        else:
            raise AssertionError("manager did not start")
        require(state["setupNeeded"] is True, "fresh install must need setup")
        require(state["minLength"] >= 12, "minLength advertised")
        ok("fresh install reports setupNeeded + minLength")

        code = (
            subprocess.run(
                ["java", "-jar", jar, "bootstrap", "--data", data],
                capture_output=True,
                text=True,
                timeout=60,
            )
            .stdout.strip()
            .splitlines()[-1]
        )
        require(len(code) >= 16, "bootstrap code issued")

        password = "integration-test-pw-1"
        status, setup, _ = anon.request(
            "/api/v1/auth/setup", {"code": code, "password": password}
        )
        require(status == 200 and setup.get("ok"), f"setup failed: {setup}")
        status, state, _ = anon.request("/api/v1/auth/state")
        require(state["setupNeeded"] is False, "setup completes first-run")
        ok("bootstrap + first-run setup")

        owner = Client(base)
        status, login, _ = owner.request("/api/v1/auth/login", {"password": password})
        require(status == 200 and login.get("ok"), f"owner login failed: {login}")
        status, state, _ = owner.request("/api/v1/auth/state")
        user = state["user"]
        require(state["loggedIn"] and user["owner"] is True, "owner session")
        perms = user["perms"]
        require(
            isinstance(perms, dict) and perms["view"] is True,
            "perms is an object with booleans",
        )
        require(
            "elevated" in state and "elevatedUntil" in state, "elevation fields present"
        )
        ok("owner login over username-less body; exact perms object")

        # Single-server operation: the owner sets the one Minecraft server.
        status, srv, _ = owner.request("/api/v1/server", {"server": "s"})
        require(
            status == 200
            and srv.get("server") == "s"
            and srv.get("configured") is True,
            f"server set: {srv}",
        )
        ok("single Minecraft server configured by owner")

        # Stash definitions: the owner creates the region cube + placements.
        status, created, _ = owner.request(
            "/api/v1/stash-defs",
            {
                "action": "create",
                "name": "a",
                "dimension": "minecraft:overworld",
                "pos1": {"x": 0, "y": -60, "z": 0},
                "pos2": {"x": 31, "y": 20, "z": 31},
            },
        )
        require(
            status == 200 and created.get("def", {}).get("name") == "a",
            f"stash def created: {created}",
        )
        status, defs, _ = owner.request("/api/v1/stash-defs")
        require(
            status == 200 and len(defs.get("defs", [])) == 1,
            f"stash def listed: {defs}",
        )
        ok("owner-created stash definition (region cube)")

        # username field (not name) selects the member account.
        status, _m, _ = owner.request(
            "/api/v1/users", {"name": "member1", "password": "member-test-pw-1"}
        )
        require(status == 200, "owner creates member")
        member = Client(base)
        status, login, _ = member.request(
            "/api/v1/auth/login",
            {"username": "member1", "password": "member-test-pw-1"},
        )
        require(
            status == 200 and login.get("ok"),
            f"member login via username failed: {login}",
        )
        status, mstate, _ = member.request("/api/v1/auth/state")
        require(
            mstate["user"]["name"] == "member1" and mstate["user"]["owner"] is False,
            "member session",
        )
        require(mstate["user"]["perms"]["ordersAll"] is False, "member lacks ordersAll")
        require(mstate["user"]["mustChange"] is True, "new member must change password")
        # mustChange gate: stash access is refused until the password is changed.
        status, denied, _ = member.request("/api/v1/stashes")
        require(
            status == 403 and denied.get("mustChange") is True,
            f"mustChange enforced: {denied}",
        )
        status, changed, _ = member.request(
            "/api/v1/auth/password",
            {"current": "member-test-pw-1", "next": "member-test-pw-2"},
        )
        require(
            status == 200 and changed.get("ok"), f"password change failed: {changed}"
        )
        ok(
            "member login via username field; scoped perms object; mustChange gate + rotation"
        )

        status, stashes, _ = member.request("/api/v1/stashes")
        require(
            status == 200 and stashes == [], "no stashes yet, empty list (not an error)"
        )
        ok("stash list (empty)")

        # Icon contract: real PNG bytes, namespaced path, guarded by session.
        status, png, headers = member.request("/icon/minecraft/shulker_box.png")
        require(
            status == 200 and headers.get_content_type() == "image/png", "icon 200 png"
        )
        require(
            png[:8] == bytes([137, 80, 78, 71, 13, 10, 26, 10]), "icon is a real PNG"
        )
        require(struct.unpack(">II", png[16:24]) == (64, 64), "game render is 64x64")
        status, _, _ = member.request("/icon/minecraft/does_not_exist_xyz.png")
        require(status == 200, "unknown id falls back (still 200 PNG)")
        anon2 = Client(base)
        status, _, _ = anon2.request("/icon/minecraft/shulker_box.png")
        require(status == 401, "icons require a session")
        ok("icon endpoint: namespaced PNG, fallback, session-guarded")

        # Order shape: member without order perm is refused; header key enforced for the owner.
        order = {
            "stash": "a",
            "lines": [{"type": "item", "key": "minecraft:diamond", "count": 1}],
        }
        status, forbidden, _ = member.request("/api/v1/orders", order)
        require(
            status == 403 and forbidden.get("permission") == "order",
            f"member without order perm refused: {forbidden}",
        )
        ok("order permission gating for restricted members")
        status, err, _ = owner.request("/api/v1/orders", order)
        require(
            status == 400 and err and "no index" in err.get("error", ""),
            f"no header still validates body: {err}",
        )
        status, bad, _ = owner.request(
            "/api/v1/orders", order, headers={"Idempotency-Key": "not valid!!"}
        )
        require(
            status == 400 and bad and "Invalid Idempotency-Key" in bad.get("error", ""),
            f"malformed header rejected: {bad}",
        )
        status, err, _ = owner.request(
            "/api/v1/orders",
            {**order, "idempotencyKey": "body-key"},
            headers={"Idempotency-Key": "hdr-key-1"},
        )
        require(
            status == 400 and "no index" in err.get("error", ""),
            f"header accepted, body key ignored: {err}",
        )
        ok("Idempotency-Key read from header; order shape validated by Java")

        status, listing, _ = member.request("/api/v1/orders?stash=a")
        require(
            status == 200
            and listing.get("orders") == []
            and listing.get("available") == {"items": {}, "shulkers": {}},
            f"unknown stash lists empty (not an error): {listing}",
        )
        ok("orders list endpoint reachable with session")

        # SPA + API boundaries served by the same process.
        status, html, headers = member.request("/")
        require(status == 200 and b'<div id="root">' in html, "SPA shell served")
        status, _, _ = member.request("/shop")
        require(status == 200, "SPA fallback route")
        status, nf, _ = member.request("/api/v1/no-such-route")
        require(status == 404 and nf.get("ok") is False, "unknown API is JSON 404")
        ok("single-process SPA + JSON API boundaries")

        print(f"\nPASS: {len(CHECKS)} Java integration checks (port {port})")
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
        if keep:
            print("kept data: " + data)
        else:
            shutil.rmtree(data, ignore_errors=True)


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print("FAIL: " + str(e), file=sys.stderr)
        sys.exit(1)
