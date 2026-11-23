# Autonomous order rollback (no operator reconciliation)

The old `needs_reconciliation` **order workflow** is replaced by autonomous
`RECOVERING` (reported externally as `in_progress`), followed by terminal
`FAILED` or `CANCELLED`. The Owner **Reconcile** and **Inspect** actions and
reconciliation alerts are removed from the API and UI. Historical order-book
states are still understood solely to migrate existing installations safely.

## State machine

1. An impossible, interrupted, cancelled, or timed-out order enters rollback.
   The Manager's 15-second background sweep works without an open dashboard.
2. The agent stops packing or crafting. A durable recovery checkpoint identifies
   each verified package staged in delivery storage by source, slot and exact
   item fingerprint. An interrupted transfer is **never blindly replayed**.
3. The agent retrieves staged packages using exact-match inventory moves and
   returns them and other tracked leftovers to eligible stash containers through
   the sorter's existing inventory/return verification path.
4. Once no staged package remains outstanding and no tracked carried item or
   unsettled physical operation exists, the agent reports `FAILED` or
   `CANCELLED`. The Manager preserves physical reservations until an additional
   fresh source observation confirms they can be released.
5. The queue can then claim the next order, without human input.

## Physical journal recovery

The physical operation journal retains crash fences internally: a journal is
an anti-duplication guarantee, not an operator work queue. When its agent
reconnects, the Manager automatically issues the same revision/checksum-bound
**read-only inspection** that used to require a browser click. A failed
inspection is retried with at least a 60-second backoff; a successful proof
clears the no-mutation recovery fence. No inventory click is retransmitted
merely because the agent restarted.

## Limits and fail-closed behavior

- An offline bot cannot physically put anything back. Recovery resumes upon
  reconnect. Reservations for inaccessible or unverified items are *not*
  discarded to make an error disappear.
- The agent cannot move items out of an unloaded chest, invent spare chest
  capacity, or undo ingredients already consumed by crafting. It must verify
  the actual remaining stock/output instead.
- If a stored package cannot be uniquely identified, the agent must not
  withdraw someone else's similarly named shulker. The saved original
  reservation and recovery checkpoint remain pending, instead of risking
  inventory loss or duplication.
- The next order is only claimed when the previous physical rollback has
  actually finished; timeouts alone do not constitute confirmation.

This is a fully **unattended** attempt-and-retry workflow, rather than a
promise that a disconnected Minecraft client can always recover immediately.

## Verification

- `./gradlew -p stash-manager-server test`
- `./gradlew test`
- `bun --cwd web test`
- Full Minecraft 26.3 E2E is required before merging: abort packing after
  source fetch; abort after one or more packages were staged; cancel during
  crafting; interrupt during a physical menu click; restart Manager/agent
  mid-rollback; remove delivery/storage capacity; and place a second order
  while cleanup runs. Confirm exact item conservation and no duplicate replay.
