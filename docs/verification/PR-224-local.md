# PR2 (#224/#225/#226/#266) — local verification

## Scope

Canonical SNBT identity: `SnbtCodec` + `ItemIdentity` + `SnapshotEnvelope` in
`common`, `StashIndex` quarantine on the client, `SNBT_CONTRACT.md` in sync.

## Full-bar fixes (this round, on `fix/pr2-kanonisk-snbt-identitet`)

- #225: reject/framing/limit/compat tests in `SnbtCodecTest` — trailing content,
  WS framing, MAX_DEPTH (70 nested), MAX_NODES (70k list), MAX_BYTES (>4MiB),
  unknown/truncated/invalid escapes incl. `\N{}`, unterminated compound/list/
  typed-array, invalid typed-array elements, pinned compat accept-set
  (single quotes, bare words, hex/binary→long, leading-zero decimal,
  `_` separators, suffix-less doubles).
- #226: `SnapshotEnvelope.fromPhysicalSnapshot(...)` — the single capture-side
  construction path from the authoritative `PhysicalSlots.Snapshot`; every slot
  must resolve to an `ExactVariantId` (fail-closed). SlotEntry normalizes to
  canonical bytes; digest input built via the codec model (no ad-hoc strings).
  Transport (#227) / persistence (#228) adopt the converter; contract re-scoped
  accordingly (no hand-built envelopes in production).
- #266: `StashIndex.key()` encode-failure returns `id#encode-failed` quarantine
  sentinel (never bare id, never `#`+8hex shape) + `IndexQuarantineTest`;
  truncation sites (`#sha1..8`, `s:`-keys) documented display-only;
  `ExactVariantId` used in production (`PhysicalSlots.Item.variantId`,
  exercised by the converter).
- Guard regression: `WorldInteractionGuard.jobTag` restored to main's
  fail-closed `unknown` (the PR2-lineage delta suffixed `unknown@d3@llease`,
  which bypasses the exact-`unknown` gate in `useDecision`/`breakDecision` —
  an authorization hole). Guards byte-identical vs `origin/main` again.
- Contract drift closed: envelope canonicalization documented + implemented;
  `derivedTotals` merges by canonical item SNBT.

## Evidence (no Docker/Minecraft, no 6b6t access)

- `./gradlew test --offline --rerun-tasks` (client+common) → 62 files,
  **367 tests, 0 failures**.
- `./gradlew -p stash-manager-server test --offline --rerun-tasks` →
  82 files, **566 tests, 0 failures** (shares `common/src/test`).
- `npm --prefix web test` → **113 passed**.
- Live 6b6t proof (#141) remains BLOCKED-by-environment and is never claimed;
  physical E2E needs real 26.3 + live (#16/#141).
