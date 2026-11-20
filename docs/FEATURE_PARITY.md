# Feature parity: legacy web UI (`main`) → React + MUI operations dashboard (PR #94)

Source of truth: `stash-manager-server/src/main/resources/web/{app.js,index.html}` on `main`
vs `web/src` (React 19 + MUI v9, Material Design 2). Java endpoints verified in `*Routes.java`.
Status as of this PR.

Legend: ✅ implemented · ⚠️ partial · ❌ not carried over (with reason)

The product is an operations dashboard, not a storefront: Overview, Inventory
(grid + table), Orders, Bots, Operations, Settings. Inventory keeps one-click ordering.

## Overview (new control center, no legacy counterpart)

| Capability | Java endpoint | React implementation | Test | Status |
|---|---|---|---|---|
| Bot presence summary | `GET /api/v1/bots` | Overview cards + list | screenshot (live icons) | ✅ |
| Stash/region summary | `GET /api/v1/stashes`, `/api/v1/server` | Overview cards + chips | screenshot | ✅ |
| Open/needs-attention orders | `GET /api/v1/orders?stash=` (all visible) | Overview cards + latest list, partial-failure alert | screenshot + e2e | ✅ |
| Cross-stash failure visibility | same | `useAllOrders` reports failed stashes, never silent-empty | e2e | ✅ |

## Inventory (guild stock + ordering)

| Existing feature | Java endpoint | React implementation | Test | Status |
|---|---|---|---|---|
| Central item search | `GET /api/v1/index` | local search (no fake URL params) | e2e | ✅ |
| Item grid with counts | same | MUI cards, real 26.3 icons | e2e + screenshots | ✅ |
| Table view (dense) | same | MUI table + pagination (25/50/100) | screenshot | ✅ |
| Loose + whole-box + mixed breakdown | `orderCounts` (index) | per-item + per-stash breakdown, mixed never auto-packed | unit + e2e + screenshots | ✅ |
| Availability states (orderable/empty/stale/unverified) | `available` + `online` + query health | chips with text (not color-only), unknown ≠ empty | unit + screenshot | ✅ |
| Category filtering (creative tabs) | `tab` field in index bytes | filter drawer + desktop panel | screenshot | ✅ |
| Availability filter + sorting | same | availability, sort | screenshot | ✅ |
| Item details + quantity | — (client) | MUI dialog, per-stash table, stack size | e2e + screenshot | ✅ |
| Guild-wide cross-stash split | `POST /api/v1/orders` per stash | loose-first + whole single-content boxes; draft split commits in one step, each stash is its own backend order (deliberate: no cross-stash transaction — partial success keeps failed groups retryable with the same key) | e2e (2 groups, 2 keys) | ✅ |
| Cart draft (per user) | — (client) | Zustand persisted per-user draft drawer | unit + e2e | ✅ |
| No over-delivery from mixed shulkers | `OrderStore`/`PhysicalReservations` (server authoritative) | single-content rule for whole-box lines | unit (mixed negatives) | ✅ |
| 54-shulker capacity | `MAX_SHULKERS` server | client pre-check, server enforces | unit | ✅ |
| Checkout stepper (draft → details → review → confirmation) | `POST /api/v1/orders` | MUI Stepper + per-group state machine (`not_submitted/submitting/confirmed/uncertain/rejected`), exact retry, discard, reload-safe persisted keys+bodies | e2e | ✅ |
| Shulker names (`none`/`all`/`each`) | `normalizeNames` | all interactive, capped (50/row, 54 rows) | unit (limits) | ✅ |
| Purpose / recipient / comment | `OrderIntent` | validated (recipient regex ≤16, comment ≤500) | unit + e2e | ✅ |
| Package labels | `normalizePackageLabel` | ❌ no UI (see limitations) | — | ❌ |
| Idempotent order submission | `Idempotency-Key` header | key+body bound to full payload hash, reused only on exact match | e2e + Java integration | ✅ |
| Offline order queue (order while bot offline) | `orderCountsIncludingStale` + stale fallback | stale chips, queued message, claim still needs fresh | Java tests + screenshot | ✅ |

## Orders (order management)

| Existing feature | Java endpoint | React implementation | Test | Status |
|---|---|---|---|---|
| Order history + polling (15s) | `GET /api/v1/orders` | MUI table, search + status filter, pagination | e2e + screenshot | ✅ |
| Partial stash failure | same | explicit error, never silent-empty | e2e | ✅ |
| Order details + timeline | same | MUI dialog, items/recipient/message + timeline | e2e + screenshot | ✅ |
| Retry for failed/cancelled | `POST .../retry` | reachable button (was hidden behind open-state gate) | e2e | ✅ |
| Cancel / force-cancel + confirm | `POST .../cancel`, `/force-cancel` | confirm dialogs, result alerts, refresh | e2e | ✅ |
| Clear finished per stash | `POST /api/v1/orders/clear` | admin card, responsive | screenshot | ✅ |
| Order approvals display | `approvalState` | status chip | unit shape | ✅ |
| Order audit search (person/item/order/stash) | `GET /api/v1/audit/orders` | Operations → Order audit tab | screenshot | ✅ |

## Bots (monitoring + control)

| Existing feature | Java endpoint | React implementation | Test | Status |
|---|---|---|---|---|
| Bot list (online, player, caps, summary, scopes) | `GET /api/v1/bots` | MUI list + presence chips | screenshot | ✅ |
| Per-bot activity + events | `GET /api/v1/bots/{id}/activity` | tasks table + events list | screenshot | ✅ |
| Per-bot settings (schema + values, send changes) | `GET/POST /api/v1/bots/{id}/settings` | positions-gated card | screenshot | ✅ |
| Coordinates hidden without positions | `Redact` server-side | UI gates + no coordinate display | Java tests | ✅ |

## Operations (new operator surface)

| Capability | Java endpoint | React implementation | Test | Status |
|---|---|---|---|---|
| Global operations (stock/agent/order states, alerts) | `GET /api/v1/operations` | state tables + alert banner | screenshot | ✅ |
| Physical operations + inspect | `GET/POST /api/v1/physical*` | owner table + inspect action | screenshot | ✅ |
| Job queue | `GET /api/v1/jobs` | owner table | screenshot | ✅ |
| Handoffs / custody / deposits / box jobs | respective `/api/v1/*` | owner tables | screenshot | ✅ |
| Per-bot jobs view | `GET /api/v1/bots/{id}/jobs` | ❌ not surfaced (see limitations) | — | ❌ |

## Accounts, guild, system

| Existing feature | Java endpoint | React implementation | Test | Status |
|---|---|---|---|---|
| Login (owner blank / member username) | `POST /api/v1/auth/login` (`username`) | fixed payload, redirect preserved | e2e + Java integration | ✅ |
| First-run setup | `POST /api/v1/auth/setup` | setup variant (code + password) | Java integration | ✅ |
| Password change (+mustChange gate) | `POST /api/v1/auth/password` | Security section; 403 surfaced | Java integration | ✅ |
| Admin unlock / lock (elevated) | `POST /api/v1/auth/confirm`, `/lock` | Security section + AppBar state | screenshot | ✅ |
| Guild view / rename | `GET/POST /api/v1/guild` (POST owner-only) | Guild section (owner-gated) | screenshot | ✅ |
| User administration (roles, scopes, disable, reset, logout, delete) | `GET/POST /api/v1/users*` (owner-only) | Users tab: presets + full perms JSON edit | screenshot | ✅ |
| Server (single) + change confirmation | `GET/POST /api/v1/server` | Server tab, owner-only, confirm dialog | screenshot | ✅ |
| Stash definitions (create/edit/delete, revision conflicts, deliveryDimension) | `GET/POST /api/v1/stash-defs` | Stashes tab, owner-only, edit dialog | screenshot | ✅ |
| Link addon / regenerate key | `GET /api/v1/link*` (link perm / owner) | Connection section, confirm on regenerate | screenshot | ✅ |
| Quota usage display | `GET /api/v1/quotas/me` | Guild section usage list | screenshot | ✅ |
| Audit log viewer (`GET /api/v1/audit`) | owner route | ❌ no UI (see limitations) | — | ❌ |
| Minecraft disclaimer + branding | static assets | AppBar footer + login, byte-identical SVGs | smoke.cjs | ✅ |
| Minecraft icons | `GET /icon/<ns>/<path>.png` | `iconUrl` + retry + letter fallback | unit + Java integration + live screenshots | ✅ |
| Stash release | removed (no bot owns a stash) | — | — | ❌ |
| Manager kit templates + kit jobs | removed (backend + Manager link + mod UI) | — | backend tests | ❌ |
| Streamer mode | removed (server + client) | — | — | ❌ |

## Limitations (documented, not silent)

- **No audit-log viewer or per-bot jobs view.** The operator queues that matter for daily runs are covered; these two remain future work.
- **No package-label editor.** The backend field exists (`packageLabel: null` sent); no UI yet.
- **Order names follow the client `names` contract** (`none`/`all`/`each`, 50 chars); the W-064 server-owned `WeSort (X/Y) - text` naming remains a documented product blocker, not silently claimed.
- **`names.each` uses a capped row list** (54 rows); the bot re-plans from reachability anyway and names apply in order.
- **Guild shared-stock tables were removed by PR #89's unified direction**; the table view covers dense inspection instead.
- **Item variants beyond the index key** (enchantments/NBT) are not distinguishable client-side; whole-box auto-pack requires single-content keys and the Manager's physical allocation owns exactness at claim.
