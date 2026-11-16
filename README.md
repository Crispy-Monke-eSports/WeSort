# WeSort – steg 0

WeSort är en lagerfokuserad vidareutveckling av Qwerte för ett gemensamt
guildlager. Detta repository befinner sig vid migrationsplanens **steg 0**;
den färdiga guildplattformen i [roadmap #1](https://github.com/Crispy-Monke-eSports/WeSort/issues/1)
är ännu inte implementerad. Ingen Minecraft 26.3-kompatibilitet påstås.

Återanvänd funktionalitet:

- Stash Sorter: chest/barrel-index, signkategorier, tiers, drop-off,
  stack consolidation, shulker-repack och bulkpackning.
- Kits: 27-slotmallar, metadata, shulkernamn, crafting och anvil.
- Stash Overlay och Stash Window: itempreview, lokal observation och lokala order.
- Separat Java Manager: inbyggd webb/API för stash, order, botstatus, aktivitet,
  individuell inloggning, rättigheter och skyddad audit.
- Valfritt Baritone för lagerrelaterade resor och placering.

Farming, ResetPearl, Build Planner/schematics, generell live world-viewer,
proxyadministration och fjärrkonsol har avlägsnats. Alla Minecraft-items,
inklusive dirt, seeds och pearls, är fortfarande vanliga lagervaror.

## Bygga och testa

Baslinjen är Minecraft **1.21.4 och 1.21.11**, Java **21.0.9+10** och
Gradle **9.2.0** via checksummalåst wrapper. Meteor/Loom-versionerna är låsta.
Varje Minecraft-version får en egen jar. Kör klienttargets sekventiellt:

```sh
./gradlew --no-daemon clean build -Pmc=1.21.4
python3 scripts/test-summary.py build
./gradlew --no-daemon clean build -Pmc=1.21.11
python3 scripts/test-summary.py build
./gradlew --no-daemon -p stash-manager-server clean build
python3 scripts/test-summary.py stash-manager-server/build
npm ci --prefix web-tests
npm test --prefix web-tests
```

Klientjar kräver Fabric Loader 0.18.2+, Meteor för samma Minecraft-version
och Java 21. Baritone API baseras på dekroms fork och är valfri vid runtime.
Vanlig kompilering/enhetstest startar inte Minecraft och bevisar inte fysiska
inventory clicks, Baritone eller leverans. Se [testbaslinjen](docs/BASELINE.md).

## Avgränsning och fortsatt arbete

Guildidentitet, SELF/GUILD-order, reservationer, kvoter (av som standard),
1–5 koordinerade agenter, verifierad transport/mottagning och Minecraft 26.3
införs i senare roadmapsteg. En HYBRID-agent ska till slut klara hela flödet.
Manager ska i produktion endast köras med Linux Docker och distribueras från
samma repo via GHCR; Docker/Compose/bootstrap/publicering ingår i steg 1.
Nuvarande Gradle-körning av Manager är endast för utveckling och test.

WeSort ska alltid börja med egna tomma data. Ingen import, fallback, kopiering,
läsning eller radering av tidigare Qwerte-data är tillåten. Namn och isolerade
lagringsvägar införs i W-011, sist i steg 0.

## Dokumentation och ursprung

- [Upstream och licensspår](docs/UPSTREAM.md)
- [Baslinje och verkliga testresultat](docs/BASELINE.md)
- [Keep/remove och beroenden](docs/CLEANUP_PLAN.md)
- [Borttagna och bevarade features](docs/REMOVED_FEATURES.md)
- [Arkitektur efter rensning](docs/ARCHITECTURE.md)
- [CI](docs/CI.md)
- [Manager och aktuellt protokoll](stash-manager-server/README.md)

Qwertze är originalförfattare. Git-historiken och originalets
[GNU GPL version 3](LICENSE) bevaras. Fontlicenser medföljer Manager.
