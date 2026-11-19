# W-059: frivilliga uttagsgränser per medlem från samma guildlager

Alla stashes tillhör en guild med gemensamt saldo, men en administratör kan
**valfritt** aktivera uttagsgränser per medlem, roll, item/kit och tidsperiod.
Avstängd kvotpolicy innebär ingen artificiell individuell begränsning;
individuell audit och rollfördelning gäller alltid. Kvoter skapar aldrig
privata inventarier eller itemkonton — de begränsar bara hur mycket av
guildens gemensamma lager en medlem får ta ut.

## Manager-sidan (denna commit)

- **`QuotaRule`** (`common/.../data`) är en regel `{subject, key, max,
  windowSeconds}`. Subject är ett medlem-id, en roll eller `*` (alla); key är
  en linjereferens (`item:<key>`, `shulker:<key>` eller `*`). Precedens:
  medlemsspecifik > roll > `*`.
- **`Quotas`** (Manager) håller policy (`enabled`, `revision`, `rules`) och
  användning (`consumed`/`reserved` per medlem/key i fasta tidsfönster).
  `checkAndReserve` kontrollerar och reserverar idempotent per order-id;
  `release` (cancel/deny) och `consume` (verifierad leverans) påverkar kvoten
  exakt en gång, även över omstart. `reservedQuota`/`consumedQuota` är skilda
  från fysisk inventoryreservation.
- **`QuotaRoutes`**: Owner `GET/POST /api/v1/quotas` (policy + revision) och
  medlem `GET /api/v1/quotas/me` (egen förbrukning). Owner-updatering kräver
  `expectedRevision` (409 vid stale).

## Testbevis

- `QuotaRuleTest`: precedens (medlem > roll > `*`), key-täckning, validering.
- `QuotasTest`: avstängd = ingen gräns, aktiverad blockerar parallellt
  överdrag, release/consume idempotent (exakt en gång), persistens över
  omstart, policyrevisionskonflikt.
- Manager- och common-byggena samt hela managersviten är gröna.

## Gränser och återstår

Återstår: koppling av kvotkontrollen till orderläggning (SELF → faktisk
mottagare, GUILD → beställande medlem), release vid cancel/deny och consume
vid verifierad leverans i orderlivscykeln (W-026/W-058), räkning av kits/fulla
shulkers och deras ingående föremål utan dubbelräkning (W-024/W-040), loggad
motiverad admin-override samt webb-/DOM-adminvy.
