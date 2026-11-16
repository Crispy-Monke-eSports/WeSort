# WeSort – migrationsarbete

> **NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**
>
> WeSort är ett oberoende communityprojekt och är inte godkänt av eller associerat med Mojang eller Microsoft. [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines).

WeSort är en lagerfokuserad vidareutveckling av Qwerte för ett gemensamt
guildlager. Minecraft-porten och testplattformen i steg 2 finns nu i koden;
den färdiga guildplattformen i [roadmap #1](https://github.com/Crispy-Monke-eSports/WeSort/issues/1)
är ännu inte implementerad. Verkliga 26.3-prov för inventory, avbrott,
Baritone, Docker-index och ikoner redovisas i [steg 2](docs/STAGE_2.md).

[Steg 3](docs/STAGE_3.md) påbörjas med [fysiska observationer och färskhet](docs/MC_26_3_STOCK.md)
(W-018). Övriga lager- och reservationssteg återstår.

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
Gradle **9.6.0** via checksummalåst wrapper. Meteor/Loom-versionerna är låsta.
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

26.3-target använder **Java 25.0.5+8**, Loom **1.17.21**, Loader **0.19.5**
och Meteor **26.3 build 22**. Med JAVA_HOME satt till denna JDK:

```sh
./gradlew --no-daemon build -Pmc=26.3
python3 scripts/mc26/client-runtime.py
```

Runtimeprovet startar riktig Minecraft och Stash Window i en egen gameDir.
Det kräver desktop eller Xvfb/Mesa och installerar Baritone API 1.4.0-26.3
i provprofilen. Bygg/klientstart bevisar inte fysisk lagerautomation.
Se [26.3-toolchain och portstatus](docs/MC_26_3_PORT.md).

Äldre klientjar kräver Fabric Loader 0.18.2+, Meteor för samma Minecraft-version
och Java 21. 26.3 använder den låsta Java 25-/Loader-/Meteor-matrisen ovan.
Baritone API baseras på dekroms fork och är valfri vid runtime.
Vanlig kompilering/enhetstest startar inte Minecraft och bevisar inte fysiska
inventory clicks, Baritone eller leverans. Se [testbaslinjen](docs/BASELINE.md).

## Avgränsning och fortsatt arbete

Guildidentitet, SELF/GUILD-order, reservationer, kvoter (av som standard),
1–5 koordinerade agenter och verifierad transport/mottagning
införs i senare roadmapsteg. En HYBRID-agent ska till slut klara hela flödet.
Manager körs i produktion endast med Linux Docker, med inbyggd webb/API,
non-root, egen persistent volym och säker Owner-bootstrap via lokal CLI.
Se [Docker-installation och verifiering](docs/DOCKER.md) och
[GHCR-publicering och uppgradering](docs/GHCR.md). Java-only körning används
endast för bygg/test.

WeSort ska alltid börja med egna tomma data. Ingen import, fallback, kopiering,
läsning eller radering av tidigare Qwerte-data är tillåten. Egna namn, tomma datakataloger och schema-/symlinkspärrar finns nu enligt
[DATA_PATHS.md](docs/DATA_PATHS.md).

## Dokumentation och ursprung

- [Upstream och licensspår](docs/UPSTREAM.md)
- [Baslinje och verkliga testresultat](docs/BASELINE.md)
- [Keep/remove och beroenden](docs/CLEANUP_PLAN.md)
- [Borttagna och bevarade features](docs/REMOVED_FEATURES.md)
- [Arkitektur efter rensning](docs/ARCHITECTURE.md)
- [Datakataloger och installationsisolering](docs/DATA_PATHS.md)
- [Docker-drift](docs/DOCKER.md)
- [GHCR-release och installation](docs/GHCR.md)
- [Steg 1 och kvarvarande avgränsningar](docs/STAGE_1.md)
- [CI](docs/CI.md)
- [Manager och aktuellt protokoll](stash-manager-server/README.md)

Qwertze är originalförfattare. Git-historiken och originalets
[GNU GPL version 3](LICENSE) bevaras. Fontlicenser medföljer Manager.
