# CI – Minecraft 26.3 (W-003)

**Supportpolicy:** Endast Minecraft **26.3** stöds och testas som klienttarget.
1.21.4/1.21.11 är historiska upstream-baslinjer, inte CI-mål. Minecraft-klienten
använder låst **Temurin 25.0.5+8**, Gradle och Fabric/Meteor/Baritone-versioner
från projektets versionsfiler. Manager bygger separat med **Java 25.0.5+8**,
React-storefronten byggs med **Bun 1** (`web/`: tsc, Vitest, Vite) före
Manager-jar:en, och webbtester använder **Node.js 22**.

## Workflow-arkitektur

Fyra GitHub Actions-workflows ersätter de tidigare 14:

- `.github/workflows/ci.yml`: en gemensam ingång för **varje PR**, push till
  `main`, nightly och manuell full verifiering. Kallas även av releaseflödet.
- `_verify.yml`: klient-, Manager- och webbtester, färsk installation och
  dataisolering, samt browser/TLS/Docker-smoke. Bygger en Manager-image en gång
  per körning, med BuildKit/GHA-cache.
- `_mc26-e2e.yml`: **alla** verkliga 26.3-runtime- och E2E-harnessar, inklusive
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

Nio separata E2E-jobb för assets/UI, client runtime, hierarchy, inventory,
navigation, sorting, physical stock, toolchain och world körs också **på varje PR**.
Inga path-filter hoppar över tunga tester på PR.

Manager-imagen exporteras som zstd-komprimerad 7-dagars artifact och laddas
lokalt på runnern, där både image-ID och `org.opencontainers.image.revision`
verifieras. CI publicerar **inte** testimagen till GHCR.

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
