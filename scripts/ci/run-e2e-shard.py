#!/usr/bin/env python3
"""Execute all isolated Minecraft scenarios in a shard, recording every result."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

SHARDS = {
    "journal": [
        ["SELECT", "PLACE", "OPEN"],
        ["TRANSFER", "VERIFY", "BREAK"],
        ["PICKUP", "STORE", "DIRECT"],
    ],
    "kits": [
        ["pack", "partial", "repeat", "repeat-version"],
        ["variant", "reserved", "abort"],
        ["offline", "version", "restart"],
    ],
    "repack": [
        ["mixed", "partial", "bulk", "compact"],
        ["no-tool", "no-empty", "full-stash", "lag"],
        ["abort", "reserved", "blocked", "full-inventory"],
        ["named", "identical", "rounds"],
    ],
}
COMMANDS = {
    "journal": ("journal-harness.py", "--phase"),
    "kits": ("kit-harness.py", "--scenario"),
    "repack": ("repack-harness.py", "--scenario"),
}
SCENARIO_TIMEOUT_SECONDS = 25 * 60


def run(command):
    child = subprocess.Popen(command, start_new_session=True)
    try:
        return child.wait(timeout=SCENARIO_TIMEOUT_SECONDS), False
    except subprocess.TimeoutExpired:
        os.killpg(child.pid, signal.SIGTERM)
        try:
            child.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGKILL)
            child.wait()
        return 124, True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", required=True, choices=sorted(SHARDS))
    parser.add_argument("--shard", required=True, type=int)
    parser.add_argument("--manager-image", required=True)
    args = parser.parse_args()
    groups = SHARDS[args.suite]
    if not 1 <= args.shard <= len(groups):
        parser.error(f"{args.suite} has shards 1..{len(groups)}")

    script, selector = COMMANDS[args.suite]
    evidence = Path("build/evidence/ci")
    evidence.mkdir(parents=True, exist_ok=True)
    results = []
    for name in groups[args.shard - 1]:
        print(f"::group::{args.suite}: {name}", flush=True)
        started = time.monotonic()
        command = [
            sys.executable, f"scripts/mc26/{script}", selector, name,
            "--manager-image", args.manager_image,
        ]
        try:
            returncode, timed_out = run(command)
        except OSError as exc:
            print(f"Failed to start {name}: {exc}", flush=True)
            returncode, timed_out = 127, False
        duration = round(time.monotonic() - started, 2)
        results.append({
            "suite": args.suite, "scenario": name, "passed": returncode == 0,
            "exit_code": returncode, "timed_out": timed_out,
            "duration_seconds": duration,
        })
        print(f"::endgroup::\n{name}: {'PASS' if returncode == 0 else 'FAIL'} ({duration}s)", flush=True)
        (evidence / f"{args.suite}-shard-{args.shard}.json").write_text(
            json.dumps(results, indent=2) + "\n", encoding="utf-8"
        )
    summary = [
        f"### {args.suite} shard {args.shard}",
        "| Scenario | Result | Duration (s) |",
        "|---|---|---:|",
        *[
            f"| {item['scenario']} | {'PASS' if item['passed'] else 'FAIL'}"
            f" | {item['duration_seconds']} |"
            for item in results
        ],
    ]
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
            output.write("\n".join(summary) + "\n")
    return 0 if all(item["passed"] for item in results) else 1


if __name__ == "__main__":
    sys.exit(main())
