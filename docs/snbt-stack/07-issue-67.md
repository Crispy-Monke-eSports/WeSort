# Automatic UUID intake

Tracking issue: [#67](https://github.com/Crispy-Monke-eSports/WeSort/issues/67).

Stack position: 7/9. Base: `stack/snbt/06-issue-229-journal`.

## Scope

Implement automatic discovery, durable rename queue, native anvil readback, uniqueness, internal tracking, pending/blocked state and safe retry.

## Implementation status

This seed commit contains an implementation plan only. The linked issue remains the source of acceptance criteria. No production behavior or tests have been implemented by this seed commit.

## Completion gate

- [ ] Implement the linked issue's production changes.
- [ ] Add and run issue-specific verification, including native Minecraft 26.3 conformance where applicable.
- [ ] Review only this branch's incremental diff against its immediate stack parent.
- [ ] Confirm the final stack satisfies #224 and #230; no legacy data migration.

## Stack order

1. #224 — Architecture decision and stack entry point
2. #225 — Shared codec and typed fingerprints
3. #226 — Native storage observation
4. #227 — Client–Manager protocol
5. #228 — Manager database and offline lookup
6. #229 — Durable physical operations
7. #67 — Automatic UUID intake
8. #68 — UUID-free external export
9. #230 — Required integration gates
