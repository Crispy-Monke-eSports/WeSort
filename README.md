# WeSort – migrationsarbete

> **NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**
>
> WeSort är ett oberoende communityprojekt och är inte godkänt av eller associerat med Mojang eller Microsoft. [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines).

**Manager kräver `eula=true` innan start.** Läs [Mojangs EULA](https://www.minecraft.net/en-us/eula) och aktivera sedan `WESORT_EULA=true` i Docker Compose-miljön eller `eula=true` i Managers `config.properties`. Manager laddar själv ned Minecraft 26.3-klienten från Mojang till sin privata cache och kontrollerar filens checksumma. Det finns inget stöd för att ange en lokal Minecraft-installation eller klient-JAR. Utan godkännande, eller om verifierad nedladdning misslyckas, startar inte servern.

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

## Meteor: WeSort Agent

I Meteor visas **endast en WeSort-modul: WeSort Agent**. Den enda inställningen öppnar en
anslutningsdialog med **Manager-adress, port och maskerad API-nyckel**. Alternativt kan
`.wesort-connect` öppna samma dialog. Den gamla `StashSorter`-modulen är inte
registrerad i Meteors modullista: dess Minecraft-exekvering körs internt av agenten.

Konfigurera sortering, scanning, orderintag, crafting, shulker-repack och övriga
agentinställningar i **WeSortManager → Agents → Settings**. Ändringar skickas som
signerade kommandon till agenten och kontrolleras mot förväntat värde; en godkänd
uppdatering av sorteringsmotorn sparas i agentens dolda tillstånd för omstart.
Serverdefinierade lagerområden och platser kommer fortfarande via verifierade
Manager-direktiv. Fysisk sortering kräver ett giltigt Manager-utfärdat tillstånd;
den interna motorn får inte kringgå inventarie- och journalspärrar.

## Bygga och testa

Klienten kräver **Temurin Java 25.0.5+8**, Gradle **9.6.0** via
checksummalåst wrapper, Fabric Loom **1.17.21**, Fabric Loader **0.19.5**,
Meteor för **26.3 build 22**, samt vid behov Baritone API **1.4.0-26.3**.
Sätt JAVA_HOME till JDK 25. En enda klient-JAR byggs:

```sh
./gradlew --no-daemon clean build
python3 scripts/test-summary.py build
# Verklig 26.3-klient och Agent-runtime (kräver desktop eller Xvfb/Mesa):
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

## Formatering av Java i VS Code

Java-koden använder Spotless 8.11.0 med Palantir Java Format 2.103.0
(4 mellanslag och normalt 120 teckens radbredd), i linje med
`.editorconfig`. De delade Java-filerna i `common/` formateras via
rotprojektet. Kod som genereras under `build/generated/` formateras inte.

Öppna repositoryroten i VS Code och installera de rekommenderade tilläggen
**Gradle for Java** och **Spotless for Gradle and Maven**. Med de incheckade
`.vscode/settings.json`-inställningarna formateras Java-kod när du sparar
(`Ctrl+S`). VS Codes vanliga Java-formatter är avaktiverad vid sparning
för att undvika motstridiga formatregler.

För manuell formatering eller kontroll (med klientens JDK 25):

```sh
./gradlew spotlessApply
./gradlew spotlessCheck
```

Manager är ett separat Gradle-bygge. Det använder JDK 21:

```sh
./gradlew -p stash-manager-server spotlessApply
./gradlew -p stash-manager-server spotlessCheck
```

**Observera:** `spotlessCheck` kan rapportera befintliga formatavvikelser.
Spotless är tills vidare frikopplat från Gradles vanliga `check`/`build`
för att inte blockera CI innan en separat formatmigrering har genomförts.
Kör inte `spotlessApply` över hela projektet i en vanlig funktions-PR
utan att granska diffen först.

### Automatisk formatering i GitHub Actions

Workflowen [`autofix.ci`](.github/workflows/autofix.yml) kör
`spotlessApply` följt av `spotlessCheck` för båda Gradle-byggena.
Den inkluderar även Java-verktyget under `tools/mc26-toolchain/src/` via
rotprojektet. Genererad kod lämnas orörd.

- **Pull requests:** [autofix.ci](https://autofix.ci/) får
  formatteringsdiffen och skapar en rättningscommit i PR-grenen.
  Formatkontrollen blir röd på den ursprungliga committen och går igen
  på rättningscommitten. Workflowens PR-jobb har endast läsrättigheter.
- **`main`:** vid varje push (eller manuell `workflow_dispatch`) formateras
  Java-koden. Eventuella avvikelser skapar automatiskt en separat commit
  direkt på `main` via GitHub Actions `GITHUB_TOKEN`, utan force-push.
  Jobbet har skrivbehörighet endast vid körning på `main`. Efter rättningen
  startas `ci.yml` genom `workflow_dispatch` så att verifieringen körs på
  den faktiskt formaterade committen.

En push med `GITHUB_TOKEN` utlöser inte vanliga `push`-workflows. Därför
använder workflowen ett explicit `workflow_dispatch` efter auto-commit;
detta kräver `actions: write` i `main`-jobbet. `spotlessCheck` körs också
**innan** committen skapas. Om `main` skyddas med branch rules som nekar
GitHub Actions att pusha kommer auto-commit att misslyckas tills rätt
behörighet ges. Fullständig massformatering kan inträffa första gången
workflowen körs efter merge.

Den centrala [`ci.yml`](.github/workflows/ci.yml) börjar med jobbet
**Java formatting gate**. Både compile/unit/Docker och Minecraft E2E har
beroenden till den kontrollen (`needs`), och `ci-required` kräver att
formatkontroll, verifiering och E2E samtliga lyckas. Om en PR behöver
rättas blir den första körningen röd; autofix.ci committar ändringen i
PR-grenen och nästa körning går vidare först när all Java är formaterad.

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
