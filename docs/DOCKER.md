# W-055: Linux Docker Manager

Produktion stöds på Linux amd64 med en Manager-container och en egen lokal
Docker-volym. Webb och API kommer från samma Java-process/image. Minecraft,
Discord och separat DB-tjänst behövs inte för att starta Manager. Funktioner
som guildkvoter, hållbara jobbleases och fysisk leverans införs senare; hela
volymen reserveras för även deras framtida data.

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

| Miljövariabel | Standard och betydelse |
|---|---|
| WESORT_IMAGE | Versionspinnad GHCR-tag i compose.yaml; kan överstyras med verifierad digest |
| WESORT_BIND | 127.0.0.1: värdens portbindning, inte access-bypass |
| WESORT_PORT | 8765: värdport; containerns HTTP-port är 8765 |
| WESORT_ALLOWED_HOSTS | Tom: extra DNS-hostnames nekas; ange en uttrycklig lista |
| WESORT_TRUSTED_PROXIES | Tom: inga forwardingheaders litas på |
| WESORT_SECURE_COOKIES | true i produktion, false i lokal dev; sätts av deployment, aldrig av en HTTP-header |
| WESORT_ICON_SOURCE | auto i produktion; game i offline-dev/test |

Inga API-nycklar eller lösenord stöds som Docker build-args eller Compose-env.
Ändra privata runtimeinställningar i den skyddade volymen om det behövs. Owner
kan hämta/regenerera agentnyckeln från den autentiserade Link-vyn. Montera inte
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
De bevarade stores skriver atomiskt innan kvittens. En full transaktions-/
fysisk journal införs enligt ADR-001/W-023/W-026, inte genom Docker-shutdown.

Hela `/var/lib/wesort` är persistent: config/keys, users, sessions, owners,
audit, indexes, orders, schema och framtida guild-/kit-/kvot-/journaldata.
Ikoncache är separat under samma egna rot. NFS/SMB och flera aktiva repliker
stöds inte. Backup/restore av en aktiv framtida DB kräver W-044; kopiera inte
bara en DB-fil medan WAL är aktiv.

## Verifiering

```sh
python3 scripts/docker-smoke.py --image wesort-manager:dev --report /tmp/wesort-docker-smoke.json
```

Testet skapar egna unika Compose-projekt/volymer och städar endast dessa.
Det bygger inte/pushar inte GHCR själv. Det verifierar non-root/read-only,
mode 700/600, riktig health/webb/API, säker bootstrap över Docker bridge,
CSRF/spoof/replay, extern signerad protokollagent, felaktig signatur/Bearer,
processlås, SIGTERM/omstart, persistent keys/Owner/sessions/index/order/audit,
samt en riktig TLS-proxy med betrodd exakt IP, Secure-cookie och Host/Origin.
TLS-testcontainern/certifikaten är testhjälpare och ingår inte i produktimagen.

Manager-build JDK: Temurin 21.0.9+10 och wrapper 9.6.0, med pinndigest. Runtime:
Temurin 21.0.13+9, pinndigest och lokalt verifierad. Rapport:
[W-055 Docker smoke](verification/W-055-docker-smoke.json). Samma tester körs
i CI utan package-push. De är HTTP-/DOM-/protokolltester, inte en verklig
Fabric-session eller fysisk Minecraft-leverans. Ingen sådan E2E påstås körd.

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
