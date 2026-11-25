#!/usr/bin/env python3
"""Validate E2E evidence content, not just existence (#204).

Fails when result.json is missing, has empty checks, was built from the wrong
commit, or lacks scenario/phase attribution. Usage:
  validate-evidence.py --evidence <dir> --commit <sha> [--scenario <name>]
"""

import argparse
import json
import sys
from pathlib import Path


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--evidence", required=True)
    p.add_argument("--commit", required=True)
    p.add_argument("--scenario", default=None)
    args = p.parse_args()
    result = Path(args.evidence) / "result.json"
    if not result.exists():
        print("missing %s" % result)
        return 1
    data = json.loads(result.read_text())
    if data.get("result") != "PASS":
        print("result != PASS: %s" % data.get("result"))
        return 1
    if not data.get("checks"):
        print("empty checks[]: evidence proves nothing")
        return 1
    if data.get("source_commit") != args.commit:
        print("stale evidence: %s != %s" % (data.get("source_commit"), args.commit))
        return 1
    if args.scenario and data.get("scenario", data.get("phase")) != args.scenario:
        print(
            "scenario mismatch: %s != %s"
            % (data.get("scenario", data.get("phase")), args.scenario)
        )
        return 1
    print("evidence OK: %d checks @ %s" % (len(data["checks"]), args.commit))
    return 0


if __name__ == "__main__":
    sys.exit(main())
