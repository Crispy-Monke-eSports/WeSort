# SNBT-kontrakt v1 (PR2: #224)

Auktoritativt lagringskontrakt för WeSort-inventarier. Status: **capture-side
landat** (codec + identitetstyper + snapshot-kuvert i `common`). Transport
(#227), SQLite-persistens (#228), journaler/custody/mallar (#229) och CI/E2E-grind
(#230) återstår i senare led.

## Version

- `wesort-snbt:1` (`SnbtCodec.CONTRACT_VERSION`), pinnad mot Minecraft `26.3`.
- Bump vid varje kanoniseringsändring. Gamla writers accepteras aldrig som nya
  writes; okänd version = fail-closed + fresh observation.

## Auktoritet

- Auktoritativt innehåll = **kanonisk SNBT-text** (typad, fullständig):
  verifierad serverobservation → registry-medveten `ItemStack`-codec/`NbtOps`
  → kanonisk SNBT → signerat wire-kuvert → Manager-parse/validering →
  transaktionell SNBT-lagring → härledda projektioner → UI.
- JSON är endast transportkuvert eller härledd UI/API-projektion.
- SQL-kolumner för id/timestamps/permissions/revisioner/index är aldrig
  content-källor. Aggregerade `items/shulkers`-summor bevisar aldrig lager.

## Kanonisk form (sammandrag)

- Tag 1–12 exakt (`1b≠1s≠1≠1L≠1.0f≠1.0d`); `TAG_End` serialiseras aldrig.
- `[B;`/`[I;`/`[L;` med versal prefix; list/array-ordning signifikant.
- Inga blanksteg utanför strängar; dubbelciterade strängar/nycklar med
  deterministisk escaping; nycklar sorterade i UTF-16-ordning (dokumenterat).
- Decimala signerade med explicita suffix; kortaste round-trip för
  float/double; icke-finita värden saknar kanonisk form (avvisas).
- Full konsumtion + gränser (`MAX_DEPTH=64`, `MAX_NODES=65536`, `MAX_BYTES=4MiB`);
  duplicate keys avvisas; ingen JSON-tree-fallback eller partiell item.

## Digester (SHA-256 över UTF-8, domänseparerade)

- `content-v1`: exakt variant (id + full count-oberoende komponent-SNBT).
- `stack-v1`: variant + count (slot-oberoende).
- `snapshot-v1`: ordnad slot-lista + kapacitet.
- `slot-proof-v1`: source + path + snapshot-digest.
- Domänsträng + kontraktsversion hashas före innehållet; återanvänd aldrig
  en domän för en annan identitet.

## Identitetsdomäner (#266)

- `BaseItemId` (registry-id + schema-kontext): sök/display-gruppering.
- `ExactVariantId` (id + full kanonisk komponent-SNBT, SHA-256): exakt likhet.
  Ingen trunkering (`#sha1(..).substring(0,8)` är display-nyckel, aldrig identitet).
  Ingen bare-id-fallback vid encode-fel — fail-closed/karantän.
- `CatalogGroupId` (policy + värde, t.ex. `tier:`, `bulk:`, `s:`): sortering/sök.
- Fysisk identitet: `ContainerIdentity` (var) + `ShulkerUuid` (vilken låda),
  oberoende av innehåll. UUID är aldrig en hash av identiska items.

## Snapshot-kuvert (#226)

`SnapshotEnvelope`: `SourceKind` (player-main/equipment/offhand/ender,
cursor-transient, chest-single/double, barrel, shulker-placerad/buren/nästlad,
bundle, other), kapacitet, slot-lista (`{slot,item-SNBT,count}`), lagermeta
(facing/type/båda positioner), proveniens (server/dimension/session/sequence/
observedAt/registryContext).

- Tomma slots = frånvarande entries, aldrig fejkade `air`-stacks.
- Observerade counts (1..99) bevaras som evidens; över-max-stack karantänas
  vid fysisk hantering istället för att clampas.
- Dubbelkista = en logisk 54-snapshot; cursor/equipment/ender är separata
  kuvert och dubbelräknas aldrig mot containerlager.
- Stale/UNREACHABLE behåller historik men allokerar aldrig.

## Shulker-UUID (#67/#68)

- Varje hanterad shulker har verifierad `WeSort-<UUIDv4>`-anvil-namn.
- Kanonisk SNBT bevarar `minecraft:custom_name` exakt; UUID-identitet är
  oberoende av content-fingerprint. Intern flytt behåller, extern export
  strippar fysiskt + avregistrerar atomiskt vid verifierad export.

## Vad som uttryckligen INTE görs här

- Ingen migrering/konvertering/backfill. Clean install + färska observationer.
  Gammal data raderas aldrig automatiskt men accepteras aldrig som giltig SNBT.
- Ingen transport-/persistens-adoption (det är #227/#228). `PhysicalSlots`-JSON,
  `physicalVersion:1/2`, `payload`-JSON och `StashIndex`-katalognycklar lever kvar
  som härledda/legacy tills adoptionen landar; de är inte auktoritativa.
- Ingen mock-PASS: fysisk E2E kräver verklig 26.3 + separat 6b6t-live (#16/#141).

## Nästa steg

1. #227: versionerat klient→Manager-protokoll (`physicalVersion:3`, SNBT-kuvert).
2. #228: SQLite SNBT-kolumner + härledda projektioner (rebuildbara vyer).
3. #229: journaler/custody/mallar/recovery + UUID-livscykel på SNBT-bevis.
4. #230: CI + Minecraft-E2E på ren data (blockerande grind).
