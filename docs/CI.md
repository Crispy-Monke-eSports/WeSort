# CI – Minecraft 26.3 (W-003)

**Supportpolicy:** Endast Minecraft **26.3** stöds och testas som klienttarget.
1.21.4/1.21.11 är historiska upstream-baslinjer, inte CI-mål. Minecraft-klienten
använder låst **Temurin 25.0.5+8**, Gradle och Fabric/Meteor/Baritone-versioner
från projektets versionsfiler. Manager bygger separat med **Java 21** och webbtester
använder **Node.js 22**.

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

## Lokal verifiering

```sh
# JAVA_HOME: Temurin 25.0.5+8
./gradlew --no-daemon clean build
python3 scripts/test-summary.py build
python3 scripts/mc26/client-runtime.py

# JAVA_HOME: Temurin 21 för separat Manager
./gradlew --no-daemon -p stash-manager-server clean build
python3 scripts/test-summary.py stash-manager-server/build
npm ci --prefix web-tests && npm test --prefix web-tests
```

Root-`.java-version` motsvarar Minecraft-klientens Java 25; Manager-workflowet
väljer Java 21. Lokal verifiering ersätter inte GitHub Actions E2E-körningar.
De tidigare 1.21.x-resultaten finns i [historisk baslinje](BASELINE.md).
