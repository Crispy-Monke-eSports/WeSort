# WeSort Manager

**Manager accepterar endast Minecraft 26.3-index och 26.3-assets.** Manager startar inte förrän `eula=true` har satts i sin `config.properties` eller `WESORT_EULA=true` i driftsmiljön. Läs [Mojangs EULA](https://www.minecraft.net/en-us/eula) före godkännande. Manager hämtar och SHA-1-verifierar den officiella 26.3-klienten automatiskt innan HTTP-servern startar; ingen lokal spelinstallation eller användarangiven JAR stöds. Både den separata Manager-runtime och klienten använder Java 25. Tidigare explicita `mcVersion`-värden avvisas, medan ett index utan fältet behåller API-v1-formatet och tolkas som 26.3.

Ett separat Java 25-projekt med inbyggd webb och HttpServer. Produktion stöds
endast som Linux amd64 Docker-container. Se [installation, Owner-bootstrap och
smoketester](../docs/DOCKER.md). Java-only nedan är för bygg/test.

Bygg/testa från repositoryroten:

```sh
./gradlew --no-daemon -p stash-manager-server clean build
python3 scripts/test-summary.py stash-manager-server/build
```

För isolerade utvecklingstester, använd en ny tom katalog med `--data`.
Använd aldrig en Qwerte-katalog eller tidigare `stash-manager-data`.
Standard är `wesort-manager-data`; en explicit --data måste vara tom eller
redan märkt för WeSort Manager. Symlänkar och okända scheman nekas före I/O.
Se [datakatalogerna](../docs/DATA_PATHS.md).

Webben har stash/index, orders/cart/shulkerpreview, bots/status, activity,
egen agent-settings, konton och audit. Auth, sessions, CSRF, signing, host-/LAN-
kontroll och sekretess för begränsade användare är kvar. Reverse proxy-header
litas endast på när dess avsändare finns i `trustedProxies`.

Farming, schematics/planner, world/HUD/chat-viewer, proxyadministration och
fjärrkonsol stöds inte. Se [aktuella API-ytan](PROTOCOL.md) och
[borttagningarna](../docs/REMOVED_FEATURES.md). Observerad stock och befintlig
orderstatus ska inte beskrivas som verifierad slutleverans eller reservation.

API-nycklar, sessions, lösenordshashar och produktionsdata får aldrig ingå i
byggevidence eller distribution. Se [upstream/licenser](../docs/UPSTREAM.md).

## Driftlarm

Övervaka `GET /health` och larma på HTTP 503. Manager kontrollerar den konfigurerade servern var 15:e sekund. `oldestIndexAgeMs` är åldern vid mättillfället för den äldsta kända stashens genererade index. Saknat index, okänd eller framtida genereringstid och ålder från `WESORT_STOCK_MAX_AGE_SECONDS` (standard 300 s) ger larm. `stuckOrders` räknar order i recovery direkt och övriga påbörjade order efter tio minuter utan uppdatering. Ändringar i larmvärdena skrivs till Manager-loggen. Ett mätvärde äldre än en minut ger också HTTP 503. Docker använder fortsatt `/health/ready` för att kontrollera att Manager kan ta emot trafik.

## Webbsessioner

Sessioner löper ut efter sju dygn från inloggning eller efter tolv timmar utan autentiserad trafik. Även en öppen dashboards bakgrundsanrop räknas som aktivitet. Sätt `WESORT_SESSION_IDLE_HOURS` till ett heltal mellan 1 och 168 för att ändra inaktivitetsgränsen. Ogiltiga värden stoppar uppstarten. Vid uppgradering gäller den tidigaste av befintlig sluttid och de nya gränserna. Efter omstart kan inaktivitetsgränsen infalla upp till tio minuter tidigare eftersom senaste aktivitet sparas periodiskt.

## Ikoncache

Manager begränsar renderade ikoner och cachemarkörer till 16 MiB totalt, med minst 4 KiB räknat per fil. Vid uppstart och före nya skrivningar rensas de minst nyligen använda filerna tills cachebudgeten räcker. Antalet rensade filer loggas. Den separata, verifierade 26.3-klienten i `icons/sources` har en fast storlek på 41 483 720 byte.
