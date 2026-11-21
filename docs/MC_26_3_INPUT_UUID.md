# W-0XX – inputkista och WeSort-UUID på shulkerboxar

## Inputkista

Varje stash kan ha en markerad **inputkista** (`Pick: Input chest` i
wesort-stash-sorter). Den får stå utanför stashens bounding box, likt
delivery-containern. Spelare lägger där items och shulkerboxar de inte längre
vill ha; botten tömmer den som en drop-off (allt räknas som misplaced och
sorteras in i stashet) och inget sorteras någonsin in i den.

Tekniskt sätter markeringen både `input` och `dropOff` på boxen, så all
befintlig drop-off-logik gäller (tömning, recheck-schema, lock, heartbeat,
indexering). `pruneForeign`/`pruneStrays` undantar inputkistan och promenader
till den får lämna perimetern, precis som delivery.

## WeSort-UUID på shulkerboxar

UUID:na (`WeSort-<canonical UUID v4>`, se `ShulkerUuid`) är intern
spårning så att registret kan följa boxar mellan kistor. De är inget en
slutanvändare förväntas arbeta med: botten sätter, flyttar och avregistrerar
dem själv.

- **Tilldelning (idle):** När det annars inte finns något att sortera döper
  botten om shulkerboxar som saknar giltig UUID, har ett ogiltigt namn, eller
  delar UUID med en annan box (då behåller en kopia namnet och resten får nya).
  Tomma shulkers döps aldrig om: kit-baser och empty-poolen kräver namnlösa
  boxar. Omdöpning använder det vanliga anvil-flödet (inklusive
  XP-flaskor vid behov) och sker aldrig medan en order är igång eller väntar
  på att claimas, så packade ordrar matchar fortfarande.
- **Borttagning ur systemet:** När en UUID-märkt box levereras ut med en order
  rapporterar botten dess UUID med orderrapporten och managern avregistrerar
  den (`DEREGISTERED_FOR_ORDER`), så den inte längre förväntas finnas kvar.
- **Försvunnen box:** Syns ett registrerat UUID inte längre i någon läst
  kista avregistreras det (`VANISHED`). Dyker samma box upp igen i en kista
  med namnet kvar registreras samma UUID på nytt.
- **Flytt:** Byter en box kista följer registreringen med (samma UUID, ny
  källa+sökväg). Ses samma UUID på två ställen samtidigt gäller den
  ursprungliga posten som duplikat.
