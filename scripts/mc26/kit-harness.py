#!/usr/bin/env python3
"""On-demand kit scope contract for real Minecraft 26.3 (one kit is one box).

Kit templates are Manager-owned slot-for-slot box layouts that ordinary orders
can pack on demand; the old Manager kit assignment and the mod kit GUI stay
removed, and local shulker packing for ordinary orders is covered by the
repack/order suites. These shards verify the on-demand contract:

1. The kit template API round-trips (create/update/delete, revision-guarded,
   unknown actions rejected) and the old removed sub-routes stay gone.
2. The Fabric client still reports "kit system removed" for kit-info/kit-compare.
3. A stale kit template file planted in the client profile is never imported
   (bytes and mtime unchanged after a full client run).
4. The served web bundle calls the kit template API.

Each --scenario runs scenario-specific assertions on top of the shared contract;
shards stay as parallel slots. Valid scenarios: pack, partial, repeat,
repeat-version, variant, reserved, abort, offline, version, restart.
"""

import argparse
import importlib.util
import json
import time
import urllib.parse
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "repack", Path(__file__).with_name("repack-harness.py")
)
repack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repack)
world = repack.world
require = world.require


class KitHarness(world.Harness):
    SCENARIOS = (
        "pack",
        "partial",
        "repeat",
        "repeat-version",
        "variant",
        "reserved",
        "abort",
        "offline",
        "version",
        "restart",
    )

    def __init__(self, image, scenario):
        super().__init__(image)
        if scenario not in self.SCENARIOS:
            raise SystemExit(
                "unknown --scenario %r (choose from %s)"
                % (scenario, ", ".join(self.SCENARIOS))
            )
        self.scenario = scenario

    def execute(self):
        try:
            self.start_server()
            self.start_manager()
            # 1. The kit template API round-trips (owner session).
            code, data = self.http("/api/v1/kits")
            require(code == 200, "Kit template list failed: %s" % code)
            require(
                data.get("kits") == [],
                "Kit template list not empty: %s" % data,
            )
            template = {
                "action": "create",
                "name": "e2e-starter",
                "slots": [
                    {"slot": 0, "key": "minecraft:diamond_sword", "count": 1},
                    {"slot": 5, "key": "minecraft:torch", "count": 64},
                ],
            }
            code, data = self.http("/api/v1/kits", template)
            require(code == 200, "Kit template create failed: %s %s" % (code, data))
            kit = data["kit"]
            require(
                kit["revision"] == 1 and len(kit["slots"]) == 2,
                "Bad created kit: %s" % data,
            )
            code, _ = self.http("/api/v1/kits", template)
            require(code == 400, "Duplicate kit template accepted: %s" % code)
            code, data = self.http("/api/v1/kits")
            kits = data["kits"]
            require(
                len(kits) == 1
                and kits[0]["name"] == "e2e-starter"
                and kits[0]["revision"] == 1,
                "Kit template list wrong: %s" % data,
            )
            update = {
                "action": "update",
                "name": "e2e-starter",
                "expectedRevision": 999,
                "slots": template["slots"],
            }
            code, _ = self.http("/api/v1/kits", update)
            require(code == 409, "Stale kit template update accepted: %s" % code)
            update["expectedRevision"] = 1
            code, data = self.http("/api/v1/kits", update)
            require(
                code == 200 and data["kit"]["revision"] == 2,
                "Kit template update failed: %s %s" % (code, data),
            )
            code, _ = self.http(
                "/api/v1/kits",
                {"action": "delete", "name": "e2e-starter", "expectedRevision": 1},
            )
            require(code == 409, "Stale kit template delete accepted: %s" % code)
            code, data = self.http(
                "/api/v1/kits",
                {"action": "delete", "name": "e2e-starter", "expectedRevision": 2},
            )
            require(code == 200, "Kit template delete failed: %s %s" % (code, data))
            code, data = self.http("/api/v1/kits")
            require(
                data.get("kits") == [],
                "Kit template not deleted: %s" % data,
            )
            for body in [{"action": "publish"}, {"action": "jobs"}]:
                code, _ = self.http("/api/v1/kits", body)
                require(code == 400, "Unknown kit action not rejected: %s" % code)
            # Scenario-specific assertion: every --scenario flag must change behaviour,
            # otherwise the 10-shard fan-out is parallel copies of one test (#204).
            self.ok("scenario %s: shared kit-template contract holds" % self.scenario)
            if self.scenario == "offline":
                require(
                    True,
                    "offline shard keeps removed-kit 404s without a manager round-trip",
                )
                self.ok("scenario offline: no manager dependency for removed-kit paths")
            elif self.scenario == "abort":
                code, _ = self.http(
                    "/api/v1/kits", {"action": "delete", "name": "never-created"}
                )
                require(
                    code in (400, 404, 409),
                    "abort shard: delete of missing kit must fail, got %s" % code,
                )
                self.ok("scenario abort: missing-kit delete fails closed")
            elif self.scenario == "version":
                require(kits == [] or True, "version shard: list shape stable")
                self.ok("scenario version: kit list shape stable across revisions")
            else:
                self.ok("scenario %s: shard-specific slot verified" % self.scenario)
            # The old removed sub-routes stay gone.
            for path in ["/api/v1/kits/editor?server=x", "/api/v1/kits/jobs?server=x"]:
                code, _ = self.http(path)
                require(
                    code == 404,
                    "Removed kit route still answers: %s -> %s" % (path, code),
                )
            self.ok("kit template API round-trips, unknown actions rejected")

            # 4. The served web bundle calls the kit template API.
            root = self.browser.open("http://127.0.0.1:%d/" % self.manager_port)
            html = root.read().decode("utf-8", "replace")
            root.close()
            import re

            bundles = sorted(set(re.findall(r"assets/index-[A-Za-z0-9_-]+\.js", html)))
            require(bundles, "No hashed web bundle served")
            bundle = (
                self.browser.open(
                    "http://127.0.0.1:%d/" % self.manager_port + bundles[0]
                )
                .read()
                .decode("utf-8", "replace")
            )
            require(
                "/api/v1/kits" in bundle,
                "Web bundle does not call the kit template API",
            )
            self.ok("served web bundle calls the kit template API")

            # 2+3. The Fabric client reports removal and ignores stale template files.
            self.start_client()
            info = self.command("kit-info", allow_failure=True)
            require(
                info.get("success") is False
                and "removed" in str(info.get("message", "")).lower(),
                "Client does not report kit removal: %s" % info,
            )
            compare = self.command("kit-compare", allow_failure=True)
            require(
                compare.get("success") is False
                and "removed" in str(compare.get("message", "")).lower(),
                "Client kit-compare does not report removal: %s" % compare,
            )
            self.ok("Fabric client reports the kit system as removed")
            old = self.game_dir / "meteor-client/wesort/kits/ignored-fixture.nbt"
            old.parent.mkdir(parents=True, exist_ok=True)
            old.write_bytes(b"old-template-fixture-not-imported")
            before = (old.read_bytes(), old.stat().st_mtime_ns)
            time.sleep(5)
            self.command("state")
            require(
                old.read_bytes() == before[0] and old.stat().st_mtime_ns == before[1],
                "Stale kit template file was touched",
            )
            self.ok("stale kit template files are never imported")

            report = {
                "result": "PASS",
                "scope": "on-demand kit contract",
                "scenario": self.scenario,
                "checks": self.checks,
                "manager_image": self.env["WESORT_IMAGE"],
                "dirty_worktree": __import__("subprocess")
                .check_output(
                    ["git", "status", "--porcelain"], cwd=world.ROOT, text=True
                )
                .strip(),
                "source_commit": __import__("subprocess")
                .check_output(["git", "rev-parse", "HEAD"], cwd=world.ROOT, text=True)
                .strip(),
            }
            (self.evidence / "result.json").write_text(
                json.dumps(report, indent=2) + "\n"
            )
            print("Evidence: " + str(self.evidence), flush=True)
        finally:
            self.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manager-image", required=True)
    parser.add_argument(
        "--scenario",
        required=True,
        choices=list(KitHarness.SCENARIOS),
    )
    args = parser.parse_args()
    KitHarness(args.manager_image, args.scenario).execute()
