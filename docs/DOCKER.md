# W-055: Linux Docker Manager

Produktion stöds på Linux amd64 med en Manager-container och en egen lokal
Docker-volym. Webb och API kommer från samma Java-process/image. Minecraft,
en agent och separat DB-tjänst behövs inte för att starta Manager.

## Lokalt verifierad utvecklingsinstallation

```sh
docker compose -f compose.dev.yaml up --build -d
docker compose -f compose.dev.yaml exec -T manager /opt/wesort/entrypoint bootstrap
```

Öppna `http://localhost:8765`, klistra in engångskoden och välj ett nytt Owner-
lösenord. Kommandot skriver koden endast till den begärda exec-sessionens
stdout. Den visas aldrig i vanliga containerloggar. Koden är 256-bit, gäller
högst tio minuter och förvaras endast som digest i serverminnet. En ny kod
invaliderar den tidigare; omstart invaliderar den; efter lyckad setup kan ingen
ny kod utfärdas eller återanvändas. Kodfältet skickas i POST-body, inte URL.

Detta definierar tilliten som rätt att köra `docker compose exec` som Manager-
användaren. HTTP-peer som ser ut som loopback eller spoofade forwardingheaders
kan inte utfärda en kod. Setup fungerar genom Docker bridge och TLS-ingress;
inget `allowRemote=true` eller avstängt Host/Origin-/CSRF-skydd behövs.

Ändra värdport med `WESORT_PORT=18765`. Utvecklingsfilen exponerar bara loopback
på Linux-värden och använder HTTP-cookies för det lokala testflödet. Dela inte
ut en sådan HTTP-installation som produktion. Stoppa med `down`; använd inte
`down -v` om datan ska sparas.

## Produktionsfil

`compose.yaml` använder versionspinnad GHCR-image utan lokal build.
[GHCR-processen](GHCR.md) beskriver publicering, anonym pull/start,
installationsunderlag, uppgradering och rollback. Den gröna
publiceringskörningens rapport anger testad tag och verklig digest.

```sh
# Sätt WESORT_IMAGE till verifierad ghcr.io/...@sha256:... för digestpinning.
docker compose pull
docker compose up -d
docker compose exec -T manager /opt/wesort/entrypoint bootstrap
```

Använd en TLS reverse proxy på Linux-värden eller en separat befintlig ingress
som terminerar HTTPS på 443. Standardportbindningen är `127.0.0.1:8765`.
Konfigurera det exakta publika DNS-namnet i `WESORT_ALLOWED_HOSTS`. Låt proxyn
skriva en korrekt Host, Origin och X-Forwarded-For; skrubba inkommande
forwardingheaders och skriv över dem med dess verifierade socketpeer. Endast
exakta betrodda proxy-IP:n får anges i `WESORT_TRUSTED_PROXIES`.

| Miljövariabel          | Standard och betydelse                                                                                                                                                |
| ---------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| WESORT_IMAGE           | Versionspinnad GHCR-tag i compose.yaml; kan överstyras med verifierad digest                                                                                          |
| WESORT_BIND            | 127.0.0.1: värdens portbindning, inte access-bypass                                                                                                                   |
| WESORT_PORT            | 8765: värdport; containerns HTTP-port är 8765                                                                                                                         |
| WESORT_ALLOWED_HOSTS   | Tom: extra DNS-hostnames nekas; ange en uttrycklig lista                                                                                                              |
| WESORT_TRUSTED_PROXIES | Tom: inga forwardingheaders litas på                                                                                                                                  |
| WESORT_SECURE_COOKIES  | true i produktion, false i lokal dev; sätts av deployment, aldrig av en HTTP-header                                                                                   |
| WESORT_EULA            | Tom/ej satt (nekar start), eller **true** efter att administratören accepterat Minecraft EULA. Kan även sättas som `eula=true` i Managers privata `config.properties` |

### Obligatorisk Minecraft-EULA och resurshämtning

**Manager startar inte utan att `eula=true` har aktiverats.** Läs
[Mojangs EULA](https://www.minecraft.net/en-us/eula) innan installationen.
Genom att sätta `WESORT_EULA=true` i miljön där `docker compose` körs
(till exempel i en lokalt hanterad `.env`-fil) eller `eula=true` i
Managers `/var/lib/wesort/config.properties` bekräftar administratören
godkännande av EULA, innehav av licens för Minecraft Java Edition och
att klienten och dess resurser inte kommer att distribueras vidare.

På första godkända start hämtar Manager **själv** den officiella,
versionslåsta Minecraft 26.3-klient-JAR:en från Mojangs objektserver,
kontrollerar exakt storlek och SHA-1 samt sparar den i den skyddade
WeSort-volymen. Nedladdningen görs **innan HTTP-servern kan öppnas**.
Om hämtning eller verifiering misslyckas avbryts starten. På kommande
starter återanvänds endast samma privata och på nytt verifierade cache.

Manager **stöder inte** `minecraftJar`, sökning i launcher-kataloger,
lokala spelinstallationer eller `acceptMinecraftDownload`; äldre sådana
konfigurationsnycklar är utan verkan och ger **inte** EULA-godkännande.
Enbart `eula=true` / `WESORT_EULA=true` räcker för att aktivera
nedladdningen; någon separat resursinställning behövs inte.

JAR-filen ingår inte i Git, Docker-imagen eller GHCR-releaser. Redistribuera
inte nedladdade Minecraft-resurser eller kopiera dem till distribuerade
säkerhetskopior; uteslut `icons/sources/26.3` och renderade ikoncachar
från sådana arkiv. Detta bygger på BlueMaps explicita samtyckesprincip
men använder det enklare startvillkoret `eula=true`.

Inga API-nycklar eller lösenord stöds som Docker build-args eller Compose-env.
Ändra privata runtimeinställningar i den skyddade volymen om det behövs.
Efter Owner-setup väljer du `6b6t.org` (med port om agenten rapporterar en)
i **Settings → Server** och definierar stashes. Skapa en namngiven agentnyckel
i **Settings → API keys** eller med lokal CLI:

```sh
docker compose exec -T manager /opt/wesort/entrypoint agent-issue wesort-agent
docker compose exec -T manager /opt/wesort/entrypoint agent-require-scoped
```

Ange Manager-adress, port och hela nyckeln i WeSort Agent. Spara nyckeln i
ett skyddat medium. Manager tillåter en aktiv namngiven nyckelplats.
Vid byte, återkalla först den gamla med `agent-revoke <nyckel-id>`, utfärda
den nya och låt klienten göra ny signerad hello. Den andra kommandoraden
kräver namngivna nycklar i nuvarande version.

### Offline-mode, Velocity och Geyser/Floodgate

WeSort Agent är en **Minecraft Java/Fabric-klientmod**. Den kan användas av
en Java-klient på en server med `online-mode=false` eller via Velocity/
BungeeCord och liknande proxyer. Ingen Mojang-session, premium-UUID eller
Minecraft-användarnamn används som behörighetskontroll: **Managers aktiva,
signerade API-nyckel är autentiseringen**. Offline-mode-UUID, forwarded UUID
och olika Java-/Bedrock-namn är enbart sessions-/journal- och visningsmetadata.

Floodgate kan prefixa namn med `.` eller andra tecken, och Bedrock-namn kan
innehålla mellanslag/Unicode. Manager accepterar sådana rapporterade namn som
**visningstext** (max 64 tecken, utan kontroll-/formattecken) och kräver inte
att ett namn eller UUID matchar någon API-nyckel. Avsaknad eller felaktig
visningstext ska inte blockera en giltig signerad nyckel. Fysiska manifest
behåller ett UUID-format för **den aktuella exekveringskontexten**, inte som
en ägarkontroll mot Mojang eller nyckelregistret. Samtidiga kontrollsessioner
med exakt samma nyckelplats är fortfarande separata från autentiseringen:
Manager låter dem inte överta varandras pågående fysiska jobb.

**Viktigt för proxyservrar:** Manager ska i Settings → Server konfigureras
med exakt det server-ID som Java-agenten rapporterar (normalt den adress,
inklusive eventuell port, som klienten anslöt till). Managers kontroller av
**fel server, odefinierat lager och fysisk journal** försvagas inte för att
en installation kör offline-mode. Konfigurera också Velocity/Papers
player-information-forwarding och dess hemlighet korrekt och hindra
direktanslutning till bakservrar. WeSort ersätter inte proxyns egen
autentisering och förhindrar inte namnspoofing på en oskyddad cracked server.

**Geyser-avgränsning:** Bedrock-klienter kan ansluta till servern via
Geyser/Floodgate men kan inte köra en Fabric/Meteor-mod direkt. Själva WeSort
Agent måste köras på en kompatibel Java-klient. Kombinationer med
Geyser/Floodgate verifieras genom protokolltester för deras namn/UUID-format;
ett fullständigt riktigt Bedrock→Geyser→Velocity→Java E2E-flöde ingår inte
i nuvarande CI.

Montera inte
Qwerte-data, hemkataloger eller Docker-socketen i Manager. Miljöinställningarna
för deployment överstyr motsvarande icke-hemliga configvärden vid start.

LAN-policyn kontrollerar socketpeer och Host, aldrig en klientvald XFF. Bakom
en reverse proxy är socketpeer proxyn; offentlig klienttrafik skyddas därför
av proxyexponering, TLS, signing/auth och Origin-kontroller, inte av en
förhoppning att XFF ska fungera som brandvägg. Endast betrodd XFF används för
rate limit/audit. Skapa inte en tillitsregel för hela Docker-subnetet.

## Volym och processlivscykel

UID/GID är 10001:10001. Ny named volume får mode 0700; privata filer får 0600
(genom umask 0077 och atomiska tempfiler). Imagen och rootfs är skrivskyddade;
`/tmp` är en begränsad tmpfs. Inga capabilities krävs och nya privilegier nekas.
Compose använder restart unless-stopped, init och 30 sekunders stop-grace.

`.wesort-installation` valideras före I/O mot config/stores. `.manager.lock`
hålls under hela processen, inklusive shutdown; andra Manager-processer mot
samma volym nekas före tempstädning eller configändring. `.control.sock` är
owner-only (0600) och rensas under det exklusiva låset vid start/ordnat stopp.
Health-kommandot är läsande och initierar aldrig en tom/okänd datarot.

`/health/live` och `/health/ready` innehåller endast status, inga paths,
credentials eller lagersiffror. Docker-proben kontrollerar privat runtime-state
via Unix-socket och ett verkligt loopbackanrop till HTTP-readiness. Readiness
ges efter data/auth, HTTP och lokal kontrollkanal startats. SIGTERM släcker
readiness, stänger intag/kontrollkanal, avslutar trådar och släpper låset sist.
Agentstatus och lagerfärskhet läses separat i Manager.
De bevarade stores skriver atomiskt innan kvittens. En full transaktions-/
fysisk journal införs enligt ADR-001/W-023/W-026, inte genom Docker-shutdown.

Hela `/var/lib/wesort` är persistent: config/keys, konton, sessioner, audit, index, order och SQLite.
Ikoncache är separat under samma egna rot. NFS/SMB och flera aktiva repliker
stöds inte. Stoppa Manager före manuell volymkopiering och kopiera hela volymen
inklusive SQLite:s tillhörande filer. Se [återstartsguiden](MC_26_3_BACKUP.md).

## Verifiering

```sh
python3 scripts/docker-smoke.py --image wesort-manager:dev --report /tmp/wesort-docker-smoke.json
```

För att även prova medlemsflödet i Chromium mot samma container:

```sh
npm ci --prefix web-tests
npm exec --prefix web-tests -- playwright install --with-deps chromium
python3 scripts/docker-smoke.py --compose-file compose.yaml --image wesort-manager:dev --browser --report /tmp/wesort-docker-browser.json
```

Testet skapar egna unika Compose-projekt/volymer och städar endast dessa.
Det bygger inte/pushar inte GHCR själv. Det verifierar non-root/read-only,
mode 700/600, riktig health/webb/API, säker bootstrap över Docker bridge,
CSRF, replay, signerad agentnärvaro, färsk stock, riktiga 26.3-ikoner och
nekade dubbla agentsessioner, fel server och felaktiga credentials. Det
verifierar processlås, SIGTERM, omstart och att den aktuella agentnyckeln,
Owner, sessioner, index, order och audit finns kvar efter omstart,
samt en riktig TLS-proxy med betrodd exakt IP, Secure-cookie och Host/Origin.
TLS-testcontainern/certifikaten är testhjälpare och ingår inte i produktimagen.

Rapportens `performance_baseline` registrerar uppstart till Docker-healthy,
20 läsningar av stash-API:t (median, p95, max), Managers RSS, containerns
CPU och minne samt maximal heap uppskattad från en separat JVM med samma
minnesgräns och MaxRAMPercentage. Det är inte en mätning av live-heap.
Mätningen görs endast mot testets färska syntetiska installation, med
oförändrad containerhärdning; inga prestandatrösklar eller äldre images.

Aktuell Manager-build och runtime använder Temurin 25.0.5+8 med låsta
Docker-digests. Startkommandot tillåter JNI för SQLite JDBC i den omodulära
fat JAR:en. Historisk W-055-verifiering kördes med Java 21. Rapport:
[W-055 Docker smoke](verification/W-055-docker-smoke.json). Samma tester körs
i CI utan package-push. Chromiumprovet använder samma Manager och en
signerad lagerfixtur. Fabric-sessioner och fysisk Minecraft-leverans
verifieras separat i 26.3-proven.

Lokalt slutresultat W-055: Manager 104 tester, 0 fel, 1 känt ikon-skip;
historiska klienttester för 1.21.4 och 1.21.11: 431 vardera, 0 fel/skips (ej längre stödda). Webbens DOM-/
referensregression, Java-process-isolering, actionlint och Compose config
passerar. Kända rendering-/Minecraft-begränsningar är samma som i steg 0.

## Färskhetsgräns och SQLite (W-018)

`WESORT_STOCK_MAX_AGE_SECONDS=300` styr hur länge en fysisk observation kan ge
nya order (1–86400 sekunder). Ändrad gräns kräver omstart; gamla källor blir då
osäkra tills nya serveravläsningar har gjorts. `guild.sqlite` och dess WAL/SHM
finns på samma lokala volym som övrig WeSort-data. Säkerhetskopiera hela volymen
med stoppad Manager. Se [fysiska observationer](MC_26_3_STOCK.md).
