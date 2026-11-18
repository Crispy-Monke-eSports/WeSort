# Steg 4 – guildidentitet, ordermotor och behörigheter

## W-057 – guildidentitet och gemensamt lager

Manager har en guildprofil per WeSort-volym. Profilen har ett stabilt
ID, namn, beskrivning och revision. Owner kan ändra den i
**Settings → Guild profile**. Uppdateringen använder den aktuella
revisionen och audit anger vilken medlem som gjorde ändringen.

Guildens lagerkatalog omfattar alla verifierade källor. Rollens
stashscope styr positioner, stashnamn och källdetaljer. Member och
Officer är presets i den befintliga behörighetsmodellen. Viewer läser,
Member lägger order, Officer följer upp andras order och Operator kan
hantera agentinställningar och omskanning. Owner administrerar
konton och guildprofil.

En order bär guildens ID, medlemmens ID, ändamål och frivillig kommentar.
SELF är förvalt. Officer och Owner får begära GUILD och ange en separat
mottagare. Mottagaren är en deklaration som följer ordern. Leverans till
en verifierad Minecraft-identitet hör till W-058 och W-027. Orderns
aktör och ID finns i audit. Kommentarer visas för beställaren och
orderansvariga med åtkomst till stashen.

| Prov | Miljö | Status |
| --- | --- | --- |
| Två inloggade medlemmar läser samma guildprofil | Riktigt Manager-API | Godkänt lokalt |
| Owner redigerar profil, medlem nekas, gammal revision ger konflikt | Riktigt Manager-API | Godkänt lokalt |
| Stashscope döljer källdetaljer och bevarar guildsaldot | SQLite och Manager-API | Godkänt lokalt |
| Guildprofilen visas och kan redigeras från webben | DOM-test | Godkänt lokalt |
| SELF, GUILD, mottagardeklaration och aktörsaudit | Manager-API | Godkänt lokalt |
| Member, Officer och Viewer med en riktig agent som fyller det gemensamma lagret | Docker Manager och Minecraft 26.3 | Godkänt lokalt |
| Docker-start, signerat protokoll och bevarad volym efter omstart | Isolerad Docker Compose | Godkänt lokalt |
| Rollbaserad godkännande- och operatörsmatris för en till fem agenter | Manager, Docker och Minecraft 26.3 | Återstår |

Orderändamål, mottagarflöde och godkännande samordnas med W-058.
Frivilliga uttagskvoter hör till W-059.

## W-026 – atomisk orderlivscykel

Manager sparar order, reservation och idempotenskvittot i samma atomiska
orderbok. Webben skickar `Idempotency-Key` och behåller nyckeln vid omförsök
av samma utkast. En identisk upprepning ger tillbaka den första kvittensen
även efter omstart och arkivering. Ändrat innehåll med samma nyckel ger
konflikt. Signerade agentrapporter använder `reportId`. Om en äldre agent
utelämnar det härleder Manager en nyckel från rapportens innehåll.

Orderns fysiska tillstånd är separat från den äldre agentens statusfält.
En verifierad överföring till utlämningskistan ger PICKUP_READY.
En rapport om färdig packning utan motsvarande journalbevis ger
NEEDS_RECONCILIATION och behåller reservationen. Historiska slutrapporter
som saknar ett fysiskt slutleveransbevis läses också som avstämning.
Upphämtning, transport och kvittens hos mottagaren hör till W-027.

Det riktiga 26.3-provet skickade samma order med samma nyckel före och efter
packningen. RCON verifierade att exakt en observerad namngiven shulker hamnade
i utlämningskistan. Separata API- och persistensprov täcker samtidiga
anrop, omstart, arkiverade order och rapporter utan fysiskt bevis.

## W-058 – orderändamål, godkännande och mottagare

Owner ställer in direktbeställning, granskning av GUILD eller granskning av
alla order i **Settings → Guild profile**. Direktbeställning är förval.
En order som väntar på godkännande reserverar fysiskt lager men blir
tillgänglig för agenten först när en orderansvarig med åtkomst till
stashen godkänner den. Avslag från granskningsläget frigör reservationen.
Ordern sparar granskarens medlems-ID och tidpunkt. Audit loggar aktör,
åtgärd och order-ID. Unicodekommentaren visas som text för beställaren
och behöriga orderansvariga.

Owner kan koppla en bekräftad Minecraft-identitet till ett konto under
**Settings → Users**. SELF använder medlemmens aktiva koppling om
den finns. En behörig beställare kan ange en aktiv, bekräftad spelare
som mottagare i en GUILD-order. Utan mottagare pekar destinationen
på stashens utlämning. Båda ändamålen använder samma lager och kö.

Ordergränsen är 54 shulkers och kön behandlas i placeringsordning bland
order som kan tas. Före plock frigör avbokning reservationen. När agenten
har börjat är avbokningen en begäran som kräver agentens rapport.
Kvarvarande osäkerhet hanteras genom avstämning. Staging ger
`PICKUP_READY`. W-027 tar vid med transport, mottagarkvittens och
utfallsbokföring. Valfria kvoter och explicita undantag hör till W-059.

## W-027 – signerat agentprotokoll och stagingbevis

Manager kan skicka typade inställningsjobb till Fabric-agenten. Jobbet får
en lease och ett sekvensnummer. Agenten tillämpar ändringen på speltråden
och publicerar sitt nya värde. Manager sparar en auditpost per kvittens
och accepterar identiska upprepningar. Webbsessionen behöver bara
behörighet till agentens inställningar.

Manager kan pausa nya lagerorder och återuppta dem via samma signerade
inställningsflöde. En pågående order behåller sitt arbete och sin
reservation. Pausen visas i agentens heartbeat och återupptagning
använder de ordinarie lager- och inventariekontrollerna.

Stagingmanifestet binder en faktisk shulkerförflyttning från agentens
inventarie till en verifierad kistslot med journalrevision, checksumma
och stackfingeravtryck. Manager sparar beviset med orderkvittot och
visar paketantal. Delvis staging kräver avstämning.

Varje stagingbevis får ett stabilt `packageId`. Den packande agenten läser
manifestet genom sitt signerade API med aktuell session och kontroll-epoch.
Manager verifierar agent-ID, stashägare, server, stash och dimension.
Det signerade svaret innehåller fingerprint, paket-ID och journalrevision.
Stagingpositioner lagras internt. Agenten kvitterar manifestets hash och
Manager sparar tidpunkten atomiskt med ordern.

De tillagda kraven på `REGISTER_BOX`, `RENAME_FOR_ORDER` och
`RETURN_INTAKE` behöver verifierad anvilbehörighet enligt W-061 och den
frysta paketplanen i W-026/W-064. Transport, mottagarkvittens och dessa
tre fysiska jobb återstår i W-027.

### Returer i vanliga kistor

En retur kan registreras från en valfri kista som agenten har läst och
som har en aktuell verifierad shulkerobservation. Owner väljer att
behålla det aktuella namnet eller begära UUID-märkning i kistans
detaljvy. Valet sparas mot den fysiska slotrevisionen och det
verifierade 27-slotinnehållet. Ett byte av shulker i sloten kräver ett
nytt val. Registreringen använder det observerade lagret och räknar
inte in nya items från en manuell uppgift. UUID-begäran väntar på ett
separat behörigt, fysiskt kvitterat anviljobb.

## W-042 – åtkomst till historik och positioner

Orderlistan visar egna beställningar för medlem. Behörigheten
`ordersAll` ger åtkomst till andras order inom tillåtna stashes.
Stashens sammanfattning visar gemensamt antal öppna order och
filtrerar den aktiva orderns detaljer enligt samma medlemsregel.

`positions` styr fysisk insyn utöver stashscope. Owner och
Operator har den, med separat tilldelning till andra roller.
Äldre anpassade roller med agentinställningar får samma
positionsåtkomst vid migrering om fältet saknas. Medlemmar utan
behörigheten har åtkomst till gemensamma saldon och orderbara
varianter. Manager skyddar fysiska snapshots, positioner, råa
agentmeddelanden och packningsallokeringar på servern.

Värdens administratör utfärdar agentnycklar via den ägarbegränsade
Unix-sockeln. Manager binder varje nyckel till Minecraft-kontot, servern
och stashen. Rotation och spärr gäller direkt för individuella nycklar.
Efter migrering stänger värdens administratör av den delade nyckeln
med `agent-require-scoped`. Link-vyn visar anslutningsstatus.

Medlemsfönstret och dess kommando registreras inte i produktionsklienten.
Beställningar går via Manager-webben. Agentens tekniska koppling,
Minecraft-chatt och lokala nödstopp finns kvar. Kvoter och verkställighet
av den fysiska policyn hör till W-059 och W-061.
