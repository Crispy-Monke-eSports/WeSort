# W-018: fysiska lagerobservationer

Steg 3 börjar med versionssatt observation och läsbar osäkerhet. Detta inför
inte W-024:s fysiska reservationshierarki eller W-040:s cross-stash-allokering.

## Wire-versioner (#227)

| `physicalVersion` | innehåll | auktoritet |
| ----------------- | -------- | ---------- |
| 1 | aggregerade `items`/`shulkers` | accepteras men bevisar aldrig orderbart lager |
| 3 | v1-aggregat + kanonisk `snbt` + `fingerprint` (endast SNBT, inga `slots`/`slotVariants`) | auktoritativ SNBT-väg |
| 4 | alla v3-fält oförändrade + `envelope`-objekt per FRESH-källa (`SnapshotEnvelope`-JSON, `wireVersion: 1`); `envelope: null` exakt när `snbt: null` | auktoritativ endast när kuvert och SNBT-snapshot stämmer helt överens (dual-carry med korskontroll); oenighet avvisas |

Allt annat (0, 2, 5, …) avvisas fail-closed med `Unsupported physical
observation version` utan partiella writes: ingen revision, fingerprint eller
saldo rörs och ingen färskhet förnyas. Kuvertet bär `kind`, `capacity`,
`slots` (`{slot, itemSnbt, count}` med endast kanonisk item-SNBT),
`storageMeta`, `provenance`
(`server`/`dimension`/`session`/`sequence`/`observedAt`/`registryContext`) och
`snapshotDigest`. Kanonisk form är pin-nad till `wesort-snbt:1`
(`SnbtCodec.CONTRACT_VERSION`) mot Minecraft `26.3`
(`SnbtCodec.MINECRAFT_DATA`).

Fruset för #227: `/api/v1/*`-webbläsarformer, `stockEpoch`/`stockRevision` +
signerad 409-konfliktform, `IndexStore.validate`s aggregatregler och
`WRITE_LEGACY_SLOT_PAYLOADS=false`.

## Identitet och bevis

`c:<sha256>` beräknas från en kanonisk JSON-tupel med server, dimension och
sorterade blockpositioner. En dubbelkista har två angränsande delar men en
identitet och ett inventory. Klick på motsatta halvor ger samma ID. Typ,
slotantal och full komponentfingerprint hör till observationen. Ett ID anger
platsen; det påstår inte att en utbytt kista är samma eviga objekt.

Klienten kopplar serverns fulla inventory-paket till en ensam storage-interaktion
och dess ordnade native block-ACK. Överlappande öppningar ger inget nytt
observationsbevis. Lokal prediction, ofullständiga slotuppdateringar och en
öppnad tom GUI räcker inte. Efter fysisk ändring krävs ny full readback.

Samma fulla serverpaket ger samma witness även om två lokala observatörer läser
det. Witness innehåller sessions-ID, sekvens, mottagningstid, full fingerprint
och den Manager-epoch/revision som var känd vid avläsningen. Kopiering, indexpush
och heartbeat förnyar inte tiden. Ny värld/anslutning gör gamla klientbevis stale.

Manager tilldelar monoton indexrevision och fysisk källrevision i en enda SQLite-
transaktion. En ny observation måste bygga på aktuell käll-/geometrirevision.
Ett försenat index får signerad 409 med aktuell epoch/revision och klienten
begär verklig rescan. En upprepad kvittens kan inte ändra mängd eller återuppliva
en källa efter avbrott. Rescan-markeringen i sig höjer inte den fysiska revisionen,
så en redan pågående korrekt serveravläsning kan fortfarande slutföras.

## Färskhet och saldo

`WESORT_STOCK_MAX_AGE_SECONDS` har standardvärdet 1800 och tillåter 1–86400.
Håll TTL minst 4x survey-golvet (60 s, se #201): under ~240 s är steady-state omöjlig.
Manager använder den tidigaste av egen mottagningstid och klientens pakettid.
För gammal tid, bakåtgående Manager-klocka och omstart stänger tillgängligheten.
En felställd klientklocka kan därför kräva ny avläsning men inte förlänga TTL.

`GET /api/v1/stock` kräver VIEW och applicerar stashbehörighet före aggregering.
Det ger guildens observerat/reserverat/disponibelt/okänt per itemvariant och
per-stash-saldo i `stashes` samt fysiska källor med dimension, delar, tillstånd, revision och `verifiedAt`.
Samma fysiska inventory räknas en gång. `reservationPrecision=aggregate` anger
att befintliga orderreservationer fortfarande gäller stashens aggregerade rader;
det finns ännu inget påhittat reservationsantal per fysisk slot/container.
En källa som flera stashes delar räknas i observerat men spärras för nya order
tills W-024 kan hantera gemensamma fysiska holds. Webbens guildsaldo visar dessa
mängder och varje källas status. Streamer-läge tar bort fysiska positioner och
innehållsdetaljer; scoped läsare får inte en annan privat stashs historik.

Tillstånd inkluderar FRESH, UNKNOWN, STALE, STALE_AGE, STALE_STARTUP,
UNREACHABLE, WITHDRAWN och SUPERSEDED. Borttagna eller oåtkomliga källor
behåller senast observerade mängder som okända; de blir inte bevisat tomma.
En ändrad dubbelkistegeometri ersätter äldre överlappande inventories.

Äldre index utan fysisk observation förblir läsbara men kan inte ge nya order.
Orderläggning och claim återkontrollerar färskhet under samma observationslås.
Heartbeat begär rescan vid start/återanslutning/utgången färskhet; den faktiska
StashSorter kör scan-only när rescan begärs och sorterar inte varor i testet.

## Persistens och Docker

`guild.sqlite` med WAL/SHM ligger i den egna WeSort-volymen. W-018:s schema 1 använder
application_id W S R T och avvisar främmande/nyare DB-format samt symlänkar.
`stock_sources`, `stock_parts`, `stock_members`, `stock_indexes`, `stock_meta`
committas med BEGIN IMMEDIATE, synchronous FULL och busy_timeout 5000 ms.
W-019 uppgraderar databasen till schema 2 för sorteringslås utan att ta bort
observationshistorik, se [sortering](MC_26_3_SORTING.md).
SQLite-indexen är auktoritativa vid förlorad äldre JSON-spegel. En omstart
behåller historik men kräver ny fysisk avläsning innan order kan tas.

JDBC är låst till [xerial 3.54.1.0](https://github.com/xerial/sqlite-jdbc/releases/tag/3.54.1.0).
Versionen innehåller SQLite efter den dokumenterade
[WAL-reset-korrigeringen](https://www.sqlite.org/wal.html).
Linux amd64-biblioteket extraheras vid bygget till image-lagret, så runtime inte
behöver exekvera native-kod från /tmp (monterad noexec). Inga externa DB-tjänster.
Som tidigare stöds en Manager på lokal volym. Ta backup efter stopp av Manager,
med hela dess volym; kopiera inte enbart en aktiv SQLite-huvudfil.

## Verifiering

Enhetstester gäller revisioner, återspelning, rollback, två samtidiga writers,
TTL, omstart, geometri, behörighet, order-/claim-spärr och återläsning utan
JSON-spegel. Webbtestet verifierar verklig DOM-rendering av mängder, osäker
källa och ofarlig visning av itemnamn som innehåller HTML.

`python3 scripts/mc26/stock-harness.py --manager-image <byggd-image>` startar
en egen vanilla 26.3-server, riktig Fabric/Meteor/WeSort-agent och Docker
Manager. Provet använder vanlig kista, trapped dubbelkista, vanliga dubbelkistor
och barrels, motsatta halvor, faktisk QUICK_MOVE/retur, TTL, serverändrad mängd,
spärrad kista, borttagen kista, försenad signerad snapshot och Manager-omstart.
Ingen användarvärld eller Qwerte-data öppnas. Fast placerad shulker i grundfixturen
ligger utanför denna scanners registrerade källor; hierarkin införs i W-020.
Resultat sparas separat från CI-körningarna i `docs/verification`.
