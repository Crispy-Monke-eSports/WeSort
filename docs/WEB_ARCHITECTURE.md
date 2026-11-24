# Web architecture: Java backend + React + MUI operations dashboard

Single-container deployment. One Java 25 runtime process (`wesort-manager.jar`)
serves the API and the compiled React single-page app from its `/web` classpath
folder. No Node.js or Bun process is required at runtime.

## Backend (authoritative)

`stash-manager-server/` (Java 25, Gradle, SQLite) keeps the Manager HTTP server,
authentication/sessions, permissions, Minecraft bot integrations, inventory
indexing, orders and reservations. Business rules — order validation,
availability, reservations, delivery stages — stay in Java; the frontend only
previews them.

Ported from PR #89 (without merging it):

- `IndexStore.orderCountsIncludingStale` — last-verified counts (FRESH,
  STALE_AGE, STALE_STARTUP). Explicitly invalidated (STALE), withdrawn,
  missing, unreachable, protected and shared sources stay excluded.
- `OrderStore` offline queue — placing an order first tries verified stock,
  then retries once against last-verified counts when the shortage is a real
  per-line shortage (400/409 with a line index). Claim still requires fresh
  observations, so the bot re-verifies before touching anything.
  `OrderStore.listJson` reports the same availability the submit check uses.
- `PhysicalReservations.allocate(..., allowStale)` /
  `available(..., allowStale)` — queued orders may reserve last-verified slots;
  `matches` still requires fresh candidates.
- Tests: `OfflineOrderQueueTest`, the `StockAvailabilityTest` expiry case now
  queues-then-claims, and `StaticRoutesTest` (SPA routes + index.html on the classpath).

## Frontend (`web/`)

React 19 + TypeScript (strict) + Vite + Material UI v9 (Material Design 2 theme,
dark default + full light variant, restrained 4dp corners, WeSort green accent).
TanStack Router (code-based routes: `/overview`, `/inventory`, `/orders`,
`/bots`, `/operations`, `/settings`, `/login`; legacy `/shop` → `/inventory`,
`/activity` → `/operations` redirects), TanStack Query (server state, live
SSE invalidations, logout clears the cache), TanStack Form + Zod (checkout details),
Zustand (cart draft per user + checkout groups with zod-validated rehydration),
Vitest unit tests, Playwright E2E, live screenshot evidence
(`scripts/screenshots-live.py` against a real Manager with real 26.3 icons).

MUI is the only visual component library (Emotion runtime styles; no Tailwind,
no Radix/shadcn). The CSP stays strict: `Http.securityHeaders` plus a
per-request style nonce — `StaticRoutes` injects it into `index.html`
(`__WESORT_STYLE_NONCE__` → `<meta name="emotion-nonce">`) and the React root
creates the Emotion cache with that nonce, so `style-src 'self'` still holds.

Inventory (guild stock + ordering): parallel per-stash index/order queries merged
client-side; filter panel + mobile drawer; grid + paginated table views with real
26.3 item icons; loose/whole-box/mixed breakdown with honest states (orderable,
empty, stale/offline, unverified — unknown reservation state is never presented
as orderable or empty); guild-wide cross-stash cart split (loose first, then
whole single-content boxes; mixed boxes never auto-packed whole); item dialog
with per-stash availability; cart draft drawer; checkout dialog with MUI Stepper
(draft → details → review → confirmation) and a per-group state machine
(`not_submitted/submitting/confirmed/uncertain/rejected`) that persists the
exact request body + `Idempotency-Key` per group (reused only on exact match,
survives reload). Order submit sends `Idempotency-Key` as an HTTP header (never
in the JSON body). Orders live on Orders (all stashes merged with partial-failure
errors, search + status filter, pagination, details dialog with timeline,
cancel/force-cancel/retry/clear); Bots shows presence/activity/settings;
Operations shows the global view plus owner queues (physical/jobs/handoffs/
custody/deposits/box jobs/audit); Settings covers server, stash definitions
(create/edit/delete, revision conflicts, deliveryDimension), users (roles,
scopes, disable, reset, logout, delete), guild, security, connection and
the active named agent key. Feature status per screen is tracked in `docs/FEATURE_PARITY.md`.

API client (`src/api/client.ts`): same-origin cookies, JSON,
`X-Stash-Manager: 1` on browser writes, 401 → login redirect (preserving the
target), 403 surfaced (incl. must-change-password). Exact TypeScript models +
zod parsing of Java's real responses (`user.perms` is an object of booleans
plus stash/bot scopes; login sends `username`, not `name`; icons at
`/icon/<namespace>/<path>.png`). No backend credentials in the frontend; no
business logic moved out of Java.

## Static serving

`StaticRoutes` is the last GET route: `/` and `index.html` serve the SPA,
`/overview /inventory /orders /bots /operations /settings /login` (plus legacy
`/shop /activity`) fall back to `index.html` (direct navigation/refresh/deep
links), `/assets/*` serves hashed Vite files immutable, unknown `/api/*`
answers JSON 404, missing assets 404, traversal refused, `index.html` is never
cached immutably. `index.html` responses carry the per-request CSP style nonce
(see above). Compatible with the CSP in `Http` (`script-src 'self'` — the
bundle has no inline code).

## Local development

Prerequisites: Java 25, Bun 1+, Node 22 (Playwright).

```bash
./gradlew --no-daemon -p stash-manager-server build   # backend + tests
```
