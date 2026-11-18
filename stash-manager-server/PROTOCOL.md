# Manager API efter steg 0

Alla aktiva routes registreras av WeSortManager.routes; Route-regressionen
klassificerar varje inloggad route för permission och sekretess.
Detta protokoll är den bevarade baslinjen, inte framtida guildordermotor.

| Område | Aktiva routes |
|---|---|
| Browser-auth | GET auth/state; POST auth/setup, auth/login, auth/logout, auth/logout-all, auth/password, auth/confirm, auth/lock |
| Agent | POST index, heartbeat, orders/claim, orders/:id/status, delivery |
| Lager/webb | GET stashes, index, orders, delivery, link; POST stashes/release, rescan, link/regenerate |
| Order/webb | POST orders, orders/clear, orders/:id/cancel, orders/:id/force-cancel, orders/:id/retry, orders/:id/approval |
| Drift/agent | POST bot/hello, bot/state, bot/results, bot/activity; GET bot/next |
| Drift/webb | GET bots, bots/:id/settings, bots/:id/jobs, bots/:id/activity; POST bots/:id/settings |
| Administration | GET/POST users; POST users/owner, users/:id, users/:id/actions enligt UserRoutes; GET audit |
| Assets | GET /icon/:id och statiska resurser |

API-routes börjar med `/api/v1/`. Se exakta regex/actions i respektive
*Routes.java. Egen agent-settings använder endast `scope=plugin`; andra scopes
avvisas. Jobbkön kan bara bära settingsändringar. Gamla planner-, console- och
viewer-routes saknas och ger 404. Inga kommandon kan köas via gammalt API.

Browserläsning kräver session; writes kräver dessutom POST, JSON,
`X-Stash-Manager: 1`, samma origin och tillämplig permission. CORS-preflight
nekas. Owner-only administration använder lösenordsbekräftelse.
Agentanrop signeras genom Signer; replay/skew/key-generation kontrolleras.
SignerTest och HTTP-regressionerna är protokollets körbara exempel.

Aktiva permissions: view, order, ordersAll, activity, settings, rescan, link,
streamer samt stash-/bot-scope. Planner/proxy/console/viewer ger ingen åtkomst.
Servern maskerar känsliga positions-/identitetsfält för forced streamer;
webbens blur är ett extra skydd. Nycklar lämnas inte ut till sub-users.

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
report leaves the order in `NEEDS_RECONCILIATION`.

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
{"botId":"fixture","controlSession":"<64 lowercase hex characters>","results":[{"id":"<job id>","sequence":1,"lease":"<lease UUID>","ok":true,"value":12}]}
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
`orderId`. The live binding must match the current stash owner, the
agent that staged the order and the dimensions in the proof. An
unavailable or out-of-scope manifest returns 404.

The reply includes the internal manifest hash and a stable `packageId`
for each journaled shulker. It carries the operation ID, revision,
checksum and native stack fingerprint. Staging coordinates and slots stay
in Manager. A manifest read grants no physical operation or custody lease.

After validating the reply, the agent sends the same scope and order
fields plus `manifestHash` to signed `POST /api/v1/bot/manifest/ack`.
Manager commits `acknowledgedAt` with the order and audits the first
acknowledgement. A repeated receipt returns the same timestamp after a
new control registration or Manager restart. A mismatched hash returns
409.

A control poll with the current epoch may carry `pendingManifest` with an
order ID and package count. Manager derives the hint from the durable
order state, so an agent can recover a missed acknowledgement after a
restart. The hint uses the same scope checks as the manifest endpoint.

## W-042 individually scoped agent authentication

The local Manager CLI issues a credential in the form
`<minecraft UUID>.<secret>`. The addon uses the UUID as the identifier
in `X-SM-Auth` and derives the signing key from the secret. A request
signed this way must match the credential's player, server, authorized stash and
agent or dashboard identity as required by the route. Agent credentials
use the same HMAC request, response and replay checks as the v1 protocol.

The host can rotate a credential for the same Minecraft account and server,
including replacing the full list of authorized stashes,
revoke it, and make scoped credentials mandatory. In that mode the
legacy identifier `-` and legacy Bearer credentials are denied. The
credential registry, scoped-only mode and revocations persist in the
Manager volume. Web responses contain connection and permission
metadata. Provisioning returns its secret through the local CLI.

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
