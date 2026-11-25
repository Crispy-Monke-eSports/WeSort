# UUID-free external export

Tracking issue: [#68](https://github.com/Crispy-Monke-eSports/WeSort/issues/68).

Stack position: 8/9. Base: `stack/snbt/07-issue-67-uuid-intake`.

## Scope

Implement UUID-free physical export rename, fenced custody, verified handoff and atomic retirement; abort restoration and new UUID on actual return.

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
