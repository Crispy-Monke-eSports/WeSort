# W-021: verifierad repack, top-up och bulk i Minecraft 26.3

WeSort behåller den faktiska StashSorter/Compactor-pipelinen och dess
Signs-kategorier. Denna ändring gäller endast Minecraft 26.3 (Java 25).
Manager använder Java 21. Ingen Qwerte-installation eller befintlig spelvärld
behöver läsas eller importeras.

## Faser och verifiering

`PhysicalWork` följer SELECT → PLACE → OPEN → TRANSFER → VERIFY → BREAK →
PICKUP → STORE → COMPLETED. Fler bulkronder kan gå tillbaka till SELECT medan
den verifierade boxen står kvar. Ett misslyckat förvillkor ger FAILED utan
repack-intent. Ett osäkert fysiskt resultat ger NEEDS_RECONCILIATION och stoppar
fler fysiska försök. Händelserna innehåller operationens ID och fas, aldrig
ett uppfunnet globalt UUID för Minecraft-boxen.

- **Select/place:** namngivna shulkers, kit och skyddad/staging/excluded stock
  lämnas utanför automatisk repack. En hotbar-pickaxe, tillåten game mode,
  verifierbar metadata och känd lagringskapacitet krävs innan placering.
- **Open:** inventory måste ha en full serverframe och ordnad block-ACK som
  binder just den placerade shulkern till den öppna menyn. En annan öppen
  shulkermeny räcker inte.
- **Transfer/verify:** varje click-batch väntar på W-015:s fulla readback och
  REQUEST_STATS-barriär. En separat VERIFY-fas jämför exakt rekursiv metadata
  och antal i inventory plus box före/efter flytt. Cursor måste vara tom.
- **Break:** position och blocktyp kontrolleras, en ledig inventory-slot
  krävs och upprepad nekad brytning är begränsad. Servern måste bekräfta
  blockändringen. Pickaxen kan slitas en gång vid den specifika brytningen;
  bara exakt härledda tillåtna toolutfall accepteras. Alla andra slots och
  komponenter jämförs fortfarande exakt. Reglerna sparas i pending-journalen.
- **Pickup:** hela yttre metadata och samtliga 27 inre slots måste matcha
  snapshoten från VERIFY. En ny shulker av annat slag eller ett ökat totalt
  boxantal räcker inte. En full serververifiering krävs också för passiv
  pickup. Redan burna identiska boxar räknas separat och förväxlas inte med
  den upplockade boxen; spelarens egna skyddade slots lämnas kvar.
- **Store:** pickup avslutar inte operationen. En ny serverbekräftad
  destination måste innehålla ytterligare en exakt matchande box samtidigt
  som motsvarande antal lämnat agentens inventory. En gammal identisk box
  i stashen räcker inte. Full destination eller osäker readback lämnar en
  återställningsspärr och diagnostik.

Placering och öppning får högst tre verifierade försök. Fasgränsen är 60 s,
pickup 120 s och hela operationens absoluta gräns 300 s. Den befintliga
inventory-/blockkvittensen har dessutom 8 s timeout. Återanslutning med en
stående box och otillräckligt återställningsbevis spärras. Full fasjournal,
startup-reconciliation och användarbeslut implementeras i W-023; denna issue
påstår inte att automatisk crash recovery i varje fas redan är färdig.

## Koordination och reservationer

W-019:s signerade exklusiva serverlease gäller även repack och compaction.
En öppen Manager-order spärrar automatiska repack-flyttar; ett nytt orderförsök
spärras under beviljad sorteringslease. Fysisk slot-/hierarkiallokering är
fortfarande W-024. Lokal automation utan Manager är fortsatt lokal och kan
inte koordinera okonfigurerade spelare eller andra program.

## Verkliga spelprov

`scripts/mc26/repack-harness.py` använder egna nya vanilla 26.3-världar,
Fabric/Meteor/WeSort och faktisk produktionsautomation. Fallen är:

| Fall | Förväntat verifierat utfall |
| --- | --- |
| mixed | Främmande dirt och exakt namngivet skadat svärd tas ur boxen; diamond-boxen plockas upp och lagras |
| partial | 60 + 10 diamonds blir 64 + 6 i korrekta boxslots |
| bulk | Ny bulkbox packas av två lösa fulla stacks |
| compact | 27 fulla stacks konsolideras till en lagrad box |
| rounds | Begränsade carry-slots tvingar flera verifierade bulkronder med boxen kvar i världen mellan dem |
| no-tool | Explicit förvillkorsfel; boxens inre metadata oförändrad |
| no-empty | Explicit boxbrist; items förblir lösa och bevarade |
| full-stash | Korrekt kategoriserade fulla destinationer ger recovery-spärr |
| full-inventory | Fyllt inventory under den fysiska operationen ger recovery-spärr |
| lag | Fördröjd trafik passerar endast med verkliga readbacks |
| abort | Avbruten nedlänk lämnar spärr; återställd länk orsakar inga nya automatiska clicks |
| reserved | Signerad Docker Manager-order på boxinnehåll stoppar riktig repack före mutation |
| blocked | Adventure-mode nekar placering med explicit förvillkorsfel |
| named | Även en oskyddad namngiven mixed box behåller hela sin metadata och layout |
| identical | En redan buren personlig box med exakt samma slutliga innehåll förväxlas inte med arbetsboxen |

Varje fall jämför oberoende RCON-server-SNBT före/efter med rekursiva antal
**och komponenter**, inklusive boxar som står kvar och faktiska itemdrops.
Pickaxens förväntade slitage särredovisas. Den skyddade kitcontainerns NBT måste
vara identisk. Kapacitetsinjektionens tillförda items räknas separat; inget
befintligt item skrivs över av injektorn.

Serverns menucursor finns inte i entity Inventory-NBT. Efter en spärrad
felinjektion gör testoperatören därför en **uttrycklig manuell menustängning**
och verifierar serverns `player.cursor` innan full mängdavstämning. Detta är
en redovisad återställningsåtgärd i testet, inte en automatisk produktretry.
Clickräknaren får inte öka under återställningen. Spelproven avser således
både verifierad completion och säkert spärrade manuellt avstämda felutfall.

Lokala resultat sparas i `docs/verification/W-021-*`. Hosted CI kör varje
scenario i en egen jobbmätning i `mc26-repack.yml`; lokala PASS är inte i sig
bevis på en grön CI-körning. W-015:s separata verkliga inventory-, nätverks-
och kraschprov körs även som regression mot den ändrade toolkvittensen.
