# PR1 local verification (working tree on top of 46f4fd33)

Executed in this environment (no Docker/Minecraft, no 6b6t access).
E2E and live proof are CI/live-gated (see bottom).

## Commands and results

- `./gradlew -p stash-manager-server test --offline --rerun-tasks`
  → 78 files, **534 tests, 0 failures, 0 errors**. Covers: strict Owner/Member
  (elevated grants refused on create/update/load-downgrade), quarantine of
  unmarked UUID roots, automatic rename-job minting (idempotent) + Owner
  cancel, legacy-JSON import removal, order atomicity, CSRF/origin, session
  revocation/replay/idempotency, hierarchy reservations.
- `npm --prefix web test` → 15 files, **113 tests, 0 failures**.
  Settings editor offers member-level flags only (view/order/activity).
- `./gradlew compileJava --offline` (client,incl. NavigationRuntime target
  pre-check, WorldInteractionGuard jobTag binding) → BUILD SUCCESSFUL.
- `./gradlew test --offline --rerun-tasks` (client) → 56 files, **328 tests, 0 failures**. Incl. WorldInteractionGuardTest jobTag rotation cases.
- `./gradlew test --offline --rerun-tasks` filtered to compat → 8/8.
- `python3 -m py_compile` on hierarchy/docker-smoke/screenshots-live/
  repack harnesses + `node --check` guild-portal.cjs → OK.

## Order-path atomicity (existing boundary, preserved)

`OrderBookRepository.commit` writes order_books + order_event_log +
order_resume_receipts + box releases in one SQLite transaction
(SqliteWriteTransaction; revision-guarded CAS). Audit entries are appended
after commit; LiveEvents only publishes committed changes. A cross-store
single transaction spanning physical stock/custody/audit is #140 scope
(PR3 #273) and is explicitly not faked here.

## Explicitly NOT proven here (CI/live-gated)

- Full 26.3 E2E (hierarchy/order-lifecycle/reserved/repack/sorting): needs
  Docker + Minecraft 26.3; runs in WeSort CI on push. Hierarchy + reserved +
  repack fixtures were updated for the mandatory UUID policy (marked boxes,
  scenario-conditional marking) but not executed here.
- Live 6b6t proof (#141): BLOCKED by environment; documented in
  docs/MC_26_3_NAVIGATION.md, never claimed PASS.
- Agent-side execution of automatic rename jobs (claim/rename/verify loop):
  Manager side (mint/cancel/list) is implemented and tested; the dispatch
  loop is PR6 scope.

## Round 7 additions (same commands, same green)

- Manager-owned workzone from the first tick: local corner/clear/delivery/
  drop-off picks are refused whenever the client is Manager-managed
  (`StashManager.managesWorkzones()`), not just after a directive revision.
- Lease-free sorting path is marker-gated, not flag-gated: `-Dwesort.e2e`
  alone grants nothing without the verified `wesort-e2e/installation.txt`
  fixture marker (cached verdict).
- `.wesort-navigation resume` removed: auto-recovery with backoff is the only
  path (status/cancel remain for diagnostics; the fixture driver keeps its own
  nav-resume hook).
- Box jobs: Owner cancel for PLANNED jobs; auto-mint is idempotent
  (one-active rule, re-mints after cancel while the box stays unmarked).

## Round 8 additions (same commands, same green)

- Incident regression test `NavigationRecoveryTest.loginChunkStreamingNeverParksForever`
  pins the 24/11 contract: RELEASED + unloaded-target are transient (never operator
  latches), backoff 5s→60s capped, non-unloaded hazards degrade after 3 retries.
- `.wesort-navigation resume` removed as interactive command (fixture driver keeps
  the programmatic hook); `clearWarehousePause` no longer references manual resume.
- Lease-free sorting requires the verified fixture marker, not just the JVM flag.
- `missingGuildDatabaseWithStrayJournalsPreventsStartup` restores SQLite-side
  startup fencing coverage independent of the removed JSON import.

## Round 9 additions (same commands, same green)

- W-010 stale-word sweep completed on the listed scope: `BotsPage.tsx`
  ("Transport is online" → "The connection is online"),
  `MC_26_3_JOBS.md` (fleet/failover wording + TRANSPORT.md reference removed;
  `MC_26_3_TRANSPORT.md` itself stays as the boundary doc for the removed
  subsystem), `MC_26_3_NAVIGATION.md` (långdistanstransport → global routing
  under W-036). Zero remaining fleet/transport/sign-stale hits in the scope.
- Workzone gate explicit everywhere a local pick can place physical work:
  box-targeted Delivery/Input picks now refuse first like the Exclude/
  drop-off/corner paths already did; the dead
  toggle/clearDelivery/Input/Anvil/CraftingTable entry points were deleted
  (no callers anywhere, incl. tests/harness) instead of gated. The
  "Residual (next PR)" carve-out is removed: no ungated local placement path
  remains.
- #197 doc sync: `MC_26_3_NAVIGATION.md` describes the chunk-await gate as
  implemented (waiting for chunks, no Baritone goal armed, watchdog ticking)
  and documents the fenced-journal design (bounded escalation: re-upload +
  60s repeat warnings after 5 min; unbounded fence by design — age never
  releases physical custody, #210–216). `WorldInteractionGuard.jobTag`
  fallback handling is unchanged vs main (fail-closed `"unknown"` preserved).
- Evidence this round (no Docker/Minecraft, no 6b6t access; live verification
  explicitly not required): `./gradlew test --offline --rerun-tasks`
  (client) → 57 files, **330 tests, 0 failures**; `./gradlew
-p stash-manager-server test --offline --rerun-tasks` → 79 files,
  **533 tests, 0 failures**; `npm --prefix web test` → 15 files,
  **113 tests, 0 failures**; `compileJava --offline` clean.
- Live 6b6t proof (#141) remains BLOCKED-by-environment and is not claimed;
  code paths (signed hello/heartbeat with control scope, lobby portal
  transit, read-only SURVEY_ONLY survey, preflight gate) are implemented and
  unit-tested. Soak ≥5400s and unified-transaction close-outs belong to
  later work and are not faked here.
