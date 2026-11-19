# W-037: registrera leveranspunkter och styr åtkomst via Manager-webben

Guildens behöriga administratörer registrerar leveranskistor och
spelar-/projektmötesplatser för intern distribution eller medlemsuttag ur
samma lagernätverk. En leveranspunkt är aldrig ett privat stash och att
välja en ändrar inte vem som äger övrigt lager.

## Manager-sidan (denna commit)

- **`DeliveryPoint`** (common): server + dimension + x/y/z + `type`
  (`CHEST`/`PLAYER_MEETING`) + `access` (`MEMBER`/`PROJECT`/`INTERNAL`)
  + `status` (`PENDING`/`VERIFIED`/`PAUSED`) + `revision`. Validerar
  dimension, koordinatgränser och att en `MEMBER`-punkt har en medlem
  (och att ingen annan punkt har en). `jsonRedacted()` tar bort koordinater
  och dimension.
- **`DeliveryPoints`** (Manager): JSON-lager med create/update/pause,
  optimistisk revision (409 vid stale), append-only ändringshistorik och
  persistens över omstart. `selectable(principal)` ger ägaren alla
  verifierade punkter med koordinater; övriga får bara verifierade `MEMBER`
  (egna) och `PROJECT`, redacted, och aldrig en hemlig `INTERNAL`-stash.
- **`DeliveryPointRoutes`**: Owner `GET/POST /api/v1/delivery-points` och
  medlemslista `GET /api/v1/delivery-points/selectable` (VIEW). Forced
  streamer: `server` maskas automatiskt av `Http.json`.

## Testbevis

- `DeliveryPointTest` (common): create, validering av malformed input,
  optimistisk merge + revisionsökning, redaction och read-roundtrip.
- `DeliveryPointsTest` (Manager): create + historik, optimistisk update och
  att pause blockerar val (409 vid stale revision), att `selectable` döljer
  `INTERNAL`/andras `MEMBER`/`PENDING` och redactar koordinater, samt
  persistens över omstart.
- Manager-, common- och klientkompilering samt hela managersviten är gröna
  (inkl. `StreamerTest.everyLoggedInRouteIsClassified`).

## Gränser och återstår

Återstår: agentverifiering av kista (nåbarhet, blocktyp, slots, double
chest, chunkstatus) som sätter `VERIFIED`, och att en ändrad/trasig punkt
pausar ett aktivt uppdrag innan dumpning (W-030/W-033/W-042), webb-/DOM-vy
för formulär och historik, samt verklig 26.3-E2E med 1–5 agenter.
