#!/usr/bin/env python3
"""Crash recovery at a durable phase boundary in real Minecraft 26.3."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import time

spec = importlib.util.spec_from_file_location(
    "repack", Path(__file__).with_name("repack-harness.py")
)
repack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repack)
require = repack.require
PHASES = ("SELECT", "PLACE", "OPEN", "TRANSFER", "VERIFY", "BREAK", "PICKUP", "STORE")


class JournalHarness(repack.RepackHarness):
    def __init__(self, image, phase):
        super().__init__(image, "mixed")
        self.phase = phase
        self.journal = (
            self.game_dir / "meteor-client/wesort/physical-journal/active.json"
        )

    def kill_manager(self):
        manager = self.run(self.compose + ["ps", "-q", "manager"]).strip()
        require(manager, "No running test Manager to crash")
        self.run(self.compose + ["kill", "-s", "SIGKILL", "manager"])
        exit_code = self.run(
            ["docker", "inspect", "--format", "{{.State.ExitCode}}", manager]
        ).strip()
        require(
            exit_code == "137",
            "The test Manager did not exit from SIGKILL: " + exit_code,
        )

    def restart_manager(self):
        self.run(
            self.compose
            + ["up", "-d", "--no-build", "--wait", "--wait-timeout", "120", "manager"]
        )
        self.ok(
            "Docker Manager survived SIGKILL and became ready on the same test volume"
        )

    def kill_client(self):
        os.killpg(self.client.pid, signal.SIGKILL)
        require(
            self.client.wait(timeout=10) == -signal.SIGKILL,
            "The test client did not exit from SIGKILL",
        )

    def record_fence_state(self, stage, state):
        # Common evidence for SELECT/PLACE/OPEN, TRANSFER/VERIFY/BREAK and
        # PICKUP/STORE: distinguish disabled sorting, missing Manager lease,
        # durable journal fencing and an unmirrored receipt. No API keys.
        keys = (
            "physicalJournal",
            "journalBlocked",
            "journalAwaitingMirror",
            "sortingPermitRequired",
            "sortingPermitted",
            "sorterActive",
            "attemptedClicks",
            "inventoryReady",
            "inventoryFailure",
            "lastBlockAck",
            "lastBlockSequence",
            "guardDenials",
        )
        detail = {"phase": self.phase, "stage": stage}
        detail.update({key: state[key] for key in keys if key in state})
        (self.evidence / f"journal-{self.phase.lower()}-{stage}.json").write_text(
            json.dumps(detail, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        return detail

    def wait_for_review(self, operation):
        end = time.monotonic() + 35
        last = None
        while time.monotonic() < end:
            status, result = self.http("/api/v1/physical")
            require(status == 200, "Owner journal list unavailable")
            last = result
            row = next(
                (row for row in result["operations"] if row["id"] == operation), None
            )
            # The Manager may already have auto-inspected this operation
            # before the owner-facing list was polled. All these states
            # belong to the unattended recovery protocol.
            if row and row["managerState"] in ("RECOVERING", "COMPLETED", "FAILED"):
                (self.evidence / "manager-row-on-review.json").write_text(
                    json.dumps(row, indent=2, default=str)[:4000] + "\n",
                    encoding="utf-8",
                )
                return row
            time.sleep(0.25)
        # Name the miss: dump every Manager row (id/revision/state) so a wrong-id upload,
        # a wrong-server rejection or a stuck state is decidable from evidence, not a bare timeout.
        (self.evidence / "physical-rows-on-timeout.json").write_text(
            json.dumps(last, indent=2) + "\n"
        )
        raise RuntimeError(
            "Manager did not flag the interrupted operation: %s"
            % json.dumps(
                [
                    {
                        k: row.get(k)
                        for k in (
                            "id",
                            "revision",
                            "managerState",
                            "inspectionState",
                            "lastSeen",
                        )
                    }
                    for row in (last or {}).get("operations", [])
                ]
            )
        )

    def snapshot_after_restart(self, row):
        snapshot = self.snapshots()
        drops = self.rcon.command(
            "data get entity @e[type=minecraft:item,limit=1] Item"
        )
        if "has the following entity data" in drops:
            snapshot["drop"] = drops
        for box in row["possibleOrphans"]:
            pos = " ".join(str(box[axis]) for axis in "xyz")
            if "Test passed" in self.rcon.command(
                f"execute if block {pos} {box['block']}"
            ):
                snapshot["standing"] = self.rcon.command(f"data get block {pos} Items")
        return snapshot

    def stable_stock_snapshot(self, row, stage):
        # Independent work after a safe no-INTENT abort may be transferring
        # stock while RCON reads the barrels, player and standing box one by
        # one. A mixed-time snapshot (or an item on the menu cursor) is not
        # an authoritative stock count. Wait for two matching observations
        # bracketed by unchanged mutation witnesses, without stopping work.
        def witness():
            state = self.command("state")["state"]
            progress = state.get("repackProgress") or {}
            return (
                state["attemptedClicks"],
                state["lastBlockSequence"],
                state["lastBlockAck"],
                state.get("repackPosition"),
                progress.get("id"),
                progress.get("phase"),
                state["physicalJournal"],
                state["inventoryReady"],
            )

        deadline = time.monotonic() + 30
        previous = None
        while time.monotonic() < deadline:
            start = witness()
            cursor_before, _ = self.cursor()
            snapshot = self.snapshot_after_restart(row)
            cursor_after, _ = self.cursor()
            end = witness()
            (self.evidence / f"stock-{stage}-latest.json").write_text(
                json.dumps(
                    {
                        "snapshot": snapshot,
                        "start": start,
                        "end": end,
                        "cursor_before": cursor_before,
                        "cursor_after": cursor_after,
                    },
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )
            if start == end and end[-1] and not cursor_before and not cursor_after:
                observation = (end, self.canonical_snapshot(snapshot))
                if observation == previous:
                    return snapshot
                previous = observation
            else:
                previous = None
            time.sleep(0.2)
        raise RuntimeError(f"No stable authoritative stock snapshot during {stage}")

    @staticmethod
    def canonical_snapshot(snapshot):
        # Raw RCON strings wobble (SNBT key order, Inventory list order, entity choice):
        # compare parsed, Slot-keyed, sorted semantic form. Tool wear while breaking is
        # expected (same exemption as totals()); every other component must match exactly.
        # Unparseable values fall back to raw strings so nothing is silently skipped.
        snbt = repack.snbt

        def norm(node):
            if isinstance(node, dict):
                out = {key: norm(value) for key, value in node.items()}
                # Tool wear while breaking is expected (same exemption as totals()):
                # packed item metadata stays exact, pickaxe damage is ignored.
                if isinstance(node.get("id"), str) and node["id"].endswith("_pickaxe"):
                    comps = out.get("components")
                    if isinstance(comps, dict):
                        comps.pop("minecraft:damage", None)
                    else:
                        out["components"] = {}
                return {key: out[key] for key in sorted(out)}
            if isinstance(node, list):
                # Top-level inventories use 'Slot'; nested packed container/bundle
                # children use lowercase 'slot' (server-snbt.py): normalize both.
                if node and all(
                    isinstance(e, dict) and ("Slot" in e or "slot" in e) for e in node
                ):

                    def slot_key(entry):
                        return entry.get("Slot", entry.get("slot"))

                    return {
                        "slot:" + str(slot_key(e)): norm(e)
                        for e in sorted(node, key=slot_key)
                    }
                return [norm(e) for e in node]
            return node

        out = {}
        for key, text in snapshot.items():
            try:
                out[key] = json.dumps(
                    norm(snbt.items(text)), sort_keys=True, separators=(",", ":")
                )
            except Exception:
                out[key] = "raw:" + text
        return out

    def require_same_world(self, first, second):
        a, b = self.canonical_snapshot(first), self.canonical_snapshot(second)
        if a == b:
            return
        diff = {
            key: {
                "before": first.get(key),
                "after": second.get(key),
                "canonical_before": a.get(key),
                "canonical_after": b.get(key),
            }
            for key in sorted(set(a) | set(b))
            if a.get(key) != b.get(key)
        }
        (self.evidence / "snapshot-diff.json").write_text(
            json.dumps(diff, indent=2) + "\n"
        )
        raise RuntimeError(
            "World items moved after reconnect without an execution permit: %s"
            % sorted(diff)
        )

    def execute(self):
        try:
            self.start_server()
            self.fixture()
            self.start_manager()
            self.start_client()
            self.configure()
            before = self.snapshots()
            self.command(
                "publish",
                url=f"http://127.0.0.1:{self.manager_port}",
                key=self.key,
                inspectionHold=True,
            )
            self.command("arm-physical-crash", phase=self.phase)
            self.command("sort-start", **self.settings)
            marker = self.control / "physical-crash-ready"
            deadline = time.monotonic() + 90
            while not marker.exists():
                if not (time.monotonic() < deadline and self.client.poll() is None):
                    armed = sorted(self.control.glob("physical-crash-*"))
                    try:
                        stalled = self.command("state")["state"]
                    except Exception as failed:
                        stalled = {"state_error": str(failed)}
                    stall = {
                        "armed_phase": self.phase,
                        "durable_markers": [path.name for path in armed],
                        "client_state": stalled,
                    }
                    # Decisive server ground truth for a PICKUP/BREAK stall: is the
                    # shulker still standing, dropped as an item, or gone?
                    try:
                        pos = (stalled or {}).get("repackPosition")
                        if pos:
                            stall["server_block_at_repack"] = self.rcon.command(
                                "data get block %d %d %d" % tuple(pos)
                            )
                            stall["server_drops"] = self.rcon.command(
                                "data get entity @e[type=minecraft:item,limit=5]"
                            )
                    except Exception as failed:
                        stall["server_probe_error"] = str(failed)
                    (self.evidence / "arming-stall.json").write_text(
                        json.dumps(stall, indent=2, default=str) + "\n"
                    )
                    raise RuntimeError(
                        "The armed durable phase was not reached "
                        "(last durable markers: %s)" % [path.name for path in armed]
                    )
                time.sleep(0.025)
            self.kill_client()
            crashed = json.loads(self.journal.read_text())
            require(
                crashed["phase"] == self.phase, "Wrong durable phase at process kill"
            )
            (self.evidence / "physical-before-restart.json").write_text(
                json.dumps(crashed, indent=2) + "\n"
            )
            (self.evidence / "client.log").rename(
                self.evidence / "client-before-restart.log"
            )
            self.kill_manager()
            self.restart_manager()
            self.start_client()
            initial = self.command("state")["state"]
            initial_detail = self.record_fence_state("restart", initial)
            require(
                initial["physicalJournal"] in ("AUTO_RECOVERING", "READY")
                and initial["attemptedClicks"] == 0,
                "Restart replayed a click or entered an unexpected recovery state: "
                + json.dumps(initial_detail),
            )
            if initial["physicalJournal"] == "READY":
                # At SELECT/PLACE the journal can be safely failed locally
                # before any INTENT was emitted. Do not demand a manual fence
                # or invent an inspection for a non-mutating operation.
                require(
                    not any(event["type"] == "INTENT" for event in crashed["events"]),
                    "Unverified physical intent was released on restart: "
                    + json.dumps(initial_detail),
                )
                require(
                    not initial["journalBlocked"] and not initial["sorterActive"],
                    "Safe no-mutation abort restarted sorting: "
                    + json.dumps(initial_detail),
                )
            else:
                require(
                    initial["sortingPermitted"] is False,
                    "Unverified restart exposed a sorting permit: "
                    + json.dumps(initial_detail),
                )
            self.command(
                "publish", url=f"http://127.0.0.1:{self.manager_port}", key=self.key
            )
            row = self.wait_for_review(crashed["id"])
            require(
                row["interruptedPhase"] == self.phase,
                "Manager omitted the interrupted phase",
            )
            require(
                "events" not in row and "leaseFingerprint" not in row,
                "Owner summary leaked private evidence",
            )
            if self.phase in ("OPEN", "TRANSFER", "VERIFY", "BREAK", "PICKUP", "STORE"):
                placed = [
                    block
                    for event in crashed["events"]
                    if event["type"] == "INTENT"
                    for block in event["evidence"].get("blocks", [])
                    if "shulker_box" in block["block"]
                ]
                require(
                    placed,
                    "The delayed rotation callback sent a placement without a durable intent",
                )
            if self.phase in ("OPEN", "TRANSFER", "VERIFY", "BREAK"):
                require(
                    row["possibleOrphans"],
                    "Manager did not list the possible standing shulker",
                )
            if self.phase == "SELECT":
                if initial["physicalJournal"] == "READY":
                    # The startup journal proved no packet was sent; Manager
                    # records the terminal safe failure without an inspection.
                    require(
                        row["managerState"] == "FAILED",
                        "Safe no-mutation SELECT was not recorded as failed: "
                        + json.dumps(row),
                    )
                    self.ok(
                        "SELECT safely failed before any mutation without operator input"
                    )
                else:
                    # If a fence existed, the signed client must complete the
                    # Manager's automatic, revision-bound read-only inspection.
                    end = time.monotonic() + 35
                    inspected = None
                    while time.monotonic() < end:
                        _, reply = self.http("/api/v1/physical")
                        inspected = next(
                            r for r in reply["operations"] if r["id"] == row["id"]
                        )
                        if inspected.get("inspectionState") == "COMPLETED":
                            require(
                                inspected["managerState"] == "FAILED",
                                "No-mutation inspection falsely completed repack",
                            )
                            self.ok(
                                "Automatic inventory readback proved SELECT sent no mutation"
                            )
                            break
                        time.sleep(0.25)
                    else:
                        raise RuntimeError(
                            "Automatic safe SELECT recovery did not complete: "
                            + json.dumps(inspected)
                        )
            # An unverified operation remains fenced. If no mutation was
            # transmitted, the old operation may terminate safely and the
            # Manager may grant a *new* sorting lease for independent work.
            fenced = self.command("state")["state"]
            fenced_detail = self.record_fence_state("reconciled", fenced)
            require(
                fenced["physicalJournal"]
                in ("AUTO_RECOVERING", "WAITING_MANAGER_RECEIPT", "READY"),
                "Client entered an unexpected journal state: "
                + json.dumps(fenced_detail),
            )
            if fenced["physicalJournal"] == "AUTO_RECOVERING":
                require(
                    fenced["journalBlocked"],
                    "Unverified physical recovery lost its journal fence: "
                    + json.dumps(fenced_detail),
                )
            # After verified READY the idle sorter can report that no lease
            # is required; only an unresolved journal must deny permits.
            if fenced["physicalJournal"] != "READY":
                require(
                    fenced["sortingPermitted"] is False,
                    "Unverified recovery exposed a sorting permit: "
                    + json.dumps(fenced_detail),
                )
            else:
                require(
                    not fenced["journalBlocked"] and not fenced["sorterActive"],
                    "Verified recovery unexpectedly restarted physical sorting: "
                    + json.dumps(fenced_detail),
                )
            require(
                fenced["attemptedClicks"] == 0,
                "Client replayed an inventory click: " + json.dumps(fenced_detail),
            )
            # For an interrupted operation with a transmitted INTENT the
            # physical world must not change under the recovery fence. When
            # SELECT/PLACE sent no INTENT at all, however, recovery ends safely
            # and normal unattended sorting is explicitly allowed to take a
            # new Manager lease. Such independent redistribution may change
            # slots, but may never change recursive item counts or metadata.
            no_mutation_sent = not any(
                event["type"] == "INTENT" for event in crashed["events"]
            )
            independent = no_mutation_sent and initial["physicalJournal"] == "READY"
            after = (
                self.stable_stock_snapshot(row, "after-restart")
                if independent
                else self.snapshot_after_restart(row)
            )
            time.sleep(8)
            later = (
                self.stable_stock_snapshot(row, "after-soak")
                if independent
                else self.snapshot_after_restart(row)
            )
            (self.evidence / "stock-snapshots.json").write_text(
                json.dumps({"before": before, "after": after, "later": later}, indent=2)
                + "\n",
                encoding="utf-8",
            )
            if independent:
                require(
                    self.totals(after) == self.totals(later),
                    "Resumed independent sorting changed total stock or item metadata",
                )
            else:
                self.require_same_world(after, later)
            soaked = self.command("state")["state"]
            soaked_detail = self.record_fence_state("after-soak", soaked)
            if soaked["physicalJournal"] == "READY":
                require(
                    not soaked["journalBlocked"] and not soaked["sorterActive"],
                    "Recovered journal restarted physical sorting during soak: "
                    + json.dumps(soaked_detail),
                )
            else:
                require(
                    soaked["sortingPermitted"] is False,
                    "Unverified recovery was re-authorized during soak: "
                    + json.dumps(soaked_detail),
                )
                if soaked["physicalJournal"] == "AUTO_RECOVERING":
                    require(
                        soaked["journalBlocked"],
                        "Unverified recovery lost its fence during soak: "
                        + json.dumps(soaked_detail),
                    )
            if not (no_mutation_sent and initial["physicalJournal"] == "READY"):
                require(
                    soaked["attemptedClicks"] == 0,
                    "An unverified operation replayed an inventory click: "
                    + json.dumps(soaked_detail),
                )
            require(
                self.totals(before) == self.totals(after) == self.totals(later),
                "Crash or unattended follow-up changed authoritative recursive totals or metadata",
            )
            self.ok(
                self.phase
                + " recovered without duplicate mutation or loss of physical stock"
            )
            (self.evidence / "result.json").write_text(
                json.dumps(
                    {
                        "result": "PASS",
                        "phase": self.phase,
                        "checks": self.checks,
                        "crash_order": ["client", "manager"],
                        "source_commit": subprocess.check_output(
                            ["git", "rev-parse", "HEAD"],
                            cwd=repack.world.ROOT,
                            text=True,
                        ).strip(),
                        "dirty_worktree": subprocess.check_output(
                            ["git", "status", "--porcelain"],
                            cwd=repack.world.ROOT,
                            text=True,
                        ).strip(),
                        "server_sha1": repack.world.SERVER_SHA1,
                        "manager_operation": row,
                    },
                    indent=2,
                )
                + "\n"
            )
            print(f"Evidence: {self.evidence}", flush=True)
        finally:
            self.close()


class DirectHarness(JournalHarness):
    def __init__(self, image, phase):
        super().__init__(image, phase)
        self.proxy = repack.network.Proxy(self.server_port)

    def manager_server_name(self):
        # The client connects through the interfering proxy, so it reports the
        # proxy address as its server: the Manager must be configured with
        # exactly that string or every agent call 409s as the wrong server.
        return "127.0.0.1:" + str(self.proxy.port)

    def execute(self):
        try:
            self.start_server()
            repack.world.Harness.fixture(self)
            self.start_manager()
            self.start_client()
            inv = repack.network.InventoryHarness
            # publish requires a real captured container to select and persist
            # e2e-fixture. Seed it before enabling the signed Manager, then
            # reopen the chest with autonomous scanning held in the same tick
            # as Manager activation. Recovery and journal mirroring stay live.
            inv.open_chest(self)
            self.command("close")
            self.command(
                "publish",
                url=f"http://127.0.0.1:{self.manager_port}",
                key=self.key,
                inspectionHold=True,
            )
            state = inv.open_chest(self)

            def require_source(snapshot, fingerprint=None):
                require(
                    snapshot["containerId"] != 0 and len(snapshot["slots"]) == 90,
                    "Direct test did not open the intended 54-slot double chest",
                )
                source = snapshot["slots"][0]
                require(
                    source["item"] == "minecraft:diamond"
                    and source["count"] == 64
                    and (fingerprint is None or source["fingerprint"] == fingerprint),
                    "Direct test source slot is not the intended diamond stack",
                )
                require(
                    snapshot["inventoryReady"], "Direct test inventory is not ready"
                )
                return source["fingerprint"]

            expected_fingerprint = require_source(state)
            with ThreadPoolExecutor(max_workers=1) as pool:
                # Poll authoritative stock while the click is outstanding:
                # do not wait for the deliberately withheld acknowledgement.
                # Retry only an explicit stale rejection before packet send.
                for attempt in range(2):
                    self.proxy.held_down.clear()
                    self.proxy.hold_down = True
                    click = pool.submit(inv.click, self, state, 0, "QUICK_MOVE", True)
                    deadline = time.monotonic() + 15
                    stale = False
                    while time.monotonic() < deadline:
                        if (
                            self.journal.exists()
                            and sum("[]" in value for value in inv.chest_halves(self))
                            == 1
                        ):
                            break
                        if click.done():
                            result = click.result()
                            require(
                                not result["success"],
                                "The unacknowledged move was reported complete",
                            )
                            require(
                                result["message"] == "Stale menu/state id",
                                "Direct click failed before server debit: "
                                + result["message"],
                            )
                            require(
                                result["state"]["attemptedClicks"]
                                == state["attemptedClicks"],
                                "Stale rejection was not proven to precede the mutation",
                            )
                            stale = True
                            break
                        time.sleep(0.05)
                    else:
                        raise RuntimeError(
                            "The source was not debited on the real server before acknowledgment was lost"
                        )
                    if not stale:
                        break
                    require(
                        attempt == 0,
                        "Direct click was stale even after reopening the source",
                    )
                    # Opening requires real server responses. Restore delivery
                    # only after proving no click was sent, then re-observe the
                    # exact source before arming the fault again.
                    self.proxy.hold_down = False
                    self.command("close")
                    state = inv.open_chest(self)
                    require_source(state, expected_fingerprint)
                require(
                    self.proxy.held_down.wait(timeout=3),
                    "Proxy did not withhold any server frames after the physical debit",
                )
                # Reverse the other phases' crash order while the server's
                # acknowledgment is still held and the client is connected.
                self.kill_manager()
                require(
                    self.client.poll() is None,
                    "The client exited before the Manager was killed",
                )
                self.proxy.drop()
                self.proxy.hold_down = False
                self.kill_client()
                try:
                    result = click.result(timeout=5)
                except RuntimeError:
                    pass  # The client was killed while the command was outstanding.
                else:
                    require(
                        not result["success"],
                        "The unacknowledged move was reported complete",
                    )
            crashed = json.loads(self.journal.read_text())
            require(
                crashed["state"] != "COMPLETED",
                "Move completed while server frames were held",
            )
            (self.evidence / "direct-before-restart.json").write_text(
                json.dumps(crashed, indent=2) + "\n"
            )
            (self.evidence / "client.log").rename(
                self.evidence / "client-before-restart.log"
            )
            self.restart_manager()
            self.start_client()
            self.command(
                "publish", url=f"http://127.0.0.1:{self.manager_port}", key=self.key
            )
            row = self.wait_for_review(crashed["id"])
            # The Manager must dispatch inspection automatically on agent
            # reconnect. No human endpoint or manual action is involved.
            end = time.monotonic() + 35
            while time.monotonic() < end:
                _, reply = self.http("/api/v1/physical")
                inspected = next(r for r in reply["operations"] if r["id"] == row["id"])
                if inspected.get("inspectionState") == "COMPLETED":
                    require(
                        inspected["managerState"] == "COMPLETED",
                        "Manager kept a verified action fenced",
                    )
                    break
                if inspected.get("inspectionState") == "FAILED":
                    raise RuntimeError(
                        inspected.get("inspectionDetail", "Inspection failed")
                    )
                time.sleep(0.25)
            else:
                raise RuntimeError(
                    "Verified postcondition did not clear the operation fence"
                )
            state = self.command("state")["state"]
            # The Manager marks automatic inspection COMPLETED on receipt of
            # the client's read-only proof. Await the signed mirror round-trip;
            # no inventory click may be replayed.
            receipt_end = time.monotonic() + 30
            while not (
                state["attemptedClicks"] == 0 and state["physicalJournal"] == "READY"
            ):
                require(
                    state["attemptedClicks"] == 0,
                    "Read-only recovery replayed movement or left the agent paused",
                )
                require(
                    state["physicalJournal"] in ("READY", "WAITING_MANAGER_RECEIPT"),
                    "Read-only recovery replayed movement or left the agent paused: "
                    + str(state["physicalJournal"]),
                )
                require(
                    time.monotonic() < receipt_end,
                    "Read-only recovery replayed movement or left the agent paused",
                )
                time.sleep(0.5)
                state = self.command("state")["state"]
            require(
                sum("[]" in value for value in inv.chest_halves(self)) == 1
                and "count: 64" in inv.player_items(self),
                "Exact native source and player totals changed during inspection",
            )
            # The Manager's index rescan can still own a *different* container
            # screen after the journal receipt is READY. Finish the Manager
            # recovery proof first, then explicitly pause only its background
            # scanner before testing the next direct user-driven inventory move.
            # Otherwise a racing scanner menu (27 slots) can be mistaken for
            # the explicitly requested double chest (54 slots).
            self.command("manager-pause")
            self.command("close")
            isolated = self.command("state")["state"]
            require(
                isolated["attemptedClicks"] == 0
                and isolated["physicalJournal"] == "READY",
                "Recovery changed before the independent direct move: "
                + str(isolated["physicalJournal"]),
            )
            require(
                isolated["containerId"] == 0,
                "Manager background scanner still owns an inventory screen",
            )
            (self.evidence / "direct-before-resumed-click.json").write_text(
                json.dumps(
                    {
                        "physicalJournal": isolated["physicalJournal"],
                        "attemptedClicks": isolated["attemptedClicks"],
                        "screen": isolated["screen"],
                    },
                    indent=2,
                )
                + "\n"
            )
            # A new, explicitly requested move proves the same agent can operate
            # again after autonomous inspection, with no competing Manager scanner.
            menu = self.command(
                "open",
                x=0,
                y=64,
                z=3,
                slots=54,
                item="minecraft:air",
                count=0,
                second=1,
            )["state"]
            carry = inv.carry_slot(menu, 54, "minecraft:diamond", 64)
            inv.click(self, menu, carry)
            require(
                all("count: 64" in value for value in inv.chest_halves(self))
                and "[]" in inv.player_items(self),
                "Resumed agent did not return exactly the one previously debited stack",
            )
            self.ok(
                "same HYBRID agent verified the accepted click and resumed without double pickup"
            )
            (self.evidence / "result.json").write_text(
                json.dumps(
                    {
                        "result": "PASS",
                        "phase": "DIRECT",
                        "checks": self.checks,
                        "crash_order": ["manager", "client"],
                        "source_commit": subprocess.check_output(
                            ["git", "rev-parse", "HEAD"],
                            cwd=repack.world.ROOT,
                            text=True,
                        ).strip(),
                        "server_sha1": repack.world.SERVER_SHA1,
                        "manager_operation": inspected,
                    },
                    indent=2,
                )
                + "\n"
            )
            print(f"Evidence: {self.evidence}", flush=True)
        finally:
            self.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=PHASES + ("DIRECT",), required=True)
    parser.add_argument("--manager-image", default="wesort-manager:journal-tests")
    arguments = parser.parse_args()
    harness = DirectHarness if arguments.phase == "DIRECT" else JournalHarness
    harness(arguments.manager_image, arguments.phase).execute()
