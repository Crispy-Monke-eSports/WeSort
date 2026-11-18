# W-063: anvil-märkt lager-UUID, hasUUID och säker återregistrering

Ett WeSort-UUID är en **aktiv lageridentitet** för en fysisk shulkerbox,
inte en oföränderlig identitet som Minecraft ger föremålet. Identiteten
syns som boxens anvil-namn `WeSort-<canonical UUID v4>` (43 tecken),
exempelvis `WeSort-550e8400-e29b-41d4-a716-446655440000`. Utgående
orderboxar lämnar UUID-registret och får ordernamn via W-064; en återlämnad
box inventeras och får **nytt** UUID innan den åter blir tillgängligt lager.

## Identitetslagret (denna commit)

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
  Båda är owner-only och maskeras för forced streamer; boxidentitet röjer
  inte stashpositioner utöver det som redan är synligt för owner.
- `return-intake` med `markUuid=true` (W-042) fortsätter vara en avsikt
  (`UUID_REQUESTED`); det faktiska anvil-namnbytet och registerjobbet
  (`REGISTER_BOX`/`RETURN_INTAKE`) är nästa steg och ska passera samma
  fail-closed `WorldInteractionGuard` (W-061).

## Testbevis

- `ShulkerUuidTest`: round-trip, längd/prefix/case, icke-v4/icke-canonical,
  extrahering ur typad NBT-patch, samt att stilade/främmande namn inte
  räknas som UUID.
- `BoxUuidRegistryTest`: registrering vid observation, `identityStatus`,
  dubblett på annan plats (registreras aldrig om), avregistrering till
  historik, återanvändning spärras, och `INVALID`/`UNKNOWN` för främmande
  respektive saknade namn.
- Manager- och common-byggena samt hela managersviten (242 prov) är gröna.

## Gränser och återstår

Detta är identitetslagret ovanpå W-020/W-023/W-024, inte det fysiska
anvil-utförandet. Återstår: Manager-auktoriserat `REGISTER_BOX`-jobb som
genererar UUID v4, utför verkligt anvil-namnbyte och kvitterar nytt
serverfingerprint, det skyddade `RETURN_INTAKE`-jobbet, samordning med
reservationer/sorting/repack vid omdöpning, samt webb-UI och verklig
26.3-E2E med 1–5 agenter. `PICKUP_READY` är en verifierad staging-status,
aldrig kundens slutliga `DELIVERED`.
