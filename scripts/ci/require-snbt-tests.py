#!/usr/bin/env python3
"""Fail CI if any required SNBT conformance test is missing, failed or skipped."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

COMMON = {
    "CanonicalSnbtTest": {
        "preservesTypesAndExactLongs", "normalizesEquivalentInputButNotListOrder",
        "stringsAndOperations", "hashDomainsAreSeparated", "rejectsInvalidOrOversizedValues",
    },
    "PhysicalSnbtSnapshotTest": {
        "canonicalSnapshotReconstructsExactTreeAndProjection",
        "rejectsAmbiguousOrTamperedEnvelope", "knownEmptySnapshotHasCapacityAndNoFabricatedItems",
    },
    "KitTemplateTest": {"ownFormatPreservesSparseSlotsCountsAndExactFingerprint"},
    "ShulkerUuidTest": {
        "automaticRepackingRequiresValidIdentity", "automaticNamingCoversAllUnregisteredNamesAndDuplicates",
        "extractsPlainCustomNameFromSnbtPatch",
    },
}
ROLE = {
    "client": {"NativeSnbtTest": {"nativeTreesRoundTripWithTypesAndOrder", "numericWidthAndCompoundOrderRemainDistinct"}},
    "manager": {"SnbtObservationTest": {
        "acceptsCanonicalSnbtAndRejectsAlternativeAuthority",
        "rawSnbtIsTheDatabaseAuthorityAndCorruptProjectionFailsClosed",
    }},
}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("build", type=Path)
    parser.add_argument("role", choices=ROLE)
    args = parser.parse_args()
    required = {(name, method) for name, methods in (COMMON | ROLE[args.role]).items() for method in methods}
    passed = set()
    failures = []
    for report in (args.build / "test-results" / "test").glob("TEST-*.xml"):
        for case in ET.parse(report).getroot().iter("testcase"):
            key = (case.get("classname", "").split(".")[-1], case.get("name", "").split("(")[0])
            if key not in required:
                continue
            if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                failures.append(key)
            else:
                passed.add(key)
    missing = required - passed
    if missing or failures:
        raise SystemExit(f"SNBT conformance failed: missing={sorted(missing)}, failed={sorted(failures)}")
    print(f"SNBT conformance: {len(passed)} required {args.role} tests passed")

if __name__ == "__main__":
    main()
