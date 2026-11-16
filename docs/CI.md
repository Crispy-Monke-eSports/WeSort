# CI – enbart Minecraft 26.3 (W-003)

**Aktuell supportpolicy:** Endast Minecraft **26.3** stöds och testas som
klienttarget. 1.21.4/1.21.11 är historiska upstream-baslinjer, inte CI-mål.

`.github/workflows/build.yml` bygger exakt en Fabric/Meteor-klient med
**Temurin 25.0.5+8**, Gradle 9.6.0 och versionslåsta 26.3-dependencies.
Samma workflow bygger separat Manager med **Java 21**, kör webb-/Node 22-
tester, installation/isolation och lokal Docker smoke. Enhetsresultat,
JAR, versionsidentitet, licens och testartefakter publiceras även vid fel.

`.github/workflows/mc26-client.yml` och separata 26.3-världs-, inventory-,
navigerings-, assets-, stock- och sorting-workflows testar verklig 26.3-
klient under Xvfb/Mesa och där det är relevant en vanilla-server och
Manager i Docker. En framgångsrik JUnit-build är **inte** fysisk E2E.

`.github/workflows/manager-image.yml` anropar baslinjeworkflowet som
releasegrind och får endast publicera GHCR-image från betrodda main-/tagg-
händelser. PR-jobb har `contents:read`, inga skrivtokens för paket.

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
väljer uttryckligen Java 21. Dokumentera faktisk GitHub Actions-status separat
från lokala byggen. De tidigare 1.21.x-resultaten finns enbart i
[historisk baslinje](BASELINE.md).
