#!/usr/bin/env python3
"""Conservative CI scope for dependency and vanilla metadata probes."""
import os
import subprocess
import sys
import unittest
from unittest.mock import patch


def extended_for(paths):
    """Skip only known application-only paths; unknown inputs run all probes."""
    for path in paths:
        if path.startswith(("src/main/java/", "src/test/java/",
                            "stash-manager-server/src/main/java/",
                            "stash-manager-server/src/test/java/",
                            "web/src/", "web-tests/", "docs/")):
            continue
        if path in ("README.md", "CHANGELOG.md"):
            continue
        # Everything else, including compat/export code, scripts, all shared
        # code, resources, Gradle, vendor and workflow changes, runs full CI.
        return True
    return False


def select(event, base):
    if event != "pull_request" or not base:
        return True
    try:
        parent = subprocess.check_output(
            ["git", "rev-parse", "HEAD^1"], text=True).strip()
        # Use the actual checked-out PR merge commit, not a mutable branch.
        if parent != base:
            return True
        raw = subprocess.check_output(
            ["git", "diff", "--no-renames", "--name-only", "-z", parent, "HEAD"])
        paths = [os.fsdecode(p) for p in raw.split(b"\0") if p]
        return extended_for(paths)
    except (OSError, subprocess.CalledProcessError):
        return True


class ScopeTest(unittest.TestCase):
    def test_application_only(self):
        self.assertFalse(extended_for(["src/main/java/com/porter/wesort/modules/StashSorter.java",
                                       "web/src/test/cart.test.ts", "docs/CI.md"]))

    def test_relevant_and_unknown(self):
        for path in ["versions/26.3.properties", "gradle/libs.versions.toml", ".java-version",
                     "vendor/meteor-client.lock.json", "src/26.3/java/MetadataExport.java",
                     "src/main/resources/assets/wesort/recipes/26.3.json",
                     "stash-manager-server/src/main/resources/creative-tabs/26.3.json",
                     "common/src/main/java/Recipes.java", "scripts/ci/probe-scope.py",
                     ".github/workflows/ci.yml", "web/bun.lock", "unknown.txt"]:
            with self.subTest(path=path):
                self.assertTrue(extended_for(["web/src/app.ts", path]))

    def test_full_events(self):
        for event in ("push", "schedule", "workflow_dispatch", "workflow_call"):
            self.assertTrue(select(event, "unused"))
        self.assertTrue(select("pull_request", ""))

    def test_merge_diff_and_fail_closed(self):
        with patch("subprocess.check_output", side_effect=["base\n", b"web/src/app.ts\0"]):
            self.assertFalse(select("pull_request", "base"))
        with patch("subprocess.check_output", side_effect=["base\n", b"web/src/app.ts\0vendor/a\0"]):
            self.assertTrue(select("pull_request", "base"))
        with patch("subprocess.check_output", return_value="wrong\n"):
            self.assertTrue(select("pull_request", "base"))
        with patch("subprocess.check_output", side_effect=OSError):
            self.assertTrue(select("pull_request", "base"))


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        unittest.main(argv=[sys.argv[0]])
    else:
        extended = select(os.environ.get("CI_EVENT", ""), os.environ.get("PR_BASE_SHA", ""))
        value = str(extended).lower()
        print("Extended toolchain/metadata probes: " + value)
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write("extended=" + value + "\n")
