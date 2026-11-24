# WeSort

> **NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**
>
> WeSort är ett oberoende communityprojekt och är inte godkänt av eller associerat med Mojang eller Microsoft. [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines).

**Manager kräver `eula=true` innan start.** Läs [Mojangs EULA](https://www.minecraft.net/en-us/eula) och aktivera sedan `WESORT_EULA=true` i Docker Compose-miljön eller `eula=true` i Managers `config.properties`. Manager laddar själv ned Minecraft 26.3-klienten från Mojang till sin privata cache och kontrollerar filens checksumma. Det finns inget stöd för att ange en lokal Minecraft-installation eller klient-JAR. Utan godkännande, eller om verifierad nedladdning misslyckas, startar inte servern.

WeSort hanterar ett gemensamt guildlager med flera stashes på en konfigurerad
Minecraft 26.3-server. Java Manager tillhandahåller webb, API, individuella
medlemskonton och Owner-administration. En Fabric/Meteor/Baritone-agent skannar
och sorterar lagret i tiers och packar order i namngivna shulkerboxar.

Medlemmar kan beställa lagervaror, hela boxar, kit från 27-slotmallar och
craftbara föremål. Lagerobservationer, reservationer och orderstatus visas i
webben tillsammans med dynamiskt renderade Minecraft 26.3-ikoner.
Lagerstaging och verifierad mottagning är skilda steg. Se
[arkitekturen](docs/ARCHITECTURE.md) för gränserna i dagens implementation.

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
`-Pmc`-värde ger byggfel. Både klientens och Managers `.java-version` anger Temurin Java 25.0.5+8.

Manager är ett **separat Java 25-projekt** och körs i produktion bara i
Linux Docker. För utvecklings- och enhetstester, behåll JAVA_HOME på JDK 25:

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

Manager är ett separat Gradle-bygge. Det använder samma JDK 25:

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

Workflowen [`autofix.ci`](.github/workflows/autofix.yml) körs endast på pull
requests och kontrollerar Java, webb och Markdown med Spotless och Prettier.
Den inkluderar även Java-verktyget under `tools/mc26-toolchain/src/` via
rotprojektet. Genererad kod lämnas orörd.

- **Pull requests:** [autofix.ci](https://autofix.ci/) får
  formatteringsdiffen och skapar en rättningscommit i PR-grenen.
  Formatkontrollen blir röd på den ursprungliga committen och går igen
  på rättningscommitten. Workflowens PR-jobb har endast läsrättigheter.
- **`main`:** formateringskontrollen körs enbart i `ci.yml`, inte i
  autofix.ci. Eventuella avvikelser rättas via en PR, inte genom en direkt push
  till den skyddade grenen.

Den centrala [`ci.yml`](.github/workflows/ci.yml) börjar med jobbet
**Java, web and Markdown formatting gate**. Både compile/unit/Docker och
Minecraft E2E har beroenden till den kontrollen (`needs`), och `ci-required`
kräver att formatkontroll, verifiering och E2E samtliga lyckas. Om en PR
behöver rättas blir den första körningen röd; autofix.ci committar ändringen i
PR-grenen och nästa körning går vidare först när formateringen är godkänd.

## Installation och verifiering

Kör Java 25 Manager i Linux Docker med en egen lokal volym. Initiera Owner
med den lokala engångskoden, välj server och stashes i webben och provisionera
en namngiven agentnyckel för Java 25-klienten. Se [Docker-guiden](docs/DOCKER.md)
och [GHCR-publicering och uppgradering](docs/GHCR.md). Börja med en egen tom
WeSort-volym enligt [datakatalogerna](docs/DATA_PATHS.md).

Den [privata 26.3-harnessen](docs/MC_26_3_E2E.md) verifierar lokala scenarier
med riktig server och klient. För 6b6t gäller även den separata
[lobby- och liveverifieringen](docs/MC_26_3_LOBBY_PORTAL.md). Följ
[roadmapen](https://github.com/Crispy-Monke-eSports/WeSort/issues/1) för
återstående fysisk leveransverifiering.

## Dokumentation och ursprung

- [Upstream och licensspår](docs/UPSTREAM.md)
- [Baslinje och verkliga testresultat](docs/BASELINE.md)
- [Keep/remove och beroenden](docs/CLEANUP_PLAN.md)
- [Borttagna och bevarade features](docs/REMOVED_FEATURES.md)
- [Arkitektur efter rensning](docs/ARCHITECTURE.md)
- [Datakataloger och installationsisolering](docs/DATA_PATHS.md)
- [Docker-drift](docs/DOCKER.md)
- [GHCR-release och installation](docs/GHCR.md)
- [CI](docs/CI.md)
- [Manager och aktuellt protokoll](stash-manager-server/README.md)

Qwertze är originalförfattare. Git-historiken och originalets
[GNU GPL version 3](LICENSE) bevaras. Fontlicenser medföljer Manager.
