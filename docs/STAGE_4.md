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
