# W-019 – verifierad sortering och skyddad stock

Signs och Tiers använder den befintliga StashSorter-motorn i riktig 26.3.
Kategorier läses från verkliga skyltar; matchning använder föremålets typ,
slang, enchantments och potion. Anvil-namn ersätter inte typen. Varianternas
komponenter och antal bevaras vid flytten och kvitteras genom W-015:s fulla
server-readback, inte genom lokal prediction.

## Destinationsval och skydd

`.wesort-sort-why` förklarar destinationsval för den variant som hålls i handen:
matchningspoäng, full container, bättre match, misc/unsigned overflow, excluded,
kit-container och skyddat lager. Kommandot flyttar inget och skriver inga
koordinater i serverloggen. En helt blockerad drop-off ger en varning och
behåller varorna i källan. Fulla containrar med delvis fyllda matchande stacks
kan nu väljas som destination; det återstående antalet går till overflow.

Pick-läget **Protect stock/staging** markerar lager som ingen automation får
flytta. Markeringen sparas i den egna stashfilen och propageras till det fysiska
indexet. Sådan stock visas som PROTECTED och är inte orderbar. Excluded,
leveransstaging och kända kitkistor används inte som normala sorteringskällor
eller destinations-/overflowplatser. Skydd kontrolleras även före klickprediction.

Att välja samma stashnamn igen återläser nu den sparade stashen med dess
markeringar. Tidigare kunde en redan provad nyckel få återläsningen att utebli,
vilket skapade ett nytt omarkerat lager vid aktivering. Dubbelkistealias bär
vidare skydd, och återspelat gammalt observationsbevis får inte ta bort skyddet.

Baritone kan avsluta sin egen rutt asynkront. Den korta avslutningen av en egen
frisläppt rutt räknas inte längre som främmande automation. Ett annat aktivt mål
eller en annan process respekteras omedelbart; grace är begränsad till en sekund.

## Samtidiga reservationer

En Manager-konfigurerad sorter får inte flytta fysiska varor utan signerad
exklusiv `/api/v1/automation`-lease för Minecraftservern. Manager kontrollerar
alla öppna order på servern under samma lås som orderläggningen. En ny order
och en ny sorteringslease kan därför inte båda vinna ett race. En annan agent
får inget överlappande sorteringstillstånd.

Detta är ett konservativt helt serverlås, i väntan på W-024:s allokering per
fysisk resurs. Inre shulker-items och helboxar tilldelas inte separat här.
Alla samverkande agenter måste använda versionen med detta protokoll.
Oanslutet lokalt läge har inga Manager-reservationer; andra spelare eller
okopplade klienter är inte kontrollerbara genom detta protokoll.

SQLite-schemat höjs till 2 med bibehållen W-018-historik; en Manager som bara
stöder schema 1 får inte öppna den nya databasen. Nyare okända scheman avvisas.

Serverns lease gäller 15 sekunder och förnyas normalt varannan sekund.
Klienten tillåter nya fysiska input högst fem sekunder från förfrågans start;
en sen kvittens kan inte förlänga tiden. Pågående inventory-verifiering håller
exkluderingen kvar. Expiry eller osäkert utfall blir beständigt
NEEDS_RECONCILIATION och frigör inte automatiskt lager för en annan agent.

Efter normal verifierad frigöring görs tidigare serverobservationer stale i
samma SQLite-transaktion. Nya reservationer måste därmed vänta på ny fysisk
avläsning. W-023 tillför återställningsvyn och den fulla fysiska journalen.

## Testunderlag

`AutomationGateTest` testar reservation/sortering samtidigt, flera agenter,
expiry, återstart och att verifierad frigöring invalidiserar gamla snapshots.
Stocktestet kontrollerar att skydd varken ignoreras eller tas bort med ett
återspelat witness. Webbens guild- och ordervyer visar inte disponibelt saldo
under pågående eller osäker fysisk sortering.

`scripts/mc26/sorting-harness.py --manager-image <image>` använder egen vanilla
26.3-server, extern Fabric/Meteor/WeSort-klient och Docker Manager. Själva
produktionssortern flyttar blandat innehåll efter verkliga skyltar. Provet
kontrollerar namngivet skadat enchanted gear, slang, potions, delvis/full
mottagare, misc och osignerad overflow, excluded och skyddad kitbox, Tiers,
helt fullt lager samt samverkan med signerad Manager-reservation.

CI kör samma skript i `mc26-sorting.yml`. Lokala rapporter under
`docs/verification` och hosted CI-resultat är separata bevis. Inga befintliga
användarvärldar eller Qwerte-data används.
