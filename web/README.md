# WeSort web storefront

React 19 + TypeScript (strict) + Vite. TanStack Router / Query / Virtual /
Form (+ Zod), Zustand, Tailwind CSS, shadcn-style `src/components/ui`.

## Develop

```bash
bun install --frozen-lockfile
bun run dev      # HMR on :5173; /api, /dirt.png, /icons* proxied to 127.0.0.1:8765
```

Start the Java Manager separately on 8765. Production bundle is built into
`../stash-manager-server/src/main/resources/web` (gitignored hashed assets):

```bash
bun run lint && bun run test && bun run build
```

E2E: `bunx playwright test` (needs `playwright install` browsers + `bun run dev`).
