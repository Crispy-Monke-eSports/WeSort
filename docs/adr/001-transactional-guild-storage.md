# ADR-001: en lokal SQLite-transaktion för guildens domänmotor

Status: beslutad arkitektur i W-025 (#26). W-018 inför den första SQLite-
tabellfamiljen för observationer och revisioner, se [implementation](../MC_26_3_STOCK.md).
Order-/kvot-/journaltransaktioner återstår; nuvarande JSON-order är inte den
framtida atomiska guildmotorn.

## Problem och alternativ

Manager måste atomiskt reservera fysisk stock (inklusive hela shulkers kontra
inre items), kvotutrymme och order, och skapa audit/outbox utan double spend.
Befintliga `OrderStore`, `IndexStore` och `AuditLog` använder separata JSON-
filer. Synkronisering per stash och AtomicFiles.write ger varken global
serialisering eller rollback av flera filer vid diskfel/processkrasch.

| Alternativ                      | Bedömning                                                                     |
| ------------------------------- | ----------------------------------------------------------------------------- |
| Behålla separata JSON-filer     | Behåll som steg-0-baslinje, otillräckligt för atomisk global domänmotor       |
| Gemensam egen JSON-WAL/snapshot | Kräver egen logg, recovery, låsning och migrationsmotor; onödig felrisk       |
| Lokal inbäddad SQLite           | Vald: en filgrupp på lokal volym, transaktioner/constraints utan extra tjänst |
| PostgreSQL/separat backend      | Ingen nödvändighet vid en process/1–5 agenter; större driftkrav               |

SQLite tillåter en samtidig writer och flera readers. `BEGIN IMMEDIATE` tar
skrivtransaktionen före läsning av kvot/stock; annan writer väntar eller får
BUSY och måste försöka om hela den idempotenta operationen. Se
[SQLite transactions](https://sqlite.org/lang_transaction.html).
WAL kräver lokal disk och omfattar inte atomiskt flera ATTACH-databaser, se
[SQLite WAL](https://sqlite.org/wal.html). All domändata läggs därför i en DB,
inte en DB per stash. NFS/SMB-volymer och två Manager-replikor stöds inte.

## Beslut och fysiskt format

Volym `/var/lib/wesort`: installations-/schemamarkör, config/keys, SQLite
`guild.sqlite` + WAL/SHM och ikoncache. `PRAGMA foreign_keys=ON`,
`journal_mode=WAL`, `synchronous=FULL` och begränsad busy_timeout sätts och
verifieras på varje skrivconnection. En kort serialiserad skrivkö är normalvägen;
constraints är extra skydd, inte ersättning för gemensam transaktion.

En aktuell, säkerhetsverifierad SQLite/JDBC-version låses vid implementation,
inklusive verifiering av upstreams WAL-korrigeringar och Linux amd64-native-
bibliotek. Lägg inte till en godtycklig gammal JDBC-driver i denna ADR-etapp.

Tabellfamiljer (UUID/text-ID; itemmängder som checked signed 64-bit integer):

- `guild` (exakt en rad), `schema_migrations`, `members`, `roles`, `policies`.
- `physical_sources`, `source_revisions`, `slot_observations`, `item_fingerprints`;
  unik server/dimension/stash/container/slot, inget agent-ID i den fysiska nyckeln.
- `orders`, `order_lines`, `idempotency_keys` (unik actor+scope+key samt body-hash).
- `reservations`, `reservation_resources` med parent/child-lås för box/slot.
- `quota_windows`, `quota_holds`, `quota_events`: hold och stock är atomiska;
  unik order/terminal receipt för slutlig debitering.
- `jobs`, `job_leases`, `agent_capabilities`: monoton fence och jämför-och-byt.
- `packages`, `custody_events`, `physical_journal`, `delivery_receipts`.
- append-only `actor_audit` och `outbox` i samma transaktion som händelsen.

Mängder/revisioner ska inte rundas genom floating point. Fingerprints använder
kanoniskt versionssatt komponentformat; inga otypade NBT-strängar är primärnyckel.
Ett schema byts med egen migrationstabell och rollback-/backupplan. En gammal
Manager får inte öppna ett nyare schema.

## Transaktionsgränser och konkreta race

Orderläggning: verifiera actor/purpose/recipient/destination och idempotency-
bodyhash; `BEGIN IMMEDIATE`; läs aktuell policy/kvot/source-revision; reservera
stock och kvot; skapa order, audit och outbox; COMMIT; först därefter svara och
skicka agentjobb. Nekat eller misslyckat commit lämnar inga partiella holds.
Samma idempotency key med annan body ger 409, inte ännu en beställning.

Två beställare vill ha sista 64 diamanterna: första writern reserverar 64 och
committar. Den andra läser efter låset available=0 och nekas. Två olika stashes
kringgår inte samma medlems globala tidskvot. Disabled kvot skapar ingen spärr.
GUILD syfte kringgår inte policy eller kvot. En boxreservation och en inner-item-
reservation använder samma konfliktträd och kan inte båda committas.

Jobbclaim: läs eligibility och tilldela lease+ny fence i samma transaktion.
Resultat kräver matchande agent, lease, fence, jobbversion och idempotent event-ID.
Utgången lease återtas inte om fysisk intent saknar slutkvittens: resursen
hålls i reconciliation och orsakar inte en ny pickup från samma inventory.

Leveranskvittens: validera mottagare/destinationsrevision/manifest/custody och
verifieringsbevis; debitera kvot en gång, slutmarkera reservation, uppdatera
paket/order och audit/outbox i samma commit. Intern flytt byter custody/plats,
men ger ingen medlemsreceipt och ingen slutlig kvotdebitering.

## Fysiska operationer och krasch

Minecraft-interaktionen sker utanför DB-transaktionen. Spara intent med
förväntade slotfingerprints före tillstånd att klicka; agenten journalför
lokalt och rapporterar verifierad efterbild. Manager kvitterar revision/custody
innan nästa steg får börja. Timeout är ett okänt utfall, inte bevis för rollback.
Alla oklara fall spärrar berörda resurser och går till NEEDS_RECONCILIATION.

Testkrav före aktivering: parallella sista-stock-order, cross-stash kvotrace,
box/inner-item-race, dubblerade requests/receipts, gammal fence, diskfull/IO-fel,
processkill vid varje commit-/intent-/kvittensgräns och återstart med gammalt
schema. Crash-test ska visa atomisk domänstate och kvarhållen fysisk osäkerhet.

## Övergång och backup

Migrera endast redan markerad WeSort-installation i en separat versionsstyrd
migration: stoppa gamla writers, ta verifierbar backup, bygg ny DB med samma
WeSort-identiteter och kvitterade observationer, jämför invariants, byt writer
atomiskt. Förbjud dual-write från JSON och DB. Befintliga osäkra fysiska order
måste reconcileras före övergång. Ingen Qwerte-data importeras.

Aktiv DB säkerhetskopieras med SQLite backup-API eller verifierad checkpoint/
quiesce, inte genom att kopiera bara guild.sqlite medan WAL används. Inkludera
alla egna secrets/schema/journal som behövs för samma installation. Restore
sker offline och verifierar schema/integritet före readiness. Full driftbackup
implementeras/testas i W-044; W-055:s volymtest är inte detta bevis.
