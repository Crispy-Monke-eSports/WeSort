# WeSort Manager API

Alla aktiva routes registreras av WeSortManager.routes.
För hela order- och leveransflödet, se [domänkontrakten](../docs/MANAGER_CONTRACTS.md).

## En server, en agent och kit på beställning

- Managern betjänar alltid exakt EN Minecraft-server, satt av ägaren i webb-UI:t
  (`GET/POST /api/v1/server`). Bot-anrop från en annan server avvisas med 409.
  Webbläsar-anrop tar bara `stash` (aldrig `server`); behörighetsscopet
  `stashes` är en lista av `{stash}`.
- Owner definierar flera stashes som den aktiva agenten kan arbeta i.
  Fysiska direktiv gäller en specifik stash och revision.
- Kit är on demand: ett kit är exakt en shulkerbox, packad vid beställning med
  mallens exakta slotplacering. Ägaren hanterar mallar (`GET/POST /api/v1/kits`,
  revisionsskyddade som stash-defs); ordrar tar `{"type":"kit","key":namn,"count":n}`
  vars komponenter reserveras från löst lager och vars layout pin_nas på ordern.
  Färdiga kitboxar lagras aldrig. Lokal shulker-packning för ordrar (KitBuilder
  i modden) packar exakt efter mall.
- Streamer-läget är borttaget: ingen `streamer`-permission, ingen maskering
  med opaka id:n. Positions-skydd via `positions`-permissionen finns kvar.
- Varje stash skapas av ägaren i webb-UI:t (`GET/POST /api/v1/stash-defs`)
  med Position 1 + Position 2 (kuben dem emellan är stashet) samt
  drop-off-kista, leveranspunkt, städ och arbetsbänk. Drop-off-kistan är där
  medlemmar lämnar items som ska sorteras in i stashet; den väljs på
  stash-nivå i Managern, aldrig i spelet. Boten får sitt direktiv
  (`{stash, dimension, pos1, pos2, dropoff(+Dimension), delivery(+Dimension), anvil, table, revision}`)
  i heartbeat-svaret och exekverar headless.
- Omkollning är inverterad: agenten rapporterar vilka kistor som finns (`chests:
[{x, y, z, readAt}]` i heartbeat) och Managern svarar med positionerna den vill
  ha omlästa (`recheck: [{x, y, z}]` i svaret, äldst först, max 128). En kista
  efterfrågas en gång per läsning — och igen när själva begäran blivit äldre än
  tröskeln, så en laggig server aldrig tappar en kista vars läsningar misslyckas.
  Aldrig lästa (`readAt: 0`) lämnas till agenten. Tröskeln är
  `WESORT_CHEST_MAX_AGE_SECONDS` (default 300).
- Självläkning utan klick: `POST /api/v1/orders/{id}/reconcile` löser
  `NEEDS_RECONCILIATION` (`target`: `cancelled`/`failed` avbryter alltid;
  `pickup_ready` endast med färsk observation utan öppna fysiska operationer).
  Samma bevis körs automatiskt vid varje färskt index (tillbaka till `packed`),
  öppna ordrar med oavslutade fysiska operationer flaggas automatiskt, och
  bekräftade cancels som inget håller verkställs efter 10 min tystnad.
  Operations-larmen omfattar även `ORDER_BLOCKED`, `DELIVERY_FULL`,
  `RESCAN_BLOCKED`, `UNKNOWN`, `MISSING`, `WITHDRAWN`, `APPROVAL_PENDING`,
  `CANCEL_REQUESTED`, `DISCONNECTED`/`AUTH_FAILURE` samt `STALE_RESCAN`
  (väntad, omläsning pågår) mot `STALE_AGE` (oväntad).

| Område         | Aktiva routes                                                                                                                                                       |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Browser-auth   | GET auth/state; POST auth/setup, auth/login, auth/logout, auth/logout-all, auth/password, auth/confirm, auth/lock                                                   |
| Agent          | POST index, heartbeat, orders/claim, orders/:id/status, delivery                                                                                                    |
| Lager/webb     | GET stashes, index, orders, delivery, link, server, stash-defs, kits; POST server, stash-defs, kits, rescan, link/regenerate                                        |
| Order/webb     | POST orders (lines: item, shulker eller kit), orders/clear, orders/:id/cancel, orders/:id/force-cancel, orders/:id/retry, orders/:id/approval, orders/:id/reconcile |
| Drift/agent    | POST bot/hello, bot/state, bot/results, bot/activity, bot/next; GET bot/next                                                                                        |
| Drift/webb     | GET bots, bots/:id/settings, bots/:id/jobs, bots/:id/activity; POST bots/:id/settings                                                                               |
| Administration | GET/POST users; POST users/owner, users/:id, users/:id/actions enligt UserRoutes; GET audit                                                                         |
| Assets         | GET /icon/:id och statiska resurser                                                                                                                                 |

API-routes börjar med `/api/v1/`. Se exakta regex/actions i respektive
\*Routes.java. Egen agent-settings använder endast `scope=plugin`; andra scopes
avvisas. Jobbkön kan bara bära settingsändringar. Gamla planner-, console- och
viewer-routes saknas och ger 404. Inga kommandon kan köas via gammalt API.

En lyckad inloggning utfärdar cookien efter lyckad sessionspersistens. Om
sessionsfilen inte kan skrivas svarar Manager med 503 och behåller tidigare
giltiga sessioner. Om detta inträffar under Owner-bootstrap kan Owner logga
in med det redan sparade lösenordet när skrivfelet har lösts.

Browserläsning kräver session; writes kräver dessutom POST, JSON,
`X-Stash-Manager: 1`, samma origin och tillämplig permission. CORS-preflight
nekas. Owner-only administration använder lösenordsbekräftelse.
Agentanrop signeras genom Signer; replay/skew/key-generation kontrolleras.
SignerTest och HTTP-regressionerna är protokollets körbara exempel.

En aktuell signerad kontrollsession kan rapportera en incidentkod i
`POST bot/next` under `activity.incident`. Manager visar giltiga koder i
`GET operations` för användare med aktivitetstillgång till agenten.
Koderna har inga koordinater eller credentials. Navigation som pausats,
väntan på 6b6t-portalen, fel server, overifierat inventarium och journal som
kräver avstämning har egna koder. Agentfrånvaro visas utifrån Managers
observerade närvaro.

Aktiva permissions: view, order, ordersAll, activity, settings, rescan, link,
guildOrders, positions samt stash-/bot-scope. Nycklar lämnas inte ut till sub-users.

IndexStore är observerat lager med heartbeat, OrderStore är befintliga orders.
DeliveryContents är observation av leveranskistans innehåll; dess data läggs
inte i lagersaldot. Fysisk leverans och framtida globala reservationer kräver
senare roadmapsteg. Det finns ingen Qwerte-datamigrering.

## W-018 physical stock observations

The signed index endpoint accepts optional `physicalVersion:1` and
`physicalSources`. See [physical observation semantics](../docs/MC_26_3_STOCK.md).
Each source supplies canonical id/dimension/parts, block kind, state, size,
items/shulkers, session/sequence, packet observedAt, full fingerprint and
epoch/baseRevision. A successful physical index returns stockEpoch/stockRevision.
A causal conflict returns signed HTTP 409 with the current epoch/revision; reread
physical containers before retrying. Cached witness retries cannot renew age.

Authenticated VIEW `GET /api/v1/stock` returns permission-filtered guild item
balances (observed, reserved, available, unknown), sources and freshness policy.
`reservationPrecision:aggregate` describes existing stash order holds. Physical
slot/box allocations and cross-stash ordering are later issues. Legacy aggregate
indexes remain readable but cannot prove orderable stock.

## W-023 physical operation receipts

The signed agent endpoint `POST /api/v1/physical/agent` accepts an agent UUID
and a journal record. A successful response returns a receipt with the
operation ID, committed revision, checksum and Manager state. Repeated
requests for that revision are idempotent. Changes to an existing operation
append evidence and preserve the original agent and server scope. A receipt
mirrors execution evidence. The stock API continues to use its own native
observations.

Owner sessions use `GET /api/v1/physical` for summaries and
`POST /api/v1/physical/inspect` with the operation ID, revision and checksum
to request a fresh inspection. The signed agent response delivers the
inspection only to the original agent. The agent supplies the exact native
source and player inventory evidence in its next journal revision and can
return the decision outcome in `result`. The Owner audit uses operation IDs.

## W-026 order receipts

The browser sends `Idempotency-Key` on order creation. Manager scopes the
key to the authenticated requester and durably saves the submitted order
and its first response together. The same key and request return the
original response. A different request with the same key returns 409.

A signed order status request may include `reportId` in its JSON body.
Manager scopes it to the order and agent. Older signed reports use a
content-derived key. Report receipts are committed with the state change.
A replay returns its committed response without advancing the order again.

`state` is the physical progress of a complete order. The legacy agent
report `delivered` describes warehouse staging. Manager requires exact
native staging receipts before exposing `PICKUP_READY`. An uncertain
report moves the order to `RECOVERING` (automatic rollback); a silent
rollback fails it after 30 minutes so its holds release on the next
verified observation. A packed order whose bot goes silent releases its
holds after 30 minutes while staying collectible.
The implemented terminal set is `PICKUP_READY` plus the verified sweep
release; `PICKED_UP`/`IN_TRANSIT`/`AWAITING_CUSTOMER`/`DELIVERED` exist
only as reserved stages for a future custody flow and are never emitted
by the current bot API.

## W-058 guild order review and destination

Owner can update `approvalPolicy` on `POST /api/v1/guild` using the current
profile revision. Values are `DIRECT` (default), `GUILD` and `ALL`.
Pending approvals hold stock and are omitted from agent claims. A reviewer
with `ordersAll` and access to the order's stash submits
`{"decision":"approve"}` or `{"decision":"reject"}` to
`POST /api/v1/orders/{id}/approval`. A repeated decision preserves the
original reviewer and time. Rejection before claim releases the hold.

An unlocked Owner session can confirm a member's Minecraft name via
`POST /api/v1/users/{memberId}/minecraft` with `{"account":"PlayerName"}`.
An empty string removes the binding. Explicit recipients require
`guildOrders` and an active binding. SELF uses the sender's active
binding by default, with warehouse pickup when no binding exists.
`recipient`, `deliveryMode` and `destination` describe the intended
delivery. Warehouse staging and verified delivery remain distinct
physical states.

## W-027 signed agent control

The Fabric agent sends `POST /api/v1/bot/hello` with its `botId`, player,
runtime metadata, and a random 32-byte `controlSession` encoded as
64 lowercase hexadecimal characters. The signed response advertises
`protocol: 2` and a `controlEpoch`. After a restart or rebinding, a
different epoch invalidates the agent's cached job receipts.

`POST /api/v1/bot/state` publishes the `plugin` schema and values with
their SHA-256 hashes. The current Fabric allowlist is `scan-interval`
(2–60 seconds), `fresh-minutes` (0–30 minutes), `order-messages`
(boolean), and `order-intake-paused` (boolean). The pause stops new order
claims and leaves a running order on its existing execution path. Resume
continues to require the normal warehouse and inventory checks.
A settings write from the browser requires its own session
and settings permission. The browser does not exchange the control session.
The agent's signed request body limit is 1 MiB for these endpoints. The
schema limit is 5,000 nodes and the settings scope limit is one.

`POST /api/v1/bot/next` is a non-blocking poll with `botId` and
`controlSession`. It returns at most one job. A settings job includes
`id`, `type: "set"`, `scope: "plugin"`, `path`, `value`, optional
`expect`, `sequence`, `lease` and `expiresAt` (Unix milliseconds).
Unacknowledged jobs can be polled again with the same lease for at most
60 seconds. The agent checks scope, expiry and the typed value before
applying the change on the game thread.

`POST /api/v1/bot/results` accepts one leased receipt per request.

```json
{
  "botId": "fixture",
  "controlSession": "<64 lowercase hex characters>",
  "results": [
    {
      "id": "<job id>",
      "sequence": 1,
      "lease": "<lease UUID>",
      "ok": true,
      "value": 12
    }
  ]
}
```

Manager replies 200 to an identical replay. A conflicting receipt,
wrong sequence, expired lease or mismatched session receives 409.
Protocol 1 remains available to agents that register without a control
session. Legacy warehouse endpoints continue to use the signed v1
exchange independently of the control session.

Warehouse staging persists an internal versioned manifest with the order
receipt. Manager derives it from completed `STAGE_PACKAGE` journal records
for the order, stash and packing agent. Each entry binds an exact shulker
stack fingerprint to an operation ID, revision, checksum and staging slot.
Manager compares the native player and chest slot readbacks and checks that
the reported staged lines cover the order before writing `PICKUP_READY`.
Partial staging keeps the evidence and requires reconciliation. The browser
receives the verified staged package count. The full package
identity plan and final delivery receipt require their own custody evidence.

The packing agent can read a completed staging manifest via signed
`POST /api/v1/bot/manifest`. Registration supplies a `manifestScope`
with the server, stash, dimension and Minecraft agent UUID. When that
binding changes, Manager rotates the control epoch and invalidates queued
control jobs. Reads take `botId`, `controlSession`, `controlEpoch` and
`orderId`. The live binding must match the agent that staged the order
and the dimensions in the proof (no stash ownership exists: every bot
may use every stash). An unavailable or out-of-scope manifest returns 404.

The reply includes the internal manifest hash and a stable `packageId`
for each journaled shulker. It carries the operation ID, revision,
checksum and native stack fingerprint. Staging coordinates and slots stay
in Manager. A manifest read grants no physical operation or custody lease.

After validating the reply, the agent sends the same scope and order
fields plus `manifestHash` to signed `POST /api/v1/bot/manifest/ack`.
Manager commits `acknowledgedAt` with the order and audits the first
acknowledgement. A repeated receipt returns the same timestamp after a
new control registration or Manager restart. A mismatched hash returns 409.

A control poll with the current epoch may carry `pendingManifest` with an
order ID and package count. Manager derives the hint from the durable
order state, so an agent can recover a missed acknowledgement after a
restart. The hint uses the same scope checks as the manifest endpoint.

## W-042 individually scoped agent authentication

The local Manager CLI issues a credential in the form
`<key-slot-id>.<secret>`. The slot ID is not a Minecraft UUID and has no
player ownership. The addon sends it in `X-SM-Auth` and derives its HMAC
signing key from the secret. **Only a valid, non-revoked signing key
authorizes agent access**; the reported Minecraft username and UUID are
not authentication factors. The latest valid username may be shown as
transient presence metadata in the Manager UI, not as a binding.
Configured server, defined stashes, control sessions, signed physical
work, journal and replay protection remain independently enforced.

The host can issue, rotate and revoke key slots and make scoped
credentials mandatory. That mode also rejects the shared HMAC identifier
`-`. All agent requests require HMAC signing. The credential registry,
scoped-only mode and revocations persist in the Manager volume. Names
are display-only and are not persisted as credential ownership.
Provisioning returns each secret once through the local CLI.

### One active named agent credential

The 6b6t Manager admits **at most one active named key slot**. Issuing a
replacement requires revoking the previous slot first. A persisted
credential file with more than one active key fails startup instead of
silently choosing an agent. Failed persistence must not issue an in-memory
key or retire a previously valid key. Revocation invalidates cached bot
presence only after durable credential persistence succeeds.

This is a **provisioning constraint**, not yet the complete single-agent
execution fence: the earlier shared signing-key path still exists until
the signed named-agent client/bootstrap and existing integration tests
are migrated. A subsequent change must eliminate that fallback before
claiming exclusive single-agent authentication.

### Stale reply fencing in WeSort Agent

The Fabric Agent binds every outgoing signed request to the Manager URL,
current Minecraft server and a one-way fingerprint of the exact signing
credential. A verified response from that **old** connection is discarded
if any of these settings change before the response is processed; it cannot
mark a replacement key as online, accept stale remote settings or authorize
physical work. The physical sorting lease uses the same connection
fingerprint plus player UUID. Manager independently rejects signed calls
made with a revoked agent credential and requires a fresh signed hello
after key replacement.

Agent unit tests cover rekey, retarget, wrong-server and blank-scope
responses. Manager HTTP tests cover old-key revocation and fresh-key hello.
These tests **do not** substitute for a real 6b6t inventory/portal E2E test.

`POST /api/v1/stock/return-intake` records an Owner's return decision
for a freshly verified root shulker in an ordinary visible chest or
barrel. The browser sends `source`, `path`, `slotRevision`, `expectedDecisionRevision`, and an
explicit Boolean `markUuid`. The receipt is tied to that slot revision and
references its native stack and snapshot fingerprints. Replaying the same
choice at the same revision returns the original receipt. A changed or
stale selection receives 409. With `markUuid=false` the receipt is
`VERIFIED_KEEP_NAME`. With `markUuid=true` it is `UUID_REQUESTED`.
This records the choice. Anvil execution and registration require a
separate verified physical operation.

Owner `POST /api/v1/stock/loss` accepts the loss of a `MISSING` or
`UNREACHABLE` source (`{source}`). The source becomes a zeroed `WITHDRAWN`
tombstone with a `lossAccepted: {by, at}` marker instead of poisoning its
row forever; the choice is idempotent and audit-logged as
`stock.loss.accepted`. A `MISSING` source nobody reaches for seven days
escalates to `WITHDRAWN` on its own. While a rescan is outstanding for a
stash, that server's stock stops aging at the freeze, so a slow full-stash
re-read is not punished as `STALE_AGE`; the freeze expires on its own when
the bot goes quiet.

`GET /api/v1/operations` distinguishes expected from unexpected staleness:
a `STALE_*` source with a rescan outstanding reports `STALE_RESCAN`, a new
failure `STALE_AGE`; alert counts are capped. Each visible bot also reports
`presence: [{botId, online, lastSeen, cause}]` where `cause` is `heartbeat`,
`poll`, `auth-failure` (rejected key retries) or `silent`. `GET /api/v1/bots`
carries the same `presenceCause` per bot. Owner `GET /api/v1/agents` keeps
revoked slots as tombstones `{id, label, active:false}` without secrets, so
the UI tells revoked apart from nonexistent. Revoking a key bumps the
`bots`/`presence` live channels at once.

`GET /health` splits liveness from data freshness: HTTP 503 only for real
blockers (still starting, sampler older than a minute, user registry
unavailable). Stale indexes/stock and stuck/recovering orders are 200 with
`warnings` (`STALE_INDEX`, `STALE_STOCK`, `STUCK_ORDERS`,
`RECOVERING_ORDERS`); `recovering` counts all orders in recovery while
`stuckOrders` only counts recovery without progress for ten minutes.

## W-063 physical box UUID identity

A shulker box whose anvil `CUSTOM_NAME` is exactly `WeSort-<canonical
UUID v4>` (43 characters) carries a visible inventory identity. The
Manager derives `hasUUID`/`boxUUID` from the actual server-observed
component patch; it never trusts a client-declared boolean. A syntactically
valid UUID is only `REGISTERED` after the Manager registers it with a
unique, physical provenance; otherwise it is `UNREGISTERED`, `INVALID`
(name present but not the `WeSort-` format), `DUPLICATE` (same UUID already
active elsewhere or reused from history) or `UNKNOWN` (no name). An order
box is deregistered after a server-confirmed rename and its UUID moves to
append-only history, never to be reused.

Owner `GET /api/v1/stock/source` exposes `boxIdentities` (path,
`hasUUID`, `boxUUID`, `identityStatus`) for each root-level shulker box in
a fresh source. Owner `GET /api/v1/stock/boxes` returns the active registry
and history. Registration is idempotent and atomic inside the physical
observation transaction; two identical nameless boxes stay position- and
fingerprint-identified and are never assigned the same UUID.

An invalid `WeSort-` label, a UUID outside its registered slot, or the same UUID
observed at multiple active locations quarantines the affected boxes and their
contents. They remain visible as observed stock. New reservations, offline
allocations and previously issued allocations checked at claim all use this
rule. Plain personal labels and unnamed boxes retain slot-based allocation.

Owner `POST /api/v1/boxes/jobs` starts a `REGISTER_BOX` or `RETURN_INTAKE`
job for a verified, unprotected, still-unreserved root shulker slot
(`server`, `stash`, `source`, `path`, `kind`). The Manager mints a fresh
UUID v4 and stores it as a `PLANNED` job; only one open box job is allowed
per server. `GET /api/v1/boxes/jobs` lists them. The signed agent endpoint
`POST /api/v1/boxes/agent` claims the job (`claim`) and advances it with
`renamed`, `complete` or `uncertain`. `renamed` moves the job to
`VERIFYING`; `complete` only succeeds after a fresh observation has
re-registered the issued UUID at the exact source and path (the anvil
rename itself is a verified physical operation passing W-061's guard). A
Manager restart or expired lease fences an in-flight job to
`NEEDS_RECONCILIATION`; it is never silently reassigned.

## W-064 user-chosen order box labels

The user chooses the text part of an order's shulker-box names; the Manager
always generates the prefix `WeSort (X/Y) - `, where `X` is the 1-based box
number and `Y` the frozen package total (`shulkersNeeded`, never above 54).
The anvil limit is 50 UTF-16 code units and the longest prefix
`WeSort (54/54) - ` is 17, so the user text is capped at 33 code units.
`POST /api/v1/orders` accepts an optional `packageLabel` of
`{"text":"..."}` (common suffix, the normal flow) or
`{"texts":["...", ...]}` (a suffix per box). The Manager trims, defaults
empty to `Beställning`, rejects `§`, control characters, bidi/invisible
format characters and broken surrogates, and stores the label plus the
frozen `packageTotal` on the order. Full names are generated on the server
(`Order.generatedNames()`), never from free client formatting; the client
cannot replace the prefix or renumber boxes. Legacy orders using
`names.all|each` remain readable unchanged. The anvil rename is best-effort:
the agent reports boxes it could not name (`unnamedNote`: no anvil set, not
enough XP), and `PICKUP_READY` is gated on staged lines, the frozen package
total and a settled journal — not on the names. Unmarked boxes are flagged in
the delivery report, never silently passed off as marked.

## 6b6t navigation and delivery boundary

WeSort Manager does **not** expose `/api/v1/transport` or manage
general-purpose `/tpa` and `/home` profiles. The single Fabric agent
uses guarded Baritone navigation and the dedicated 6b6t lobby-portal
bootstrap. Long-distance pickup/delivery and verified arrival remain
explicit work under W-035/W-036/W-038/W-039; emitting a chat command
or starting a route is never proof of delivery.

## Orders without withdrawal quotas

WeSort uses a shared guild inventory with no personal withdrawal quota,
quota policy or quota override. The removed `/api/v1/quotas` and
`/api/v1/quotas/me` endpoints are intentionally not supported.
Physical stock reservation, stale-source checks, authenticated member
orders, verified delivery and audit are **still mandatory** and are
not replaced by member-specific balances.

## W-029 package custody

Each physical package has a Manager-owned `packageId` (never a Minecraft
UUID and never the visible `WeSort (X/Y) - text` label) and an append-only
custody chain: `STAGED → PICKUP_VERIFIED → IN_TRANSIT → DEPOSIT_VERIFIED`
or `PLAYER_HANDOFF_PENDING`, plus `NEEDS_RECONCILIATION`. Owner
`GET /api/v1/custody` lists packages (optionally by `order`). The signed
agent endpoint `POST /api/v1/custody/agent` stages or advances a package
(`stage` or a state name). Custody is mutually exclusive: after an agent
picks a package up, only that agent — or an explicit reconciliation — may
advance it, so two couriers can never claim the same package and a package
is never booked in two places. A reboot or a replayed receipt does not
change custody.

## W-037 delivery points

A delivery point is a chest or a player/project meeting place at
`server` + `dimension` + `x`/`y`/`z`, with an access class (`MEMBER`,
`PROJECT`, `INTERNAL`) and a status (`PENDING`, `VERIFIED`, `PAUSED`).
`POST /api/v1/delivery-points` (Owner) creates, updates (`action` +
`id` + `expectedRevision`) or pauses a point; updates are optimistic
(409 on a stale revision) and every mutation appends a history event.
Owner `GET /api/v1/delivery-points` lists every point with coordinates
and history. Member `GET /api/v1/delivery-points/selectable` returns
only verified `MEMBER` (their own) and `PROJECT` points, redacted —
coordinates and dimension are removed and a secret `INTERNAL` stash
never appears. Selecting a point never changes who owns the rest of
the stock.

## W-038 physical deposition

A package is deposited as either a `DELIVERY` to a recipient outside
the stock (a withdrawal) or a `TRANSFER` to an internal guild stash (a
relocation that does not change the guild total); the kind is explicit.
`POST /api/v1/deposits/agent` (signed) begins, confirms or reconciles a
deposit keyed by `packageId`; `begin` is idempotent per package + order

- kind + destination. `DELIVERED` is reached only with a server-
  confirmed, non-empty chest content `snapshot`, so a double report or
  reconnect never double-delivers: replaying a confirmation returns the
  same record. An uncertain outcome goes to `NEEDS_RECONCILIATION`, and a
  second agent cannot complete another agent's deposit. Owner
  `GET /api/v1/deposits` lists the ledger (optionally by `order`).

## W-034 job scheduler

Jobs carry a category `SCAN → SORT → PACK → PICKUP → DELIVER` (plus the
`RECONCILE` override) and a work-unit `chain`; a step is only claimable
once its predecessor for the same chain is COMPLETED, so a lone agent
chains every step without a handover. `POST /api/v1/jobs/agent` (signed)
allocates work atomically: a claim returns the highest-priority eligible
job with a lease token and deadline, no agent ever holds two physical
transactions, and two agents can never claim the same job. `renew`
extends the lease; a lapsed lease moves the job to
`NEEDS_RECONCILIATION`, and it is only reassignable after the Owner
reconciles it back to `QUEUED` (`POST /api/v1/jobs`, `reconcile`), so a
blind restart of an uncertain physical step is impossible. `pause`,
`resume` and `revoke` round out the controls. Owner `GET /api/v1/jobs`
shows the queue ordered by state, priority and age.

Lease expiry uses elapsed monotonic time. Restarting Manager or revoking a held
job fences both claimed and paused work in `NEEDS_RECONCILIATION`. The old
token cannot renew, resume or complete it. The incident and its cause persist
across subsequent restarts; the single work slot stays occupied until reconciliation.

## W-039 player handoff

A package handed to a player at a registered meeting place is tracked
separately from a chest deposit. `POST /api/v1/handoffs/agent` (signed)
reports `arrive`, `no-show` or `drop`; a drop without a verified pickup
never reaches DELIVERED — it goes to `NEEDS_RECONCILIATION`. DELIVERED
requires an explicit recipient receipt (`confirm`) from the order
owner/admin, recorded as `confirmedBy`; a replay is idempotent. A
no-show is traceable and the stock is not returned until an explicit
`RETURNED`, so there is no false credit. Only the recorded agent may
advance the handoff; a second agent cannot take over the cargo. Owner
`GET /api/v1/handoffs` lists handoffs (optionally by `order`).

## Backups and crash recovery

Manager does **not** provide an in-process backup/restore command or
backup REST endpoint. The former unused `Backups` utility has been removed.
An operator may copy the private Manager data volume only when the Manager
process has been fully **stopped**, including the SQLite database and any
WAL files; a live filesystem copy is not an atomic SQLite backup.

This is separate from **mandatory crash recovery**: physical intents,
leases, stock reservations and order reconciliation must survive process
failure. An unknown physical outcome must remain blocked as
`NEEDS_RECONCILIATION` until server-observed state permits safe progress.
The transactional persistence refactor is tracked in #140.
