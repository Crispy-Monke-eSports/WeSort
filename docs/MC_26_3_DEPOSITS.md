# W-038: fysisk kistleverans med verifierad deposition

En WeSort-agent levererar från guildens gemensamma lager till en
registrerad kista och verifierar faktisk deposition. En guildintern flytt
bokförs som omlokalisering utan att hela guildens totalsaldo minskar; en
leverans till mottagare utanför lagret är ett uttag. Skillnaden är explicit
och aldrig härledd.

## Manager-sidan (denna commit)

- **`Deposit`** (common): tillståndsmaskin `DEPOSITING → DELIVERED` eller
  `NEEDS_RECONCILIATION`, explicit `kind` (`DELIVERY`/`TRANSFER`),
  `netEffect()` (`withdrawal`/`relocation`) och `reducesStock()` (endast
  `DELIVERY`). `advance()` upprätthåller grafen och ömsesidig custody: en
  andra agent kan inte slutföra en annans deposition utan reconciliation.
- **`Deposits`** (Manager): JSON-lager med idempotent `begin` per paket +
  order + kind + destination; `confirm` kräver en serverbekräftad, icke-tom
  kistinnehålls-snapshot innan `DELIVERED`, och replay av en bekräftelse
  returnerar samma post (ingen dubbel leverans). `reconcile` öppnar
  `NEEDS_RECONCILIATION`. Append-only events och persistens över omstart.
- **`DepositRoutes`**: Owner `GET /api/v1/deposits` och signerad agent
  `POST /api/v1/deposits/agent` (`begin`/`confirm`/`reconcile`).

## Testbevis

- `DepositTest` (common): state graph, custody (andra agenten nekas),
  DELIVERY=withdrawal vs TRANSFER=relocation, validering och json-roundtrip.
- `DepositsTest` (Manager): idempotent begin, att confirm kräver snapshot
  och att replay inte dubbellevererar, andra agenten nekas, reconciliation
  är idempotent, båda kinderna särskiljs i ledger, samt persistens.
- Manager-, common- och klientkompilering samt hela managersviten gröna.

## Gränser och återstår

Återstår: agentens pack/res/leverans (W-035) och långdistansnavigation
(W-036), att knyta `Deposits.confirm` till en faktisk slot-snapshot och till
`StockLedger` (DELIVERY minskar saldot, TRANSFER omlokaliserar utan netto-
minus), kvittens-/avvikelsevy i webben, samt verklig 26.3-E2E med 1–5
agenter och felinjektion (dubbel rapport, reconnect, fulla slots, partiell
leverans, lagg, samtidiga jobb).
