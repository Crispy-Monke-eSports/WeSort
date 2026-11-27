# PR3 (#228/#229 + #140/#142) — local verification

## Scope
SQLite transactional domain for wesort-stash-manager: steg-1 (normalized SNBT
authority, changesets, repository split), audit-tx (same-tx order audit),
Step 4 (custody/deposits/handoffs/jobs SQLite + same-tx), Step 5 (service
split), Step 6 (projection readers), Step 7 (Clock migration).

## What landed
- `snbt_snapshots` + `slot_projection ⨝ variant_register` is the only
  slot/item read and write path; legacy `physical_slots`/`physical_snapshots`
  payloads are read-only evidence (gated writes, divergence fails closed,
  byte-identical rebuild from SNBT).
- Custody/deposits/handoffs/jobs live in SQLite (8 tables + indexes), commit
  in the same transaction as the order book + audit; JSON files are neither
  read nor written (stale files warn loudly, never imported).
- `OrderStore`/`StockLedger` split into real services (placement, lifecycle,
  observation, box-UUID registry, return intake, operation journal); existing
  suite stayed green unmodified through the split.
- All domain time on `Clock` (wall `millis` + monotonic `nano`); zero
  `LongSupplier` in main; every remaining wall-clock site annotated;
  `FakeClock` + wall-jump-immune lease tests.
- Fail-closed branches tested: variant digest-collision, empty-changeset
  rejection, `PRAGMA quick_check` failure, tampered/phantom projection rows,
  non-canonical envelope rejection, air-slot rejection, double-chest meta.
- Wire format frozen (`/api/v1/*` shapes unchanged); clean install + fresh
  observations only (no migration/backfill of SNBT); live 6b6t proof (#141)
  BLOCKED-by-environment, never claimed.

## Evidence (no Docker/Minecraft, no 6b6t access)
- `./gradlew -p stash-manager-server test --offline --rerun-tasks` → 93 files,
  **615 tests, 0 failures**.
- `./gradlew test --offline --rerun-tasks` (client+common) → 62 files,
  **367 tests, 0 failures**.
- `npm --prefix web test` → **113 passed**.
- Guards byte-identical vs `origin/main`; no close-keywords in any PR3 commit
  (only `Refs`/`Relates to`).
