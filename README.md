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

## Stödd Minecraft-version

**WeSort stöder endast Minecraft 26.3.** Klient-JAR, Fabric/Meteor/Baritone,
Gradle-bygget, CI, E2E och Manager-index/ikoner är riktade till exakt 26.3.
Minecraft 1.21.4 och 1.21.11 är historiska Qwerte-baslinjer, **inte**
stödda installationer eller byggen. De äldre käll-/versionsmålen är borttagna.

## Bygga och testa

Klienten kräver **Temurin Java 25.0.5+8**, Gradle **9.6.0** via
checksummalåst wrapper, Fabric Loom **1.17.21**, Fabric Loader **0.19.5**,
Meteor för **26.3 build 22**, samt vid behov Baritone API **1.4.0-26.3**.
Sätt JAVA_HOME till JDK 25. En enda klient-JAR byggs:

```sh
./gradlew --no-daemon clean build
python3 scripts/test-summary.py build
# Verklig 26.3-klient och Stash Window (kräver desktop eller Xvfb/Mesa):
python3 scripts/mc26/client-runtime.py
```

`-Pmc=26.3` accepteras för äldre automatiseringsscript, men ett annat
`-Pmc`-värde ger byggfel. `.java-version` avser nu klientens Java 25.

Manager är ett **separat Java 21-projekt** och körs i produktion bara i
Linux Docker. För utvecklings- och enhetstester, byt JAVA_HOME till JDK 21:

```sh
./gradlew --no-daemon -p stash-manager-server clean build
python3 scripts/test-summary.py stash-manager-server/build
npm ci --prefix web-tests
npm test --prefix web-tests
```

Minecraft-klientstart och Gradle/JUnit-bevis är olika testnivåer;
bygg/test ensamt verifierar inte fysisk inventory-hantering, Baritone eller
leverans. Se [26.3-portstatus](docs/MC_26_3_PORT.md),
[CI](docs/CI.md) och [historisk upstream-baslinje](docs/BASELINE.md).

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
