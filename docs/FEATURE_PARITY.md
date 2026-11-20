# Feature parity: legacy web UI (`main`) → React storefront (PR #94)

Source of truth: `stash-manager-server/src/main/resources/web/{app.js,index.html}` on `main`
vs `web/src`. Java endpoints verified in `*Routes.java`. Status as of this PR.

Legend: ✅ implemented · ⚠️ partial · ❌ not carried over (with reason)

## Shop (unified webshop, PR #89 behavior)

| Existing feature | Java endpoint | React implementation | Test | Status |
|---|---|---|---|---|
| Central item search (`@ns`, `=exact`, fuzzy) | `GET /api/v1/index` | `ShopPage` + `?q=` search param | vitest `matches` via e2e + e2e filter | ✅ |
| Item grid with counts | same | row-virtualized `ProductCard` grid | e2e | ✅ |
| Loose + boxed aggregated counts | `orderCounts` (index) | `shelfCount`/`boxedContents` | unit + e2e | ✅ |
| Tooltips with boxed breakdown | same | Radix `InfoTip` | e2e (title) | ✅ |
| Category filtering (creative tabs) | `tab` field in index bytes | sidebar categories from data + `?cat=` | screenshot | ✅ |
| Availability filter + sorting | same | `?avail=`, `?sort=` (count/creative/name/id) | unit + screenshot | ✅ |
| Buy dialog with amount + quick amounts | — (client) | `Dialog` + `QtyInput` | e2e | ✅ |
| Shift-click / double-click quick-add | — (client) | card handlers + always-visible `+` (touch) | e2e + screenshot | ✅ |
| Cart: add/update/remove/clear/persist | — (client) | Zustand per-user+stash store, validated | unit + e2e | ✅ |
| Automatic whole-shulker splitting | `OrderStore`/`PhysicalReservations` (server authoritative) | `splitItemLine`/`takeBoxes` preview | unit + e2e | ✅ |
| 54-shulker capacity + no over-delivery | `MAX_SHULKERS` server | client pre-check, server enforces | unit | ✅ |
| Checkout wizard (cart → delivery → review → place) | `POST /api/v1/orders` | TanStack Form + Zod, step guards | e2e | ✅ |
| Shulker names (`none`/`all`/`each`) | `normalizeNames` | all interactive; `each` rows estimated (bot re-plans anyway) | unit (limits) | ✅ |
| Purpose / recipient / comment | `OrderIntent` | Zod-validated (recipient regex, ≤500) | unit + e2e | ✅ |
| Package labels | `normalizePackageLabel` | ❌ no UI (see limitations) | — | ❌ |
| Quota override checkbox | — (no server support on `main`) | removed (was a no-op; PR #84 unmerged) | — | ❌ |
| Quota usage display | `GET /api/v1/quotas/me` | Settings → usage list | screenshot | ✅ |
| Idempotent order submission | `Idempotency-Key` header | header sent, key reused on uncertain retry | unit + e2e + Java integration | ✅ |
| Offline order queue (order while bot offline) | `orderCountsIncludingStale` + stale fallback | queued message, claim still needs fresh | Java tests + e2e queued msg | ✅ |
| Order history + polling (3s/15s) | `GET /api/v1/orders` | TanStack Query, `OrderRow`, details dialog | e2e | ✅ |
| Order actions (cancel / force-cancel / retry / clear) | `POST .../cancel`, `/force-cancel`, `/retry`, `/orders/clear` | details dialog + admin clear | integration (endpoints exist) | ✅ |
| Order approvals display | `approvalState` | badge in details | unit shape | ✅ |
| Order audit search (person/item) | `GET /api/v1/audit/orders` | officer-only audit panel | guild-portal e2e | ✅ |
| Stash status (online, blocker, delivery, naming) | `GET /api/v1/stashes` | sidebar status line + bot strip info | screenshot | ✅ |
| Manual rescan | `POST /api/v1/rescan` | sidebar button (rescan perm) | — | ✅ |
| Stash release | removed (no bot owns a stash) | — | — | ❌ |
| Guild shared-stock tables + per-stash drilldown | `GET /api/v1/stock` | ❌ removed by design (PR #89 unified shop; endpoint untouched) | — | ❌ |

## Bots, activity, operations

| Existing feature | Java endpoint | React implementation | Test | Status |
|---|---|---|---|---|
| Bot list (online, player, caps, summary, scopes) | `GET /api/v1/bots` | Bots page selector list | screenshot | ✅ |
| Per-bot activity feed | `GET /api/v1/bots/{id}/activity` | Bots detail + Activity page | screenshot | ✅ |
| Per-bot settings (schema + values, send changes) | `GET/POST /api/v1/bots/{id}/settings` | Bots detail (positions-gated) | screenshot | ✅ |
| Per-bot jobs view | `GET /api/v1/bots/{id}/jobs` | ⚠️ not surfaced (see limitations) | — | ⚠️ |
| Global operations/activity | `GET /api/v1/operations` | ❌ Activity page is per-bot (see limitations) | — | ❌ |
| Physical operations / inspect | `GET/POST /api/v1/physical*` | ❌ no UI (see limitations) | — | ❌ |
| Manager kit templates + kit jobs | removed (backend + Manager link + mod UI) | — | backend tests | ❌ |
| Delivery points / deposits / handoffs / custody / transport / boxes | respective `/api/v1/*` | ❌ no UI (see limitations) | — | ❌ |

## Accounts, guild, system

| Existing feature | Java endpoint | React implementation | Test | Status |
|---|---|---|---|---|
| Login (owner blank / member username) | `POST /api/v1/auth/login` (`username`) | fixed payload, redirect preserved | e2e + Java integration | ✅ |
| First-run setup | `POST /api/v1/auth/setup` | setup variant (code + password) | Java integration | ✅ |
| Password change (+mustChange gate) | `POST /api/v1/auth/password` | settings form; 403 surfaced | Java integration | ✅ |
| Admin unlock / lock (elevated) | `POST /api/v1/auth/confirm`, `/lock` | settings panel + header badge | screenshot | ✅ |
| Guild view / rename | `GET/POST /api/v1/guild` (POST owner-only) | settings (owner-gated) | screenshot | ✅ |
| User administration | `GET/POST /api/v1/users*` (owner-only) | owner-gated list + create | screenshot | ✅ |
| Audit log viewer | `GET /api/v1/audit` | ❌ no UI (see limitations) | — | ❌ |
| Link addon / regenerate key | `GET /api/v1/link*` (link perm / owner) | settings panel | screenshot | ✅ |
| Themes (dirt Classic + clean look) | `GET /dirt.png` + `dirt.svg` | Modern Dark (default) + Classic themes | screenshots | ✅ |
| Streamer mode | removed (server + client) | — | — | ❌ |
| Single-server operation | `GET/POST /api/v1/server` | Settings → server panel | — | ✅ |
| Stash definitions (region cube + placements) | `GET/POST /api/v1/stash-defs`, heartbeat `directive` | Settings → stashes panel | screenshot | ✅ |
| Minecraft disclaimer + branding | static assets | footer + login, byte-identical SVGs | smoke.cjs | ✅ |
| Minecraft icons | `GET /icon/<ns>/<path>.png` | `iconUrl` + retry + letter fallback | unit + Java integration | ✅ |

## Limitations (documented, not silent)

- **No physical ops, delivery/deposit/handoff/custody/transport/box managers, global operations view, audit-log viewer, per-bot jobs view, or package-label editor.** These are owner/operator consoles with large surface area; rebuilding each was out of scope. Follow-ups can add them one screen at a time behind the same design system.
- **`names.each` uses an estimated shulker count** (client `shulkersNeeded` estimate, capped at 54); the bot re-plans from reachability anyway and names apply in order.
- **Guild shared-stock tables were removed by PR #89's unified-shop direction**; availability is visible per item (loose + boxed) instead.
- **Quota override checkbox removed**: `main`'s order path has no quota wiring (that was PR #84, unmerged); showing it would be a no-op control.
- **Bot settings POST requires the `settings` permission and bot-declared caps**; the UI surfaces server errors instead of pre-validating every capability.
