# W-029: paketspårning och custody mellan warehouse, courier och mottagare

Varje fysiskt paket har spårbar custody från packning via pickup till
mottagare, även när samma HYBRID-agent gör allt. `packageId` är Managers
unika, persistena identitet — aldrig ett påhittat Minecraft-UUID och aldrig
det synliga `WeSort (X/Y) - text`-namnet, så två paket med samma namn eller
ordningsnummer i olika order sammanblandas aldrig.

## Manager-sidan (denna commit)

- **`PackageCustody`** (Manager) håller ett append-only-tillstånd per paket:
  `STAGED → PICKUP_VERIFIED → IN_TRANSIT → DEPOSIT_VERIFIED` eller
  `PLAYER_HANDOFF_PENDING`, samt `NEEDS_RECONCILIATION`. Varje övergång
  loggar agent, lokalisering (server/dimension/stash) och kvittens.
- **Ömsesidig uteslutning:** den agent som plockar upp ett paket behåller
  custody genom transporten; en annan agent kan inte claim:a eller avancera
  paketet utan uttrycklig reconciliation. Övergångar validerar tillstånds-
  grafen, så ett paket kan aldrig vara `STAGED` och `IN_TRANSIT` samtidigt
  eller bokföras på två platser.
- **`CustodyRoutes`**: Owner `GET /api/v1/custody` (listar per order) och
  signerad agent `POST /api/v1/custody/agent` (`stage` eller ett
  tillståndsnamn). Reboot/replay ändrar inte custody felaktigt.

## Testbevis

- `PackageCustodyTest`: idempotent stage, full custody-kedja, andra agenten
  kan inte claim:a, ogiltiga övergångar nekas, reconciliation alltid möjlig,
  och custody överlever omstart utan att byte av agent blir möjlig.
- Manager- och common-byggena samt hela managersviten är gröna.

## Gränser och återstår

Återstår: koppling av custody-övergångarna till verifierade fysiska
kvittenser (W-027/W-028) och till W-063:s retur/intake-flöde (nytt lager-UUID
vid återlämning utan att återskapa gammalt), webb-/DOM-vy samt verklig
26.3-E2E med 1–5 agenter och felinjektion (duplicerade etiketter, fel box,
partiella `1/4`–`3/4`, replay, två agenter som vill ha custody).
