# W-063: anvil-märkt lager-UUID, hasUUID och säker återregistrering

Ett WeSort-UUID är en **aktiv lageridentitet** för en fysisk shulkerbox,
inte en oföränderlig identitet som Minecraft ger föremålet. Identiteten
syns som boxens anvil-namn `WeSort-<canonical UUID v4>` (43 tecken),
exempelvis `WeSort-550e8400-e29b-41d4-a716-446655440000`. Utgående
orderboxar lämnar UUID-registret och får ordernamn via W-064; en återlämnad
box inventeras och får **nytt** UUID innan den åter blir tillgängligt lager.

## Identitetslagret

- **`ShulkerUuid`** (`common/.../data`) är en ren, strikt parser/validator:
  `parse` kräver exakt `WeSort-` + canonical UUID v4 (liten bokstav, rätt
  version- och variantnibble, ingen trimning/whitespace). `customName`
  plockar det serverobserverade `minecraft:custom_name` ur den typade
  NBT-komponent-patch som agenten redan skickar; endast ett rent, ostylat
  textnamn räknas. En syntaktiskt giltig UUID lovar inte att den är unik,
  registrerad eller säker.
- **`StockLedger`** får tabellerna `box_uuids` (aktivt) och
  `box_uuid_history` (append-only) i samma SQLite-transaktion som den
  fysiska observationen. Vid varje färsk observation härleds UUID från
  boxarnas rot-slots och stäms av mot registret: ny UUID registreras
  idempotent med guild/server, source, path, itemfingerprint, revision och
  tid; samma UUID på annan plats eller återanvänd historisk UUID klassas
  `DUPLICATE` och spärras från allokering tills fysisk reconciliation.
  Två identiska namnlösa boxar fortsätter vara positions-/fingerprint-
  identifierade och får aldrig automatiskt samma UUID.
- **Livscykel** exponeras per box som `identityStatus`:
  `REGISTERED | UNREGISTERED | INVALID | DUPLICATE | UNKNOWN`, med
  `hasUUID` och `boxUUID` härledda från faktiskt serverobserverat namn.
  `deregisterBoxUuid` flyttar en aktiv UUID till historik vid utgående
  order/omdöpning; historiska UUID återanvänds aldrig.

## API och sekretess

- Owner `GET /api/v1/stock/source` bär `boxIdentities` per rot-shulker.
- Owner `GET /api/v1/stock/boxes` returnerar aktivt register och historik.
  Båda är owner-only; boxidentitet röjer
  inte stashpositioner utöver det som redan är synligt för owner (ingen
  streamer-maskning — läget är borttaget).
- `return-intake` med `markUuid=true` (W-042) fortsätter vara en avsikt
  (`UUID_REQUESTED`); det faktiska anvil-namnbytet sker via det
  Manager-auktoriserade box-jobbet nedan.

## Manager-auktoriserat REGISTER_BOX / RETURN_INTAKE-jobb

Owner `POST /api/v1/boxes/jobs` startar ett jobb för en verifierad,
oskyddad, ej redan UUID-märkt rot-shulker (`server`, `stash`, `source`,
`path`, `kind`). Manager mintar en ny UUID v4 och lagrar jobbet som
`PLANNED` (högst ett öppet box-jobb per server). `GET /api/v1/boxes/jobs`
listar. Agentens signerade `POST /api/v1/boxes/agent` claimar (`claim`) och
avancerar med `renamed` (→`VERIFYING`), `complete` (→`COMPLETED` först när
en färsk observation återregistrerat UUID på exakt source/path) eller
`uncertain` (→`NEEDS_RECONCILIATION`). Anvil-namnbytet är en verifierad
fysisk operation som måste passera W-061:s fail-closed
`WorldInteractionGuard` och återanvänder `StashSorter.Job.Name`
(`OrderRunner.Rename`). Omstart eller utgången lease fencar ett pågående
jobb till `NEEDS_RECONCILIATION`; det tilldelas aldrig om automatiskt.

## Testbevis

- `ShulkerUuidTest`: round-trip, längd/prefix/case, icke-v4/icke-canonical,
  extrahering ur typad NBT-patch, samt att stilade/främmande namn inte
  räknas som UUID.
- `BoxUuidRegistryTest`: registrering vid observation, `identityStatus`,
  dubblett på annan plats (registreras aldrig om), avregistrering till
  historik, återanvändning spärras, och `INVALID`/`UNKNOWN` för främmande
  respektive saknade namn.
- `BoxJobsTest`: jobb mintar UUID v4, claim/renamed/complete-livscykel,
  completion kräver färsk serverobserverad omdöpning, och omstart fencar
  pågående jobb.
- Manager- och common-byggena samt hela managersviten är gröna.

## Gränser och återstår

Identitetslagret, det Manager-auktoriserade box-jobbet och den verifierade
export-strip-motsvarigheten på Manager-sidan är klara. **Avgränsning: den
agent-sidiga utförandekedjan (claim → hämta box → fysisk anvil-omdöpning/
namn-strip → återinventera → `renamed`/`complete`) ligger uttryckligen
utanför detta scope och implementeras inte här** — Manager-sidan verifierar
enbart mot färska serverobservationer (registrering kräver att det utfärdade
UUID:t återobserveras på exakt source/path; export-strip kräver en färsk
observation som visar att namnet är borta) och skriver register, identitets-
ankare, historik och audit i en transaktion. Kvarstår: själva agent-
utförandet som en verifierad fysisk operation under W-061:s guard, webb-UI
för att starta/pausa/avstämma registrering, samt verklig 26.3-E2E med 1–5
agenter.
`PICKUP_READY` är en verifierad staging-status, aldrig kundens slutliga
`DELIVERED`.
