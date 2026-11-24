#!/usr/bin/env python3
"""Fail CI if the Java runtime/toolchain pins drift apart."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def require(condition, message):
    if not condition:
        raise SystemExit(message)


version = read(".java-version").strip()
require(re.fullmatch(r"25\.\d+\.\d+(?:\.\d+)?\+\d+", version), "Root must pin a Java 25 patch and build")
require(read("stash-manager-server/.java-version").strip() == version, "Manager Java pin differs from root")

for path in ("build.gradle.kts", "tools/mc26-toolchain/build.gradle.kts"):
    content = read(path)
    for field in ("sourceCompatibility", "targetCompatibility"):
        require(re.search(rf"\b{field}\s*=\s*JavaVersion\.VERSION_25\b", content), f"{path}: {field} must be 25")
    require(re.search(r"\boptions\.release\s*=\s*25\b", content), f"{path}: release must be 25")
manager = read("stash-manager-server/build.gradle.kts")
require(re.search(r"JavaLanguageVersion\.of\(25\)", manager), "Manager toolchain must be 25")
require(re.search(r"\boptions\.release\s*=\s*25\b", manager), "Manager compiler release must be 25")

for folder in (".github/workflows", ".github/actions"):
    for path in sorted((ROOT / folder).rglob("*.yml")):
        content = path.read_text(encoding="utf-8")
        if "actions/setup-java@" not in content:
            continue
        pins = re.findall(r"^\s*java-version:\s*['\"]?([^\s'\"]+)['\"]?\s*(?:#.*)?$", content, re.M)
        require(len(pins) == content.count("actions/setup-java@"), f"{path.relative_to(ROOT)}: each setup-java needs an explicit version pin")
        require(all(pin == version for pin in pins), f"{path.relative_to(ROOT)}: Java pins must match {version}")

tag = version.replace("+", "_")
dockerfile = read("Dockerfile")
for kind, stage in (("jdk", "build"), ("jre", "runtime")):
    pattern = rf"^FROM eclipse-temurin:{re.escape(tag)}-{kind}-jammy@sha256:[0-9a-f]{{64}} AS {stage}$"
    require(re.search(pattern, dockerfile, re.M), f"Docker {stage}: expected digest-pinned Temurin {tag}-{kind}-jammy")

print(f"PASS: client, Manager, Gradle, CI and Docker Java pins agree on {version}")
