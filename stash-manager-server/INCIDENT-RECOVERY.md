# Production recovery and data integrity

## Account registry

A damaged `Data/users.json` no longer prevents the Manager from listening.
Only the independently authenticated owner remains available; all sub-user
sessions and Minecraft account mappings are denied, and registry mutations
fail. `/health` is degraded with `userRegistryUnavailable: true`.
The damaged file is left unchanged. Do not manufacture a replacement from
the schema or automatically fall back to an older permission snapshot:
that could resurrect revoked accounts or broaden access.

Stop the Manager, preserve the damaged file and the complete data directory
(including the SQLite database and any WAL), restore a verified current
registry from a trusted backup, and restart. If the only available backup
is older, review every account, permission, mapping and session before
restoring service. Use a consistent offline backup or SQLite's backup
mechanism, never copy a live database file without its WAL.

## Physical recovery

A denied sorting lease is not an authentication failure and now has its own
bounded retry schedule. Control traffic continues independently and can
register a fresh session after Manager restart. Do not toggle the agent
or clear the order book just to work around a rejected lease.

`/health` distinguishes `reservationHoldingOrders` (orders that actually
hold allocations) from `stuckOrders` (lifecycle recovery). A released
PICKUP_READY order waiting for a recipient is not stuck. The physical
journal and current observations remain authoritative. An allocated order
cannot be safely force-dropped merely because a timeout expired: the agent
might still hold real items or have performed a move. Keep the reservation
until recovery, verified observation and journal settlement prove release.
If the agent cannot return, use the physical incident recovery workflow;
do not edit the order state or delete the lease by hand.

## Authoritative order storage

`Data/guild.sqlite`, table `order_books`, is the authoritative order
store. Files in `Data/orders/*.json` are legacy import inputs, not live
mirrors. After the one-time migration they are ignored.

Preserve `Data/guild.sqlite.initialized` with the complete data directory.
Once this external marker exists, a missing SQLite file (or orphaned
WAL/SHM files) prevents startup instead of creating an empty database
and re-importing stale JSON. Restore a consistent backup; do not delete
the marker to bypass recovery. On upgrade the marker is installed before
order import or physical work. Loss of a database predating this marker
cannot always be distinguished from an original legacy installation.

`order_resume_receipts` retains agent-scoped terminal dispositions
independently of the bounded operational archive. It is part of the same
transaction as the order snapshot and audit log. Do not purge it while
agents may retain checkpoints. The upgrade backfills surviving terminal
orders; it cannot reconstruct already evicted dispositions without proof.

For read-only diagnosis (prefer a consistent backup of the database):

```sql
SELECT server, stash, revision FROM order_books;
SELECT server, stash, revision, payload FROM order_books;
```

Do not change the database manually, or use the old JSON files to infer
whether an order migration/release succeeded. Keep the Manager/agent
versions aligned. Monitor both health and signed agent presence.
