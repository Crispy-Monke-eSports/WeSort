# Steg 4 – guildidentitet, ordermotor och behörigheter

## W-057 – guildidentitet och gemensamt lager

Manager har en guildprofil per WeSort-volym. Profilen har ett stabilt
ID, namn, beskrivning och revision. Owner kan ändra den i
**Settings → Guild profile**. Uppdateringen använder den aktuella
revisionen och audit anger vilken medlem som gjorde ändringen.

Guildens lagerkatalog omfattar alla verifierade källor. Rollens
stashscope styr positioner, stashnamn och källdetaljer. Endast två kontoroller
finns som produkt: Owner och Member (övriga presets är borttagna). Owner
administrerar konton och guildprofil.

En order bär guildens ID, medlemmens ID, ändamål och frivillig kommentar.
SELF är förvalt. Endast Owner får begära GUILD och ange en separat
mottagare (Member-preset har guildOrders=false; explicit beviljande via custom perms är möjligt men ingen produktroll). Mottagaren är en deklaration som följer ordern. Leverans till
en verifierad Minecraft-identitet hör till W-058 och W-027. Orderns
aktör och ID finns i audit. Kommentarer visas för beställaren och
orderansvariga med åtkomst till stashen.

| Prov                                                                  | Miljö                              | Status          |
| --------------------------------------------------------------------- | ---------------------------------- | --------------- |
| Två inloggade medlemmar läser samma guildprofil                       | Riktigt Manager-API                | Godkänt lokalt  |
| Owner redigerar profil, medlem nekas, gammal revision ger konflikt    | Riktigt Manager-API                | Godkänt lokalt  |
| Stashscope döljer källdetaljer och bevarar guildsaldot                | SQLite och Manager-API             | Godkänt lokalt  |
| Guildprofilen visas och kan redigeras från webben                     | DOM-test                           | Godkänt lokalt  |
| SELF, GUILD, mottagardeklaration och aktörsaudit                      | Manager-API                        | Godkänt lokalt  |
| Member med en riktig agent som fyller det gemensamma lagret           | Docker Manager och Minecraft 26.3  | Godkänt lokalt  |
| Docker-start, signerat protokoll och bevarad volym efter omstart      | Isolerad Docker Compose            | Godkänt lokalt  |
| En aktiv agent (ingen fleet/failover; 1–5 agenter borttaget per #139) | Manager, Docker och Minecraft 26.3 | Återstår (live) |

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
Ingen människa kan godkänna i drift: ett väntande godkännande äldre än 10
minuter avslås automatiskt med frigivning och audit, så kön aldrig parkerar.
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

`positions` styr fysisk insyn utöver stashscope. Owner har den; Member får
den endast vid behov (sekretess förblir förval). Äldre anpassade roller är
borttagna per bindande scope (#139) — ingen `officer`/`operator`-migrering
finns kvar. Medlemmar utan behörigheten har åtkomst till gemensamma saldon
och orderbara varianter. Manager skyddar fysiska snapshots, positioner, råa
agentmeddelanden och packningsallokeringar på servern.

Värdens administratör utfärdar agentnycklar via den ägarbegränsade
Unix-sockeln. Nycklar är namngivna platser (slot-ID är display-only, ingen
ägarbindning per PROTOCOL): rotation och spärr gäller direkt för
individuella nycklar.
Efter migrering stänger värdens administratör av den delade nyckeln
med `agent-require-scoped`. Link-vyn visar anslutningsstatus.

Medlemsfönstret och dess kommando registreras inte i produktionsklienten.
Beställningar går via Manager-webben. Agentens tekniska koppling,
Minecraft-chatt och lokala nödstopp finns kvar. Verkställighet
av den fysiska policyn hör till W-061 (kvoter är borttagna).

## W-030 – gemensam lagerportal

Browse visar guildens gemensamma saldo först. Sökningen filtrerar både
guildsaldot och de stashes som kontot får se. Välj en stash i resultatet
för att öppna den exakta artikelvarianten i inventariet. Orders visar
egna beställningar för medlemmar och behörig guildhistorik för
orderansvariga. Det vanliga medlemsflödet använder bara Manager.

Chromiumprovet loggar in två medlemmar i var sin webbläsarsession mot
Manager i Docker. Båda ser summan av två stashes, beställer från samma
stash och får ett gemensamt tillgängligt saldo som minskar med exakt
den reserverade mängden. Varje konto ser sin egen order. Webbläsaren
låter provet endast anropa Manager. Den signerade lagerfixturen ersätter
Minecraft i detta prov. W-027 och W-042 har separata 26.3-prov för fysisk
packning och staging.

Personkvoter är borttagna (se MC_26_3_QUOTAS.md); säkerhetspolicy och
verifierad UUID-hantering följer W-061–W-064. Generella teleportprofiler
finns inte (se MC_26_3_TRANSPORT.md).

## W-033 — en agent och flera stashes per konto [omsatt till bindande scope]

En agent har en egen nyckel för sitt Minecraft-konto, sin server och
en eller flera stashes. Samma konto har ett stabilt bot-ID oavsett aktiv
stash. [Historiskt: högst fem aktiva individuella agentnycklar och ett
flottprov med fyra signerade protokollklienter — borttaget per #139; en
aktiv agent gäller.]

En stash har en ägare. För överföring, avsluta eller stäm av arbetet,
rotera den gamla ägarens nyckel utan den berörda stashen och frigör
stashen i Manager. Nästa behöriga agent som observerar stashen tar över.
Individuellt autentiserade agenter kan inte automatiskt ta över en
annan agents stash efter frånvaro.

Manager sparar agentens identitet med anspråket och kan ge samma
anspråk tillbaka efter omförsök. Ett nytt anspråk i en annan stash och ett
samtidigt kitjobb blockeras för agenten som redan arbetar. Webben visar
senaste kontakt, faktiskt ansluten server och dimension för behöriga
roller, och de lagerförmågor som klienten annonserat.

Flottprovet är borttaget per bindande scope (en aktiv agent, #139).
Själva upphämtningen, transporten och mottagarkvittensen följer
W-036–W-039; generella teleportprofiler finns inte (W-061–W-062
avser grindar, inte transport).

## W-043 – orderrevision och driftstatus

**Orders → Order audit** söker på beställare, mottagare, vara,
stash, order-ID och tidsintervall. Owner och orderansvariga ser historik
inom sin stashbehörighet. Sökning på exakt order-ID visar stabila
paket- och operations-ID:n från den verifierade fysiska journalen.
Endast en fysiskt bekräftad staging visas som staged mängd.

Manager sparar reservation, godkännande, avslag, anspråk, avbokning,
statusövergång och manifestkvittens atomiskt med orderboken. Omförsök
av samma agentrapport lägger inte till en ny händelse. Avslutade
ordrar som rensas eller faller ur orderlistan sparas i ett arkiv med
de 100 senast uppdaterade avslutade ordrarna per stash. Befintliga
ordrar visar tillgängliga orderfält och fysisk journal. Händelser
från före införandet av orderhistoriken återskapas inte.

Exportera de filtrerade posterna som JSON från `GET /api/v1/audit/orders`
via en inloggad, behörig session. Sökningen returnerar högst 200
ordrar. Exakt order-ID returnerar högst 128 fysiska operationer. Auditen
innehåller ingen rå inventariesnapshot, slotallokering eller manifestposition.
[Streamer-läget är borttaget per PROTOCOL; ingen tvingad revisionsspärr finns.]
Ägarens separata konto-
och administrationslogg behåller de senaste 2 000 posterna.

**Bots** visar aktuell stock-, order- och agentsammanställning samt
larm om gamla eller onåbara källor, frånkopplade agenter och order som
behöver avstämmas. Agenten rapporterar också sitt lokala tillstånd för
död, osäker inventarie och platsbrist. Larmsidan använder signerade
observationer och visar aktiva förhållanden. [Kvoter och specialiserade
transportjobb är borttagna per #139.]
