# WeSort operations dashboard (web)

React 19 + TypeScript (strict) + Vite + Material UI v9 (Material Design 2 theme,
dark default + full light variant). MUI is the only visual component library
(Emotion runtime styles); no Tailwind, no Radix/shadcn, no separate CSS bundle.
TanStack Router (code-based routes: `/overview`, `/inventory`, `/orders`,
`/bots`, `/operations`, `/settings`, `/login`), TanStack Query (server state),
TanStack Form + Zod (checkout details), Zustand (per-user cart draft +
persisted per-group checkout state machine), Vitest unit tests, Playwright E2E.

Java Manager stays authoritative for stock, reservations, naming and security;
the UI previews its data and never invents business rules. CSP stays strict
(`style-src 'self'`): the Manager injects a per-request Emotion nonce into
`index.html` (`__WESORT_STYLE_NONCE__` → `<meta name="emotion-nonce">`).

## Develop

```bash
bun install --frozen-lockfile
bun run dev      # HMR on :5173; /api proxied to 127.0.0.1:8765
```

Start the Java Manager separately on 8765. Production bundle is built into
`../stash-manager-server/src/main/resources/web` (gitignored hashed assets):

```bash
bun run lint && bun run test && bun run build
```

E2E: `bunx playwright test` (needs `playwright install` browsers + `bun run dev`).
Live visual evidence against a real Manager with real 26.3 icons:
`python3 scripts/screenshots-live.py` (repo root; needs `WESORT_EULA=true`) —
a verification gate, not best-effort capture (page/API/capture failures are red).

## Status model (tested, not just documented)

- Implemented + unit/e2e-tested: inventory grid (full-catalog paging)/table,
  checkout state machine with exact retry, guild-wide split, single-content
  whole boxes, reservation-safe counts, per-stash 54-box pre-check,
  error-aware verification (stale ≠ verified ≠ 0), orders with retry/cancel/
  clear behind the tested status mapping, bots/activity/settings, operations
  queues + order audit with same-incident folding, W-064 per-stash box labels,
  scope-safe user admin (null-vs-[]), MD2 dialogs everywhere (no
  window.confirm/prompt), MD2 theme (see `docs/MD2_COMPLIANCE.md`) with
  dark+light toggle.
- Implemented, CI-verified (Docker/MC E2E): everything the mandatory jobs cover.
- Explicitly remaining: W-063 stock-UUID lifecycle + return intake; Manager-side
  module-allowlist audit surface; per-bot jobs view; audit-log viewer UI.
  These are tracked as NOT READY in the PR report, not silently claimed.
