> **Versionavveckling (26.3-only):** Klientkällor, byggtargets, recipes och Manager-creative-tab-resurser för Minecraft 1.21.4/1.21.11 samt gamla verktyg för att generera dessa versioners assets har avlägsnats. Klienten byggs med Java 25 och endast 26.3; Manager använder fortsatt Java 21. Äldre testresultat i denna fil är historiska och inte supportåtaganden.

# Avgränsningar och borttagningar

Beslut och originalfilvägar finns i [CLEANUP_PLAN.md](CLEANUP_PLAN.md).
Ingen borttagning avser användardata. Git bevarar originalkod och historik.

## W-005 / #6: farming och pearl

Borttaget: AutoTill, AutoDirt, ResetPearl, hela pearl-paketet, Restocker,
StorageList och WorkPattern, deras registreringar och farmingdokumentation.
StashYield har inte längre beroende på farmingmoduler för pauskontrollen;
AutoEat, spelarinput, andra Baritone-mål och främmande inventoryändringar
fortsätter att pausa sorteringen. Ingen exklusiv pearl-/farmingmixin fanns.
Den 1.21.11-mixin som skyddar läsning av player heads behålls.

Restocker/StorageList granskades: endast AutoTill/AutoDirt använde dem.
Inga gemensamma container-, hotbar- eller navigeringsfunktioner behövde flyttas.
BaritoneHelper, AutoHotbar, Containers, ChestStack, ContainerPacing, PlaceSearch,
SurveyRetry, WalkProgress, SnapshotSearch och craft/stash behölls. Seeds,
dirt och pearls är vanliga lagervaror. Senare avvecklade #153 även
Minecraft-skyltbaserade kategorier. Tiers, vanlig skyltartikel,
anvilnamn och signerad Manager-trafik ingår i dagens flöde.

13 pearl-testklasser (129 tester) togs bort tillsammans med hela målpaketet.
Övriga 496 klienttester behålls. 1.21.4: 496 körda, 0 fel/skips. Manager är
oförändrad och dess godkända baslinje gäller. Speltest av chest/barrel, drop-off,
Baritone-resa och shulkerpackning är EJ KÖRT; behöver Minecraft-testvärld.
1.21.11: också 496 körda, 0 fel/skips; båda klientbyggena passerade.

## W-007 / #8: Build Planner och schematics

Borttaget: PlanStore, PlannerRoutes, serverns planner-paket (NBT/schematic/
materiallistor), block-items-resurser och deras dev-generator. Klientens
PlannerTab/PlanModel/SchematicFiles, navigering, planner-/plannerOrder-fält,
route-guards och webbsidans planner-HTML/JS/CSS är borta. Delad orderhantering
kräver nu order/äganderätt enligt befintlig modell; plannerOrder ger ingen rätt.
PlanStore var minnesbaserad; ingen gammal planfil öppnas eller tas bort.
Fullständig isolering från gamla Qwerte-order genomförs i W-011.

Behållet: craft/Recipes/CraftPlanner, KitBuilder, OrderRunner, kit-/shulker-
preview, vanliga webb-/lokala order, namn/anvil, materialindex, ikoner och auth.
Endast de 17 exklusiva klienttesterna i PlanModelTest/SchematicFilesTest och
66 Manager-planner-testerna (inklusive deras NBT-fixtures) togs bort. Delade
order-/auth-/navigationstester uppdaterades och behölls. Ett nytt HTTP-test
bekräftar 404 för tidigare planner-routes och frånvaro av permissionsfält.

Verifierat: båda klienttargets bygger med 479 tester, 0 fel/skips. Manager
bygger med 124 tester, 0 fel, 1 tidigare känt ikon-skip (123 godkända).
JavaScript-syntax och no-undef-kontroll passerar; inloggningssidan initieras i
jsdom (canvas stöds inte där; den generella viewern tas bort i W-008).
Minecraft-interaktioner är fortfarande EJ KÖRDA.

## W-008 / #9: proxy, fjärrkonsol och generell viewer

Borttaget: serverns ViewerHub/ViewerRoutes, klientens MapTab/WMapView och
manager/map-paket, ViewerStream/StreamRestart, ConsoleTab/ConsoleLog,
MapTexture-adaptrar, webbens live canvas/HUD/chat, console/proxy-sidor och
behörighetskontroller. Ingen befintlig lagerkarta berodde på dessa world-viewers.
Containerpositioner, botstatus och aktivitet behålls för lager/logistik.

BotHub saknar kommandokö och konsolhistorik. Bot-/settings-scope är endast
`plugin`; `proxy` och `plugin:<id>` avvisas. Registryt filtrerar även bort
console/proxy/viewer-capabilities, så gamla hello-anrop inte återaktiverar dem.
Gamla console/viewer-routes ger 404 även för Owner. Behörighetsobjekt, presets
och båda UI-editorerna saknar proxy/console/consoleAllow/viewer-fält.

Signerad index/heartbeat/link/order-kommunikation, auth/CSRF, aktivitet, audit,
botstatus och egen agent-settings behålls. Delade JS-hjälpare för capabilities,
activity-navigation och lösenordsbekräftelse extraherades från proxysektionen.
Privacy-/streamertester och order-/leveranstester är kvar. 39 exklusiva klient-
viewer/console-tester och 30 exklusiva Manager-viewer/console-tester togs bort;
ett negativt HTTP-/registrytest tillkom. Delade tester uppdaterades för 404.

Verifierat: 440 tester/0 fel/skips per klienttarget; Manager 95/0 fel/1 känt
ikon-skip. `npm ci --prefix web-tests && npm test --prefix web-tests` kör
no-undef samt DOM-regression för login, stash/orders/bots/activity/settings,
eget plugin-scope och frånvaro av borttagna sidor/anrop. Samma test har körts
lokalt med låsta beroenden och kopplats till CI; actionlint passerar.
DOM-testet verifierar inte rendering i en riktig webbläsare eller Minecraft.

## W-010 / #11: död kod, resurser och dokumentation

Borttaget efter referensanalys: SseParser och dess nio exklusiva tester,
SmClient raw file-upload och viewer-streamhantering (fyra exklusiva tester),
console-allow-parser och ett test, oanvänd viewer-ID-cache, planner-session-
callback och upload-guard, console-outputformattering och konsolens exklusiva
audit-helper (ett test). JSON-transportens origin/cookie/CSRF/redirect-
regressioner och riktig konto-/orderaudit är kvar. Job-ID-generering behålls.

Dev/map-colors och Windows-serverstart togs bort. Fake-addon behåller signerad
index/heartbeat/order/delivery-testtrafik, men saknar nu proxy/console/viewer-
kommandon. Oanvända Java-importer och döda UI-kontroller/selectors togs bort.
README, Manager README/PROTOCOL och arkitekturdokumentet beskriver aktuell
lagerprodukt; föråldrad DASHBOARD-DESIGN togs bort. CHANGELOG/Git är historik,
inte dokumentation av aktiva WeSort-funktioner. Ursprung och GPL/OFL behålls.

Recipes, creative tabs, item-/shulkerikoner, font-/temaresurser, anvil, craft,
signs, Baritone och säkerhetsstöd behålls med motivering i ARCHITECTURE.md.
Inget Gradle-bibliotek var exklusivt för de borttagna funktionerna: Fabric,
Meteor och optional Baritone behövs av lagerklienten, Gson av Manager och
JUnit av regressionerna. Inga nya runtime-beroenden infördes.

Klientregressionen omfattar nu 426 tester per target. Manager har 94 tester
med ett känt ikon-skip. Webbens DOM-/referenstest och fake-addon --help körs
separat. Bygg-/speltestgränsen är oförändrad: inga Minecraft-interaktioner har
körts i denna miljö.

## W-011 / #12: identitet och tom installation

Alla aktiva paket/modulnamn/data/browseridentiteter är egna WeSort-namn.
Den gamla server-URL-fallbacken och dess två exklusiva tester är borttagna;
sex gemensamma isoleringstester tillkom i båda projekten. Ursprunglig Qwertze-
attribution och GPL/OFL ligger kvar. Ingen användardata migreras.
Se DATA_PATHS.md för fullständig sökvägsmatris och STAGE_0.md för slutresultat.
