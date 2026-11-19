# W-064: användarvalda order-shulkernamn med automatiskt WeSort (X/Y)-prefix

Användaren väljer textdelen av namnet på beställda shulkerboxar i
Manager-webben. Manager skapar alltid prefixet `WeSort (X/Y) - ` där `X`
är det 1-baserade boxnumret och `Y` det låsta, auktoritativa paketantalet.
Nuvarande gräns är 54 boxar per order och höjs inte.

## Kontrakt (denna commit)

- **`PackageLabel`** (`common/.../data`) är den rena, versionerade policyn:
  `name(x, y, text)` bygger `WeSort (X/Y) - <text>`; `text()` trimmar,
  defaultar tomt till `Beställning`, och nekar över 33 UTF-16-kodenheter
  (anvilens 50 minus längsta prefix `WeSort (54/54) - ` = 17), `§`,
  kontrolltecken, bidi/osynliga formattecken och trasiga surrogat.
  `normalize()` tar `{"text":"..."}` (gemensamt) eller
  `{"texts":[...]}` (per box, max 54) och `generate()` producerar alla
  fullständiga namn för ett fryst `packageTotal`.
- **`OrderStore`** lagrar `packageLabel` och det frysta `packageTotal`
  separat från de äldre fria `names.all|each`. `normalizePackageLabel`
  validerar server-side; `Order.generatedNames()` genererar namnen. Idempotens-
  fingeravtrycket omfattar `packageLabel`, så samma förfrågan ger samma
  order/etikett och API-replay kan inte kringgå prefix eller maxgräns.
  Äldre order med `names` förblir läsbara oförändrade och får inga påhittade
  nummer (`packageTotal=0` → inga genererade namn).
- **Route:** `POST /api/v1/orders` tar emot valfri `packageLabel`; klienten
  skickar bara suffixet och kan aldrig ersätta prefixet eller manipulera
  numreringen.

## Testbevis

- `PackageLabelTest`: exakt prefix/nummer (1/4 … 54/54), nummer utanför
  intervall, 33/34-kodenheter, `§`/kontroll/bidi/surrogat, trim/default,
  `normalize` för gemensam och per-box-text samt `generate` med fryst total
  och defaultutfyllnad.
- `OrderNamesTest.packageLabelIsNormalisedStoredAndGeneratesNames`:
  normalisering, lagring, `packageTotal=1` för 16 diamanter, genererat namn
  och överlevnad över omstart.
- Manager- och common-byggena samt hela managersviten är gröna.

## Gränser och återstår

Återstår: webbens orderfält ("Text på shulkerboxarna") med prefix-
förhandsvisning och 33-tecken-räknare, `OrderRunner`/`Order.Names` som
tillämpar de genererade namnen vid packning, anvilnamnbyte som krav före
`PICKUP_READY` (i samspel med W-063:s `deregisterBoxUuid`), visning av
genererade namn/fryst `Y`/per-box `X` i orderlistan, samt verklig 26.3-E2E.
En orderbox har efter verifierad omdöpning inget aktivt lager-UUID
(`hasUUID=false`); dess identitet är `packageId` + `orderId` + nummer +
manifest/proveniens.
