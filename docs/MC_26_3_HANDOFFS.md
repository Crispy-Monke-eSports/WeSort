# W-039: spelarleverans vid registrerad mötesplats med mottagarkvittens

En guildmedlem får sin order levererad vid en verifierad mötesplats.
Kvittens och sannolikt fysiskt övertagande får inte förväxlas: ett drop
utan verifierad pickup är aldrig DELIVERED.

## Manager-sidan (denna commit)

- **`Handoff`** (common): tillståndsmaskin `SCHEDULED → AWAITING_RECIPIENT
  → DELIVERED` (endast med explicit kvittens i `confirmedBy`), `NO_SHOW`,
  `NEEDS_RECONCILIATION` och `RETURNED`. `advance(DELIVERED, …)` kräver
  en icke-tom kvittensaktör; ett drop kan aldrig nå DELIVERED.
- **`Handoffs`** (Manager): `schedule`/`arrive`/`confirm` (idempotent
  replay)/`noShow`/`dropUnverified`/`resolve`/`retry`/`returnOrder`.
  Endast den registrerade agenten kan avancera handoffen (andra agenten
  kan inte ta över cargo), och no-show lämnar lagret spärrat tills explicit
  `RETURNED` — ingen falsk återföring. Append-only events och persistens.
- **`HandoffRoutes`**: Owner `GET/POST /api/v1/handoffs` (schedule/
  confirm/resolve) och signerad agent `POST /api/v1/handoffs/agent`
  (arrive/no-show/drop).

## Testbevis

- `HandoffTest` (common): DELIVERED kräver kvittens, drop→NEEDS_RECONCILIATION
  (aldrig DELIVERED), no-show spårbar och endast explicit RETURNED,
  resolve DELIVERED/RETURNED, ogiltiga övergångar, validering, roundtrip.
- `HandoffsTest` (Manager): confirm är enda väg till DELIVERED + idempotent
  replay, andra agenten nekas, no-show → RETURNED, persistens.
- Manager-, common- och klientkompilering samt hela managersviten gröna.

## Gränser och återstår

Återstår: agentens fysiska möte (W-035/W-036), maxavståndsvalidering och
fel-mottagare/död/disconnect-hantering, webb-bekräftelse begränsad till
orderägare/admin, samt verklig 26.3-E2E (no-show, fel identitet, drop utan
pickup-verifiering, death, stuck).
