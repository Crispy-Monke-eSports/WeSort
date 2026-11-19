# Web architecture: Java backend + React/TypeScript storefront

Single-container deployment. One Java 21 runtime process (`wesort-manager.jar`)
serves the API and the compiled React single-page app from its `/web` classpath
folder. No Node.js or Bun process is required at runtime.

## Backend (authoritative)

`stash-manager-server/` (Java 21, Gradle, SQLite) keeps the Manager HTTP server,
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
- `DirtRoutes` (`GET /dirt.png`) + `VanillaTexture` — the Classic theme's dirt
  background is read from the checksum-verified official client (`eula=true`);
  a 404 falls back to the bundled `dirt.svg`. Never downloads, validates the
  PNG magic, refuses symlinks.
- Tests: `OfflineOrderQueueTest`, `VanillaTextureTest`, the
  `StockAvailabilityTest` expiry case now queues-then-claims, and
  `StaticRoutesTest` (SPA routes + index.html on the classpath).

PR #84 was NOT merged. No quota/delivery-stage dependency was needed: the
offline queue was re-based onto `main`'s `OrderStore` (no quota parameters),
keeping `main`'s order semantics untouched.

## Frontend (`web/`)

React 19 + TypeScript (strict) + Vite. TanStack Router (code-based routes with
validated search params: `/shop?server=&stash=&q=&sort=&cat=&avail=`,
`/orders?server=&stash=&q=`, `/bots`, `/activity`, `/settings`, `/login`),
TanStack Query (server state, 3s visible / 15s hidden order polling, logout
clears the cache), TanStack Virtual (row-virtualized product grid with a
correct total scroll model), TanStack Form + Zod (checkout delivery step),
Zustand (cart keyed per user+stash with zod-validated rehydration; UI prefs),
Tailwind CSS + real shadcn/ui (Radix-based Button/Card/Input/Select/Badge/
Dialog/Sheet/DropdownMenu/Tooltip/Tabs/Separator/Skeleton/Label), Vitest unit
tests, Playwright E2E, deterministic screenshot script (`web/screenshots/`).

Shop (PR #89 behavior, modern storefront implementation, always across ALL
visible stashes — no stash picker): parallel per-stash index/order queries
merged client-side; left filter rail (stash status list, creative-tab
categories from index metadata, availability, sorting, per-stash rescan),
center virtualized catalog with storefront product cards (artwork, name,
aggregated loose+boxed counts, stock state, quick-add), sticky right cart
(lines tagged per stash with steppers, real-quantity totals, packing estimate,
over-availability warnings), mobile bottom cart bar + sheet drawer. Checkout
groups lines per stash and places one order per stash (one idempotency key
each, reused on safe retry): cart → delivery (purpose/recipient/comment/names,
Zod-validated) → review → place, with step guards. Order submit sends
`Idempotency-Key` as an HTTP header (never in the JSON body) and reuses the
key when retrying an uncertain submission. Order history lives on Orders
(all stashes merged, filters, details dialog, cancel/force-cancel/retry/clear
per stash, audit for approvers); the Shop shows a recent-orders summary.

Bots (list/detail/activity/settings via `GET /api/v1/bots*`), Activity
(per-bot feed), Settings (guild, quotas usage, password, admin unlock, users,
link addon) reuse the same design system. Feature status per screen is tracked
in `docs/FEATURE_PARITY.md`.

API client (`src/api/client.ts`): same-origin cookies, JSON,
`X-Stash-Manager: 1` on browser writes, 401 → login redirect (preserving the
target), 403 surfaced (incl. must-change-password). Exact TypeScript models +
zod parsing of Java's real responses (`user.perms` is an object of booleans
plus stash/bot scopes; login sends `username`, not `name`; icons at
`/icon/<namespace>/<path>.png`). No backend credentials in the frontend; no
business logic moved out of Java.

Modern Dark is the default theme (neutral dark surfaces, green accent);
Classic (verified dirt + tighter radius) is opt-in and theme-aware. Both share
the same components. Theme + streamer mode persist; streamer mode masks names.

## Static serving

`StaticRoutes` is the last GET route: `/` and `index.html` serve the SPA,
`/shop /orders /bots /activity /settings /login` fall back to `index.html`
(direct navigation/refresh/deep links), `/assets/*` serves hashed Vite files
immutable, unknown `/api/*` answers JSON 404, missing assets 404, traversal
refused, `index.html` is never cached immutably. Compatible with the CSP in
`Http` (`script-src 'self'` — the bundle has no inline code).

## Local development

Prerequisites: Java 21, Bun 1+, Node 22 (Playwright).

```bash
./gradlew --no-daemon -p stash-manager-server build   # backend + tests
cd web && bun install --frozen-lockfile
bun run dev        # HMR on :5173, proxies /api /dirt.png /icons* to :8765
```

Run the Manager separately (`serve`) on port 8765; the proxy preserves Host/
Origin behavior. Production: `docker build .` (frontend stage with Bun, Java
stage with Gradle, JRE runtime; single container on 8765).

## Testing

```bash
cd web && bun run lint && bun run test && bun run build   # tsc, vitest (31), bundle
./gradlew --no-daemon -p stash-manager-server build      # Java tests (336+)
npm test --prefix web-tests                              # SPA static regression
python3 scripts/web-java-integration.py                  # 10 checks vs real Manager
cd web && npx playwright test                            # E2E vs dev server (needs browsers)
node web/screenshots/capture.mjs [base] [outDir]         # visual evidence
python3 scripts/docker-smoke.py --image ... --browser    # full deployment smoke
```

Visual evidence lives in `docs/screenshots/` (deterministic fixture data,
never production data).
