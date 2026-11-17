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
