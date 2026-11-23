# W-015: verklig inventory- och blocksynk på 26.3

Kör `python3 scripts/mc26/inventory-harness.py` med samma JDK, Docker och
grafikkrav som [grundvärlden](MC_26_3_E2E.md). Workflow
`mc26-inventory.yml` kör samma scenario med Xvfb/Mesa. Varje körning startar
en ny privat vanilla-server, extern Fabric-klient och publicerad Docker
Manager med egen volym. Inga befintliga Prism-/Qwerte-data används.

## Vad som räknas som kvittens

26.3 använder ContainerInput och menyns containerId/stateId. Lokal prediction
är ingen kvittens. `InventorySync` fångar WeSorts riktiga inventory- och
blockanrop, även Meteor InvUtils, och journalför förväntat fullständigt
slottillstånd och cursor innan paketet lämnar klienten. Kanonisk typbevarande ItemStack
CODEC-NBT omfattar antal och komponenter inklusive nästlat shulkerinnehåll.

Efter en sammanhängande klickbatch begär klienten full serverläsning.
Vanilla 26.3:s AbstractContainerMenu accepterar slot -1; QUICK_MOVE på negativ
slot returnerar före någon itemoperation. Ett stateId -1, utanför serverns
0–32767, tvingar ServerGamePacketListenerImpl att skicka fullständigt innehåll.
En efterföljande REQUEST_STATS behandlas på servertråden och svarar även med
tom statistik. Bara en ny full innehållssnapshot med exakt samma menu, slots
och cursor, följd av detta svar, släpper spärren. En gammal snapshot får inte
återanvändas. Detta är verifierat mot den checksumlåsta vanilla-26.3-servern
i harnessen, inte ett löfte om godtyckligt modifierade servers protokoll.
Oväntade stats-svar eller innehåll under en pågående operation kan ge en
säker paus; det finns ingen automatisk fysisk retry.

Blockoperationer kräver dessutom vanillas sequence-ack efter tillämpad
serverkorrigering och rätt block på den laddade positionen. Vid väntan
stoppas nya automatiserade ingrepp före lokal prediction. Sorter väntar och
Manager publicerar inte ett index byggt på okvitterat inventory. Vanliga
manuella spelarklick används inte som automatiska kvittenser.

Efter åtta sekunder utan verifiering, mismatch eller avbrott blir status
NEEDS_RECONCILIATION. Det ursprungliga förväntade eftertillståndet behålls i
`meteor-client/wesort/inventory-sync/pending.json` under den egna gameDir.
Filen skrivs atomiskt med fsync före paketet, mode 0600 i en 0700-katalog.
Omstart återspelar inga klick. Skadad/okänd journal stoppar automation.

`.wesort-reconcile` begär uttryckligen en ny läsning av det ursprungliga
eftertillståndet. Rätt server, dimension, spelar-UUID, källa och eventuella
blockändringar måste stämma. Vid behov får den exakta närliggande källan
återöppnas tomhänt som läsning. Om servern fortfarande har förtillståndet
förblir agenten stoppad. Kommandot utför inte den fysiska operationen igen.
Journalen tas bort först efter verifierat inventory och blockkvittens.

## Faktiskt körda scenarier

Den lokala [rapporten](verification/W-015-inventory.json) kommer från en riktig
26.3-körning före issue-committen. CI-artifacts binder omkörningar till exakt
pushad commit och innehåller actions.jsonl, serverns före/efter-NBT,
klientloggar före/efter omstart, Manager-index och riktig skärmdump. Endast
syntetiskt testinventory journalförs i rapporten; credentials publiceras inte.

| Scenario | Verifiering |
|---|---|
| PICKUP, cursor, spelarens slots, QUICK_MOVE | Full serverläsning och oberoende RCON-NBT efter varje flytt; dubbelkistans menu-slot 0 motsvarar den fysiska högra halvan |
| Verklig fördröjd servertrafik | Ingen andra withdrawal eller slutförd åtgärd före kvittens |
| Saknat svar längre än timeout | Beständig paus, sena paket återupptar inte; uttrycklig läsning kan verifiera redan tillämpat eftertillstånd |
| Namngiven fylld shulker | Place/open/break/pickup/return bevarar 64 dirt och exakt komponentfingerprint |
| Chest, barrel, vanlig skyltartikel | Kista och tunna placeras, läses och plockas upp. Redigeringsåtgärden avvisas. En fysisk skylts text och vanliga skyltartiklar förblir oförändrade enligt servern. |
| Tunnlock | Harnessen växlar den privata serverns riktiga lockstate. Klientens bevakning begär en enda omläsning efter stängning. |
| Enchantments, damage, custom names, bundle | Färska snapshots/index och oförändrade serverkomponenter före/efter |
| För långt bort men laddad container | Nekas före interaktion |
| Avbrott före leverans och riktig JVM-omstart | Pending bevaras, noll återspelade klick; serverns oförändrade förtillstånd nekas som falsk reconciliation |
| Försök att kringgå testdriverns spärr | Produktionsmixin stoppar före prediction och paket |
| Totals/Manager | Alla ursprungliga servercontainers återställda exakt; signerad verklig agent publicerar matchande 26.3-index; osäker åtgärd ändrar inte publicerat index |

Enhetstester prövar protokollspärrens negativa fall separat. Baseline-klienter
behåller sin befintliga implementation; spärrens Minecraft-adapter är 26.3.
Detta är inte full guildorder-, kit/repack- eller multiagentverifiering.
Full fysisk operations-/custodyjournal hör fortfarande till W-023.
Anticheat-plugin testas inte genom att kalla vanilla-servern ett sådant test;
provet verifierar verklig paketfördröjning, nekad/okänd synk och säker paus.
