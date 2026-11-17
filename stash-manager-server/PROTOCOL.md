# Manager API efter steg 0

Alla aktiva routes registreras av WeSortManager.routes; Route-regressionen
klassificerar varje inloggad route för permission och sekretess.
Detta protokoll är den bevarade baslinjen, inte framtida guildordermotor.

| Område | Aktiva routes |
|---|---|
| Browser-auth | GET auth/state; POST auth/setup, auth/login, auth/logout, auth/logout-all, auth/password, auth/confirm, auth/lock |
| Agent | POST index, heartbeat, orders/claim, orders/:id/status, delivery |
| Lager/webb | GET stashes, index, orders, delivery, link; POST stashes/release, rescan, link/regenerate |
| Order/webb | POST orders, orders/clear, orders/:id/cancel, orders/:id/force-cancel, orders/:id/retry |
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
