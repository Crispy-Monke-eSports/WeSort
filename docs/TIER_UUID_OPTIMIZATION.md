# WeSort Agent: tier-based shulker optimization and queued orders

## Operator behavior

Agent storage is organized by **tiers**. Minecraft signs are ordinary inventory
items. The signed Manager settings API exposes tier sorting through
`sort-enabled`, which defaults to true for new Agents. If the operator previously disabled that setting explicitly,
the saved value is still respected.

**Physical optimization is not an unconditional override of safety controls.**
It only runs with a current, authenticated Manager automation lease, confirmed
inventory readback and a clear physical journal. When an active or queued
physical order exists, that order has priority and the agent continues
read-only scanning; the active order's physical reservations cannot be
overwritten by maintenance moves. On completion, the Agent re-checks its
sources before resuming optimization.

### Deposit chests

Drop-off containers are the intake source for the tier sorter. They can be
read even while sorting is disabled. Emptying requires `sort-enabled`, free
inventory carry slots, a verified unprotected destination with capacity,
and all physical safety gates. When no safe destination exists, the items
remain in the deposit chest; no inventory operation is forced.

### Shulker packing and UUID names

- Tier sorting can run repacking and compaction.
- Mixed normal boxes, and mixed boxes with a **canonical** WeSort UUID name,
  can be cleaned; content groups are compared by the exact `BulkKey`, not
  by a chest sign.
- The compactor can gather full stacks of one kind into an unnamed box,
  or top up a partial WeSort UUID box with matching stacks.
- A UUID top-up plan pins that **exact** name across carry-slot changes and
  persistent crash recovery; it never swaps to a different named box.
- Kit/order-claimed shulkers, protected stock, non-WeSort custom names and
  malformed UUID labels are not repacked.
- A new box receives its own WeSort UUID from the existing idle naming
  workflow when an anvil, XP, inventory room and validated permissions are
  available. Empty boxes stay unnamed as spare containers.

The implementation is incremental: it uses verified `Compactor` and
`PhysicalWork` transactions with conservation checks, rather than moving
unverified contents or touching every registered box simultaneously.

## Website orders

If a physical order is already running, new orders are **persisted in a
pending, unreserved queue** rather than failing with HTTP 409. They are
labelled `reservationDeferred: true`, and the UI receives a clear message
that no stock is guaranteed. Only a future claim under the observation lock
may reserve _fresh_ stock and begin packing, in FIFO order. The original
physical order's protected roots are not changed.

If an agent disappears mid-cancel, an _unallocated_ order can still be
auto-cancelled after the existing timeout. An allocated order cannot
honestly be declared cancelled without physical evidence. After ten minutes
of no response it transitions from a stuck `CANCEL_REQUESTED` into
`NEEDS_RECONCILIATION`. Its physical roots stay held until a verified
reconciliation; the transition is rechecked on Manager restart and dashboard
polling. This is not a silent reservation release.

## Required on-server validation

1. Place order A, then B while A is packing: B appears pending/unreserved.
2. Complete A and upload a fresh source observation: B can reserve and claim
   without double booking.
3. Disconnect/restart an Agent with an outstanding cancellation: the
   allocated order eventually needs reconciliation, rather than remaining
   `CANCEL_REQUESTED` indefinitely.
4. With no order, a valid sorting lease and available chest room: verify
   deposit input empties and tier compaction starts without signs.
5. Verify separately: unnamed and valid WeSort UUID shulkers can be grouped;
   non-WeSort labels, kit boxes and reserved contents are untouched.
6. Force a crash during UUID compaction and confirm the exact UUID box is
   recovered without duplication, corruption or unsafe inventory clicks.
