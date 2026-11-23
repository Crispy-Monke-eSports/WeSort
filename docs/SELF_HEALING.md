# Autonomous physical reconciliation

The Manager reconciles orders and physical operation journals without an Owner clicking
**Reconcile** or **Inspect**. Recovery is conservative: it never replays a Minecraft
inventory click, assumes that a retained source stack means an order was packed, or
releases a physical reservation on a timeout alone.

## Recovery loop

- A 15-second Manager sweep runs independently of the dashboard. Startup and every fresh
  inventory observation continue to use the same recovery path. Order transitions are
  written durably and the sweep is idempotent.
- A stale cancel request without physical allocations is cancelled after ten minutes.
  With physical allocations, the request first becomes `NEEDS_RECONCILIATION`, while
  the reserved roots remain protected. Its original `cancelRequestedAt` survives.
- An agent mirroring a fenced physical journal receives an automatic, revision-pinned
  `INSPECT` decision. The agent performs **read-only** exact-source and player-inventory
  verification; it must not retransmit the interrupted mutation. Requests are idempotent
  while pending and failed inspections retry no more than once per minute.
- Once all journaled work for an order is settled, **each reserved source** must have a
  trusted fresh observation newer than the reconciliation fence. The Manager then
  cancels a requested cancellation, or marks an otherwise unprovable order as failed.
  The virtual slot holds are released in that same durable transition. Abandoned orders
  are never reported as `PICKUP_READY` merely because original items still exist.
- A crash-recovered physical journal with **no sent mutation** may clear its client-side
  recovery fence after successful read-only inspection, without an operator toggle.

## Safety boundaries

Some physical uncertainty cannot be resolved from Manager data alone. An offline bot,
a missing/unreachable source, a mismatched cursor, or a partially completed sorting
workflow without a proven final state remains fenced. The Manager continues to retry
safe inspections when the agent connects, but does **not** erase evidence or release
unverified roots to hide an alert. An operator may still inspect exceptional cases.

The automated sweep changes **order metadata and virtual reservations only**. Physical
item movement always happens in the agent under its existing journal/lease protocol.
Auto-recovery is thus opportunistic, not a guarantee that inaccessible Minecraft
world state can be reconstructed.

## Verification

- `./gradlew -p stash-manager-server test`
- `SelfHealTest`: fresh readback fails unproven packing, times-out cancellations settle,
  and a missing fresh readback retains its hold.
- `PhysicalOperationsTest`: automatic inspection, duplicate prevention, backoff, and
  exact revision-pinned journal receipt.
- Live test: interrupt a DIRECT physical operation, restart Manager and agent, then
  verify an `INSPECT` directive is issued and a new verified inventory observation
  settles the order without any dashboard action. Repeat with a missing source to
  verify the operation remains fenced.
