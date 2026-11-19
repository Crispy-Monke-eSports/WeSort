# Web architecture: Java backend + React/TypeScript storefront

Single-container deployment. One Java 21 runtime process (`wesort-manager.jar`)
serves the API and the compiled React single-page app from its `/web` classpath
folder. No Node.js production server exists.

## Backend (authoritative)

`stash-manager-server/` (Java 21, Gradle, SQLite) keeps the Manager HTTP server,
authentication/sessions, permissions, Minecraft bot communication, inventory
indexing, orders and reservations. Business rules — order validation,
availability, reservations, quota checks, delivery stages — stay in Java; the
frontend only previews them.

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

React 19 + TypeScript (strict) + Vite, TanStack Router (code-based routes:
`/shop /orders /bots /activity /settings /login`), TanStack Query (server
state, 3s visible / 15s hidden order polling), TanStack Virtual (item grid),
Zustand (cart persisted per user+stash, UI prefs), Tailwind CSS +
shadcn-style `components/ui` (Button/Input/Select/Dialog/Badge/Card/Tooltip),
forms with controlled inputs validated by Zod-shaped helpers, Vitest unit
tests, Playwright E2E (`web/e2e`).

Unified Shop (PR #89 behavior, React implementation): central search, item
grid aggregating loose + boxed counts, tooltips with the boxed breakdown,
buy dialog / Shift-click quick-add / double-click, cart with automatic shulker
splitting preview (`lib/shop.ts`: whole filled boxes as-is plus loose rest,
never over-delivers, 54-shulker cap), cart → delivery → review → place
wizard (shulker names, purpose, recipient, comment, audited quota override),
order history with queued/offline states, responsive + mobile nav, Classic
dirt theme with `dirt.svg` fallback, streamer mode, Minecraft disclaimer.

API client (`src/api/client.ts`): same-origin cookies, JSON, `X-Stash-Manager:
1` on browser writes, idempotency keys for order submission, 401 → login
redirect, network-error handling. No backend credentials in the frontend; no
business logic moved out of Java.

## Static serving

`StaticRoutes` is the last GET route: `/` and `/index.html` serve the SPA,
`/shop /orders /bots /activity /settings /login` fall back to `index.html`
(direct navigation/refresh), `/assets/*` serves hashed Vite files immutable,
unknown `/api/*` answers JSON 404, missing assets 404, traversal refused,
`index.html` is never cached immutably. Compatible with the CSP in `Http`
(`script-src 'self'` — the bundle has no inline code).

## Local development

Prerequisites: Java 21, Bun 1+.

```bash
./gradlew --no-daemon -p stash-manager-server build   # backend + tests
cd web && bun install --frozen-lockfile
bun run dev        # Vite HMR on :5173, proxies /api /dirt.png /icons* to :8765
```

Run the Manager separately (`serve`) on port 8765; the proxy preserves Host/
Origin behavior. Production: `docker build .` (frontend stage with Bun, Java
stage with Gradle, JRE runtime; single container on 8765).

## Testing

```bash
cd web && bun run lint && bun run test && bun run build   # tsc, vitest, bundle
./gradlew --no-daemon -p stash-manager-server build      # 336 Java tests
npm test --prefix web-tests                              # SPA static regression
```

Playwright: `cd web && bunx playwright test` (needs browsers + dev server).
Docker smoke: `python3 scripts/docker-smoke.py --image ... --browser`.
