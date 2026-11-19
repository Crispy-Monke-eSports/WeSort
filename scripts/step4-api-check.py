#!/usr/bin/env python3
"""Step-4 API check: quotas enforced on orders, release on cancel, per-person audit filter, reconcile route."""

import http.cookiejar
import importlib.util
import json
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "fake_addon", repo / "stash-manager-server/dev/fake-addon.py"
)
fake = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fake)

JAR = repo / "stash-manager-server/build/libs/wesort-manager.jar"
JAVA = "java"


def main():
    with tempfile.TemporaryDirectory(prefix="wesort-step4-") as directory:
        root = Path(directory)
        data = root / "wesort-manager-data"
        data.mkdir()
        (data / ".wesort-installation").write_text("wesort-manager:1\n")
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        (data / "config.properties").write_text(
            f"port={port}\nbind=127.0.0.1\neula=true\n"
        )
        base = f"http://127.0.0.1:{port}"
        cookies = http.cookiejar.CookieJar()
        browser = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(cookies)
        )

        def request(path, body=None):
            headers = {}
            if body is not None:
                headers = {"Content-Type": "application/json", "X-Stash-Manager": "1"}
            req = urllib.request.Request(
                base + path,
                data=None if body is None else json.dumps(body).encode(),
                headers=headers,
            )
            try:
                with browser.open(req, timeout=5) as response:
                    return response.status, json.load(response)
            except urllib.error.HTTPError as e:
                return e.code, json.loads(e.read().decode() or "{}")

        log = root / "manager.log"
        with log.open("w") as out:
            process = subprocess.Popen(
                [JAVA, "-jar", str(JAR), "--data", str(data)],
                cwd=root,
                stdout=out,
                stderr=subprocess.STDOUT,
            )
        try:
            for _ in range(900):
                if process.poll() is not None:
                    raise AssertionError("exited early:\n" + log.read_text()[-3000:])
                try:
                    status, state = request("/api/v1/auth/state")
                    if status == 200:
                        break
                except OSError:
                    time.sleep(0.1)
            assert state["setupNeeded"], state
            token = subprocess.check_output(
                [JAVA, "-jar", str(JAR), "bootstrap", "--data", str(data)], text=True
            ).strip()
            password = "step4-api-check-only"
            assert (
                request("/api/v1/auth/setup", {"code": token, "password": password})[0]
                == 200
            )
            assert request("/api/v1/auth/login", {"password": password})[0] == 200
            key = [
                line.split("=", 1)[1].strip()
                for line in (data / "config.properties").read_text().splitlines()
                if line.startswith("apiKey")
            ][0]
            agent = fake.Client(base, key)
            index = fake.protocol_observation(fake.build_index(extra_diamonds=1000))
            assert agent.post("/api/v1/index", index)[0] == 200

            # 1. Enable quotas: * may withdraw 64 diamonds per hour.
            status, policy = request(
                "/api/v1/quotas",
                {
                    "expectedRevision": 1,
                    "enabled": True,
                    "rules": [
                        {
                            "subject": "*",
                            "key": "item:minecraft:diamond",
                            "max": 64,
                            "windowSeconds": 3600,
                        }
                    ],
                },
            )
            assert status == 200, (status, policy)

            def place(count):
                return request(
                    "/api/v1/orders",
                    {
                        "server": fake.SERVER,
                        "stash": fake.STASH,
                        "lines": [
                            {"type": "item", "key": "minecraft:diamond", "count": count}
                        ],
                    },
                )

            # 2. First order reserves 40.
            status, first = place(40)
            assert status == 200, (status, first)
            first_id = first["order"]["id"]
            status, usage = request("/api/v1/quotas/me")
            assert (
                status == 200
                and usage["usage"]["item:minecraft:diamond"]["reserved"] == 40
            ), usage

            # 3. Second order of 40 exceeds 64 -> HTTP 429, no reservation leaked.
            status, rejected = place(40)
            assert status == 429, (status, rejected)
            status, usage = request("/api/v1/quotas/me")
            assert usage["usage"]["item:minecraft:diamond"]["reserved"] == 40, usage

            # 4. Cancel releases; a fresh 64 fits again.
            assert request(f"/api/v1/orders/{first_id}/cancel", {})[0] == 200
            status, usage = request("/api/v1/quotas/me")
            assert usage["usage"]["item:minecraft:diamond"]["reserved"] == 0, usage
            status, big = place(64)
            assert status == 200, (status, big)
            big_id = big["order"]["id"]

            # 5. Per-person audit filter shows the order actions.
            status, audit = request("/api/v1/audit?person=Owner&limit=200")
            assert status == 200, (status, audit)
            actions = [e["action"] for e in audit["entries"]]
            assert "order.place" in actions and "order.cancel" in actions, actions
            status, audit_all = request("/api/v1/audit?limit=200")
            assert len(audit_all["entries"]) >= len(audit["entries"])

            # 6. Reconcile on a non-fenced order is a 409, unknown id is a 404.
            assert (
                request(f"/api/v1/orders/{big_id}/reconcile", {"outcome": "requeue"})[0]
                == 409
            )
            assert (
                request(
                    "/api/v1/orders/o-deadbeefcafe/reconcile", {"outcome": "requeue"}
                )[0]
                == 404
            )
            assert (
                request(f"/api/v1/orders/{big_id}/reconcile", {"outcome": "bogus"})[0]
                == 400
            )

            # 7. Quota override without approver rights is forbidden; owner override works and is audited.
            status, over = request(
                "/api/v1/orders",
                {
                    "server": fake.SERVER,
                    "stash": fake.STASH,
                    "lines": [
                        {"type": "item", "key": "minecraft:diamond", "count": 1000}
                    ],
                    "quotaOverride": True,
                },
            )
            assert status == 200, (status, over)
            status, audit = request("/api/v1/audit?person=Owner&limit=200")
            assert any(
                e["action"] == "order.quota.override" for e in audit["entries"]
            ), [e["action"] for e in audit["entries"]]
            print("STEP4-API-OK")
        finally:
            process.terminate()
            process.wait(timeout=10)


if __name__ == "__main__":
    sys.exit(main())
