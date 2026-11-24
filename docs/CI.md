# CI – Minecraft 26.3 (W-003)

**Supportpolicy:** Endast Minecraft **26.3** stöds och testas som klienttarget.
1.21.4/1.21.11 är historiska upstream-baslinjer, inte CI-mål. Minecraft-klienten
använder låst **Temurin 25.0.5+8**, Gradle och Fabric/Meteor/Baritone-versioner
från projektets versionsfiler. Manager bygger separat med **Java 25.0.5+8**,
React-storefronten byggs med **Bun 1** (`web/`: tsc, Vitest, Vite) före
Manager-jar:en, och webbtester använder **Node.js 26**.

## Workflow-arkitektur

CI är uppdelat i följande GitHub Actions-workflows:

- `.github/workflows/ci.yml`: en gemensam ingång för **varje PR**, push till
  `main`, nightly och manuell full verifiering. Kallas även av releaseflödet.
- `_verify.yml`: klient-, Manager- och webbtester, färsk installation och
  dataisolering.
- `_manager-ci-image.yml`: bygger en Manager-image en gång per körning med
  BuildKit/GHA-cache, parallellt med grundverifieringen.
- Browser/TLS/Docker-smoke körs parallellt med Minecraft E2E på den exporterade
  imagen, så smoke-testet ligger inte längre på den seriella kritiska vägen.
- `_mc26-e2e.yml`: verkliga 26.3-runtime- och E2E-harnessar, inklusive
  vanilla-server, Fabric-klient och Manager från samma aktuella revision.
- `manager-image.yml`: separat tagg/manuell release. Kräver hela CI-sviten,
  verifierar exakt versionsmärkt candidate-image, producerar SBOM och behåller
  explicit publiceringsgrind samt anonym pull-verifiering.

En godkänd JUnit-build räcker **inte** som fysisk E2E-verifiering.

## Fullständig scenariotäckning

Alla 34 tidigare matrix-scenarier finns kvar i tio isolerade shards:

- Journal (9): SELECT, PLACE, OPEN, TRANSFER, VERIFY, BREAK, PICKUP, STORE, DIRECT.
- Kits (10): pack, partial, repeat, repeat-version, variant, reserved, abort,
  offline, version, restart.
- Repack (15): mixed, partial, bulk, compact, no-tool, no-empty, full-stash,
  lag, abort, reserved, blocked, full-inventory, named, identical, rounds.

Scenarierna körs sekventiellt per shard, men varje harness skapar egen isolerad
Minecraft-värld, klient, Docker Compose-projekt och evidence-katalog. Enskilda
scenarier har 25 minuters timeout; fel hindrar inte senare scenarier från att
köras, och shard-jobbet misslyckas om något scenario misslyckas.

Sju E2E-jobb för assets/UI, client runtime, hierarchy, inventory, navigation,
sorting och physical stock körs **på varje PR**, utöver alla 34 scenarier och
Docker-smoke. World-harnessens unika återanslutningsprov före stash-tilldelning
körs i assets/UI-jobbet; dess övriga läs-, signatur- och oförändrad-inventory-prov
täcks där och i inventory/navigation. Standalone world-harness finns kvar
för lokal felsökning.

Toolchain, fryst Meteor utan dependency-cache och vanilla metadata körs på
PR vid relevanta ändringar enligt `scripts/ci/probe-scope.py`, samt alltid på
main, nightly, manuella körningar och release. Endast uttryckligen kända
applikationsändringar får hoppa över dessa tre prov; okänd diff kör allt.
Nightly och release upprepar varje journalfas två gånger på samma SHA.

Manager-enhetstester körs i det obligatoriska Manager-jobbet, inte en andra
gång i CI:s Docker-build. Standalone Docker-build och release-candidate
behåller tester som standard. Web-unit och statiska regressioner delar
samma byggda React-bundle i Manager-jobbet; Docker bygger fortfarande från
källan för att verifiera dess fristående paketering. Gemensamma Java-prov
behålls i klient- och Manager-bygget eftersom de har olika classpaths
(Minecraft/Fabric respektive Gson/SQLite). Client runtime bygger och startar
klienten men upprepar inte grundjobbets JUnit-svit.

Manager-imagen exporteras som zstd-komprimerad 7-dagars artifact och laddas
lokalt på runnern, där både image-ID och `org.opencontainers.image.revision`
verifieras. CI publicerar **inte** testimagen till GHCR.

E2E-runnern installerar bara saknade display-/ljudpaket. Ubuntu-arkivet används
först, med runnerns ordinarie spegel som fallback. Paketens release,
signaturkontroll och obligatoriska bibliotek ändras inte. Onödiga
översättningsindex och rekommenderade paket hämtas inte.

## Säkerhet och obligatoriska kontroller

- CI använder `contents: read`. Endast den separat skyddade release-publiceringen
  begär `packages: write`.
- PR-körningar med äldre commits kan avbrytas, men release och taggverifiering
  avbryts inte automatiskt av ny CI.
- Jobbet `ci-required` måste vara grönt för att hela CI ska räknas som godkänd.
  Om grundbygget misslyckas hoppas efterföljande E2E över för att inte slösa
  runner-tid, **men** `ci-required` rapporterar fel. Ett fel i vilket E2E-jobb som
  helst ger också röd status.
- **Inför merge:** uppdatera GitHub branch protection/rulesets till
  `ci-required` efter att kontrollen synts på draft-PR:en. Gamla statusnamn
  från fristående workflows finns inte längre.
- Jämför faktisk kötid, runner-minuter, cacheträffar och artifact-överföring
  med tidigare körningar innan någon prestandavinst påstås vara uppmätt.

## Java 25-migrationskontroller

Formatjobbet kör `python3 scripts/check-java-versions.py` för att kontrollera
att klientens och Managers versionsfiler, Gradle-targets, CI:s JDK-versioner
och Docker-taggar med låsta digests är synkroniserade. Vid en avsiktlig
Java-patchuppdatering ändras pinnarna tillsammans.

Manager-testprocesserna kör med `--illegal-native-access=deny` och explicit
JNI-behörighet. Det direkta JAR-isoleringsprovet använder också strikt
native access, men utan en separat tillåtelseflagga: manifestet i den
körbara JAR:en måste räcka för SQLite JDBC.

Manager-jobbet sparar `jdeps.txt` och `jdeprscan.txt` i
`stash-manager-server/build/evidence/`, inklusive verktygens exitstatus.
Granska träffar på interna API:er och borttagningsmarkerade API:er, även
i de inpackade biblioteken. Skanningarna är rapporterande eftersom fat
JAR:en även innehåller tredjepartsbibliotek med valfria beroenden.

Docker-smoke-rapporten innehåller en Java 25-baslinje för färsk uppstart,
API-latens, RSS, CPU, containerminne och uppskattad maximal heap.
Måtten är diagnostiska, inte pass/fail-gränser; jämför likvärdiga
körningar med samma workload och resursgräns innan prestandapåståenden.

## Lokal verifiering

```sh
# JAVA_HOME: Temurin 25.0.5+8
./gradlew --no-daemon clean build
python3 scripts/test-summary.py build
python3 scripts/mc26/client-runtime.py

# Samma JAVA_HOME: Temurin 25.0.5+8 för separat Manager
./gradlew --no-daemon -p stash-manager-server clean build
python3 scripts/test-summary.py stash-manager-server/build
npm ci --prefix web-tests && npm test --prefix web-tests
```

Root-`.java-version` och `stash-manager-server/.java-version` anger samma
Temurin 25.0.5+8; båda byggena och CI använder Java 25. Lokal verifiering ersätter inte GitHub Actions E2E-körningar.
De tidigare 1.21.x-resultaten finns i [historisk baslinje](BASELINE.md).
