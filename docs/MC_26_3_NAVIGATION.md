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
lokal lagernavigation. Portaler och global routing
ingår i W-036. Ett nekat oladdat mål i testdrivern innebär inte att generisk
Baritone förbjuder all chunkstreaming. Endast 26.3-targetet är aktivt per #11/#139; äldre 1.21.x-targets är historik.

Lokal körning dokumenteras i [rapporten](verification/W-016-navigation.json).
Rapportens bascommit och dirty_worktree anger att provet föregår issue-
committen. Workflow-artifacts innehåller exakta commitbundna omkörningar,
route.json, actions.jsonl, riktiga klient-/serverloggar och skärmdump.

## Live-status #141: BLOCKED (ej PASS)

Verkligt 6b6t-liveprov (inloggning, lobbyportal, Manager-presence, säker readback
på riktig 6b6t-server) är BLOCKED i denna miljö och redovisas aldrig som PASS
här. Lokala 26.3-harnessar, mockade paket eller CI ensamt kan inte stänga #141.
Se docs/P0-ACCEPTANCE.md: kör `scripts/ci/solo-agent-preflight.sh` (med `--live`
på 6b6t-värden) som grind före varje liveförsök; allt som inte kan bevisas
statiskt rapporteras BLOCKED. #197: chunk-await i walkNear/walkReach/
walkXZ — oladdat mål väntar på chunks i gaten (ingen Baritone-goal armeras,
alltså ingen RELEASED-latch); chunk-ankomst och retry ägs av Baritone +
NavigationRuntime (UNSAFE_BLOCK "unloaded ..."/RELEASED auto-retry med backoff,
aldrig manuell resume). Det är en förutsättning för liveprovet, inte själva beviset.

## Pausmatris #197: varje latch har en bounded väg ut (aldrig manuell resume)

Ingen navigationspaus parkerar för evigt. Källa: `src/26.3/java/com/porter/wesort/compat/NavigationRuntime.java`
(`pauseTick`), `common/.../inventory/NavigationRecovery.java` (klassificering).

| Latch                                                                     | Bounded väg ut                                                                                                                                                                                                                                                |
| ------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `PAUSED_NAVIGATION_RELEASED/STUCK/TIMEOUT/INVENTORY/PHYSICAL_UNCERTAINTY` | Transient: auto-resume med backoff (`retryable` → `retryAt`, `delayMs` 5s→60s cap) när `InventorySync` är redo och journalen inte är fenced                                                                                                                   |
| `PAUSED_NAVIGATION_UNSAFE_BLOCK` med `unloaded ...`                       | Transient: samma auto-resume när cellen är laddad och `NavigationGuard.permits` gäller. Målcellen förklassas i `allowedRoute` före pathing (read-only): oladdat mål pausar direkt som `unloaded target ...` och återhämtar med backoff — aldrig blind pathing |
| `PAUSED_NAVIGATION_UNSAFE_BLOCK` utan `unloaded`                          | Degraderas till omplanering efter 3 bounded retries (`UNSAFE_BLOCK_MAX_RETRIES`); gräver aldrig genom barriären                                                                                                                                               |
| `OTHER_PROCESS/CANCELLED/FOREIGN/ DISCONNECTED/WRONG_DIMENSION`           | Operator-latch: expires efter idle-timeout (10 min bevakad, 5 min headless `dedicated`) och omplanerar; stal aldrig goalen                                                                                                                                    |
| Fenced journal (`blocked/awaitingMirror`)                                 | Håller pausen medan osäkerheten består (ålder frigör aldrig en fysisk fence, #210–216 — att släppa på tid vore en duplicerad fysisk handling); ingen tyst parkering: färdigt kvitto laddas upp på nytt, varning upprepas varje minut efter 5 min, auto-resume när readback är verifierad eller managern nåbar igen |

`walkNear/walkReach/walkXZ` väntar på chunks i gaten: ett oladdat mål armerar
aldrig en Baritone-goal (ingen RELEASED-latch), utan stannar med "waiting for
chunks" medan watchdoggen tickar och NavigationRuntime äger retry med backoff.
Målet döljs inte — klassificeringen ovan gäller oförändrat.
