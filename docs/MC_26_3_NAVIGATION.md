# W-016: Baritone i den privata 26.3-världen

Kör `python3 scripts/mc26/navigation-harness.py` med samma förutsättningar
som [grundharnessen](MC_26_3_E2E.md). `mc26-navigation.yml` kör samma prov
med Xvfb/Mesa. Den externa Fabric-klienten använder den låsta riktiga
Baritone-implementationen från W-013/W-014, inte en mock eller bara API-stub.

Alla WeSort-mål i `BaritoneHelper` registreras på 26.3 hos
`NavigationRuntime`. Målet har 60 sekunders absolut deadline och högst åtta
sekunder utan minst 0,4 blocks förflyttning från senaste framstegspunkt.
Klockan är monoton och fortsätter under path calculation. Ett ersättningsmål
utan föregående avslut nollställer inte dessa gränser. Fel dimension, tappad
anslutning, okvitterat inventory, annan styrande process eller ett övergivet
Baritone-mål ger PAUSED. Sorter och Manager fortsätter inte medan pausen är
aktiv. En avslutad normal Sorter-rutt släpper sin övervakning utan felpaus.

`.wesort-navigation` visar status. `cancel` avbryter endast WeSorts ägda mål;
`resume` kräver ansluten värld och verifierat inventory, och släpper pausen
för ett nytt mål. Kommandot återspelar ingen gammal rutt. Vid fel, cancel
eller ankomst släpps vårt mål och våra tillfälliga Baritone-inställningar.
En annan aktörs mål avbryts inte. Befintlig confine-logik bevarar inställningar
som spelaren själv hunnit ändra under gång.

WeSorts lagergång använder `confine`: allowBreak och allowPlace är false.
Safe walk förbjuder parkour, vattenhinksfall och riskfyllda fall. Lagerblock
läggs dessutom i skyddslistan under provet. Tillfällig shulkerplacering och
brytning görs uttryckligen av inventory-/blockkoden efter avslutad gång,
och verifieras separat i [W-015](MC_26_3_INVENTORY.md).

## Faktiska spelprov och gränser

Harnessen bygger nya fixtures och en oberoende serverkopia av hela
lagerområdets block och block entities med vanilla clone. Efter alla prov
måste `execute if blocks … all` matcha kopian, och samtliga ursprungliga
container-items måste vara identiska. Det jämför även golv, trappa, skyltar
och tomma utrymmen, så en oavsiktlig placering upptäcks.

| Prov                              | Kontroll                                                                                                                                     |
| --------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| Rundtur mellan flera dubbelkistor | Riktig Baritone-gång, serverlevererat containerinnehåll vid varje stopp                                                                      |
| Laddad chunkgräns x=16            | Passeras under gång, inte genom teleport                                                                                                     |
| Höjd och arbetsyta                | Riktig trappgång till y=67, öppning av upphöjd barrel och återkomst                                                                          |
| Barrel och shulker                | Gång inom räckvidd och verklig öppning                                                                                                       |
| Cancel                            | Stillastående och inaktiv path efter avbrott, gamla inställningar återställda, nytt mål nekas tills resume                                   |
| Otillgänglig inhägnad             | Rapporteras PAUSED när Baritone överger målet eller watchdog löper ut; ingen glasruta bryts                                                  |
| Absolut timeout                   | Serverteleport håller en verklig pågående rutt från ankomst under hela produktionsgränsen på 60 sekunder; rörelse får inte förlänga deadline |
| Dimensionsbyte                    | Riktig serverflytt till privat Nether-fixture stoppar den gamla rutten                                                                       |
| Oladdat mål                       | Testdrivern nekar före goal-start; ingen långdistansrutt godkänns av detta prov                                                              |
| Lagerblock/items                  | Serverns fullständiga områdesjämförelse och inventory-NBT måste stämma före/efter                                                            |

Enhetstester prövar fastkörning trots pågående beräkning, små positionsskak,
äkta framsteg, absolut timeout trots rörelse och dimensionsbyte. Detta är
lokal lagernavigation. Portaler, långdistanstransport och global routing
ingår i W-036. Ett nekat oladdat mål i testdrivern innebär inte att generisk
Baritone förbjuder all chunkstreaming. Äldre klienttargets behåller sin
befintliga navigationsadapter.

Lokal körning dokumenteras i [rapporten](verification/W-016-navigation.json).
Rapportens bascommit och dirty_worktree anger att provet föregår issue-
committen. Workflow-artifacts innehåller exakta commitbundna omkörningar,
route.json, actions.jsonl, riktiga klient-/serverloggar och skärmdump.
